/**
 * Boilerplate Radiant webtoon extension.
 *
 * Rename this class, update `id` / `name` / `baseUrl`, then fill in the
 * fetch + parse steps marked with comments in each method.
 *
 * Build & publish:
 *   1. `npm install && npm run build`
 *   2. Upload `dist/bundle.mjs` to your CDN
 *   3. Add an entry to your repo `index.json` with the bundleUrl
 */
import type {
  Chapter,
  ChapterId,
  FilterList,
  PageUrl,
  PagedWebtoonResponse,
  Source,
  SourceCapabilities,
  SourceRequestContext,
  UrlString,
  WebtoonId,
  WebtoonMetadata,
} from '@radiant/extension-sdk';

import { absoluteUrl, fetchHtml } from './http.js';
import {
  parseChapterList,
  parsePageList,
  parseWebtoonCards,
  parseWebtoonDetailPage,
} from './parse.js';

/**
 * Replace every `YOUR_*` constant before shipping.
 */
const SOURCE_ID = 'en.your-source';
const SOURCE_NAME = 'Your Source Name';
const SOURCE_BASE_URL = 'https://example.com';
const SOURCE_LANG = 'en';
const SOURCE_ICON = 'https://example.com/icon.png';

const CAPABILITIES: SourceCapabilities = {
  supportsSearch: true,
  supportsLatest: true,
  supportsNsfw: false,
  supportsFilters: false,
};

export default class YourSource implements Source {
  readonly id = SOURCE_ID;
  readonly name = SOURCE_NAME;
  readonly baseUrl = SOURCE_BASE_URL;
  readonly lang = SOURCE_LANG;
  readonly icon = SOURCE_ICON;
  readonly capabilities = CAPABILITIES;

  // ─── Required: detail & reader ────────────────────────────────────────────

  /**
   * Fetch series metadata for one webtoon.
   *
   * Flow:
   *   1. Normalize `url` to an absolute detail-page URL.
   *   2. `fetchHtml` — download the page (see `http.ts`).
   *   3. `parseWebtoonDetailPage` — extract title, cover, synopsis (see `parse.ts`).
   *
   * Example URLs you might pass in tests:
   *   - Lord of the Mysteries  → `${baseUrl}/manga/lord-of-the-mysteries`
   *   - Renegade Immortal      → `${baseUrl}/manga/renegade-immortal`
   */
  async getWebtoonDetails(
    url: UrlString,
    context?: SourceRequestContext,
  ): Promise<WebtoonMetadata> {
    const detailUrl = absoluteUrl(this.baseUrl, url);

    // ── HTML FETCHING: download the series detail page ─────────────────────
    const html = await fetchHtml(detailUrl, {
      context,
      headers: this.getWebViewHeaders(),
    });

    // ── DOM PARSING: title, thumbnail, description, author, status, genres ─
    return parseWebtoonDetailPage(html, detailUrl, this.baseUrl);
  }

  /**
   * Return every chapter for a webtoon.
   *
   * Most sites embed the chapter list on the same page as the synopsis.
   * Others use a separate `/chapters` endpoint — fetch that URL instead if needed.
   *
   * `webtoonId` is typically the path segment you returned from getWebtoonDetails,
   * e.g. `"manga/lord-of-the-mysteries"`.
   */
  async getChapterList(
    webtoonId: WebtoonId,
    context?: SourceRequestContext,
  ): Promise<readonly Chapter[]> {
    const detailUrl = this.getMangaPageUrl(webtoonId);

    // ── HTML FETCHING: series page (or dedicated chapter-list URL) ───────────
    const html = await fetchHtml(detailUrl, {
      context,
      headers: this.getWebViewHeaders(),
    });

    // ── DOM PARSING: walk chapter <a> rows, extract id/title/number/date ─────
    // See parseChapterList() for Lord of the Mysteries / Renegade Immortal examples.
    return parseChapterList(html, this.baseUrl);
  }

  /**
   * Return ordered image URLs for the reader.
   *
   * `chapterId` is usually the href you stored on each Chapter (relative or absolute).
   */
  async getPageList(
    chapterId: ChapterId,
    context?: SourceRequestContext,
  ): Promise<readonly PageUrl[]> {
    const chapterUrl = absoluteUrl(this.baseUrl, chapterId);

    // ── HTML FETCHING: open the chapter reader page ─────────────────────────
    const html = await fetchHtml(chapterUrl, {
      context,
      headers: this.getWebViewHeaders(),
    });

    // ── DOM PARSING: collect <img> tags or JSON image arrays ────────────────
    // Many CDNs require Referer = chapterUrl (handled in parsePageList).
    const pages = parsePageList(html, chapterUrl, this.baseUrl);

    if (pages.length === 0) {
      throw new Error(`No pages found for chapter ${chapterId}`);
    }

    return pages;
  }

  // ─── Optional: browse & search ──────────────────────────────────────────────

  async fetchPopularManga(
    page: number,
    context?: SourceRequestContext,
  ): Promise<PagedWebtoonResponse> {
    const listUrl = absoluteUrl(this.baseUrl, `/manga?page=${page}`);

    // ── HTML FETCHING: popular / trending catalog page ───────────────────────
    const html = await fetchHtml(listUrl, { context, headers: this.getWebViewHeaders() });

    // ── DOM PARSING: grid of series cards (cover + title + detail link) ────
    const webtoons = parseWebtoonCards(html, this.baseUrl);

    return {
      webtoons,
      hasNextPage: webtoons.length > 0,
    };
  }

  async fetchLatestManga(
    page: number,
    context?: SourceRequestContext,
  ): Promise<PagedWebtoonResponse> {
    const listUrl = absoluteUrl(this.baseUrl, `/manga?sort=latest&page=${page}`);

    const html = await fetchHtml(listUrl, { context, headers: this.getWebViewHeaders() });
    const webtoons = parseWebtoonCards(html, this.baseUrl);

    return { webtoons, hasNextPage: webtoons.length > 0 };
  }

  /**
   * Site search — users might query "Lord of Mysteries", "LOTM", "Renegade Immortal", etc.
   *
   * Inspect the site's search form in DevTools to find the query parameter name
   * (often `q`, `s`, or `search`).
   */
  async fetchSearchManga(
    page: number,
    query: string,
    _filters: FilterList,
    context?: SourceRequestContext,
  ): Promise<PagedWebtoonResponse> {
    const encoded = encodeURIComponent(query);
    const searchUrl = absoluteUrl(this.baseUrl, `/search?q=${encoded}&page=${page}`);

    // ── HTML FETCHING: search results page ───────────────────────────────────
    const html = await fetchHtml(searchUrl, { context, headers: this.getWebViewHeaders() });

    // ── DOM PARSING: same card grid as popular, but filtered by search term ──
    const webtoons = parseWebtoonCards(html, this.baseUrl);

    return { webtoons, hasNextPage: webtoons.length > 0 };
  }

  getFilterList(): FilterList {
    return [];
  }

  // ─── Host helpers ───────────────────────────────────────────────────────────

  getMangaPageUrl(webtoonId: WebtoonId): UrlString {
    return absoluteUrl(this.baseUrl, webtoonId);
  }

  getWebViewHeaders(): Readonly<Record<string, string>> {
    return {
      Referer: this.baseUrl,
      Origin: this.baseUrl,
    };
  }
}

/** Factory export — alternative to `export default` for some bundlers. */
export function createSource(): Source {
  return new YourSource();
}

/** Convenience for local smoke tests. */
export async function smokeTest(detailPath: string): Promise<void> {
  const source = new YourSource();
  const detailUrl = absoluteUrl(source.baseUrl, detailPath);
  const meta = await source.getWebtoonDetails(detailUrl);
  console.log('Detail:', meta.title);
  const chapters = await source.getChapterList(meta.id);
  console.log(`Chapters: ${chapters.length}`);
  if (chapters[0]) {
    const pages = await source.getPageList(chapters[0].id);
    console.log(`Pages in first chapter: ${pages.length}`);
  }
}

// Example manual test paths (uncomment to try):
// smokeTest('/manga/lord-of-the-mysteries');
// smokeTest('/manga/renegade-immortal');
