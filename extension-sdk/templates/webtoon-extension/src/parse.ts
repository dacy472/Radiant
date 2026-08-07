/**
 * DOM parsing helpers — replace the stub implementations with a real HTML parser.
 *
 * Recommended libraries (install in your extension package, then bundle for deploy):
 *   - cheerio  — server-side jQuery-like API (Node / bundled extensions)
 *   - linkedom — lightweight DOM for sandboxed runtimes
 *
 * This file uses regex stubs so the template compiles without extra dependencies.
 * Swap each function body for cheerio selectors once you inspect the target site.
 */
import type {
  Chapter,
  PageUrl,
  UrlString,
  WebtoonMetadata,
  WebtoonStatus,
  WebtoonSummary,
} from '@radiant/extension-sdk';
import { absoluteUrl } from './http.js';

// ─── Detail page ─────────────────────────────────────────────────────────────

/**
 * DOM PARSING — webtoon detail / series page
 *
 * Given the raw HTML of a series page, extract metadata only (no chapter list).
 *
 * Example target pages:
 *   - Lord of the Mysteries  → /manga/lord-of-the-mysteries
 *   - Renegade Immortal      → /manga/renegade-immortal
 *
 * Typical selectors to look for in DevTools:
 *   - Title:        h1.entry-title, .manga-title, [itemprop="name"]
 *   - Cover:        .summary_image img, .thumb img[src], meta[property="og:image"]
 *   - Description:  .description, #noidungm, .synopsis p
 *   - Author:       .author-content a, .manga-authors
 *   - Status:       .summary-content, span:contains("Status")
 *   - Genres:       .genres-content a, .manga-genres a
 */
export function parseWebtoonDetailPage(
  html: string,
  detailUrl: UrlString,
  baseUrl: string,
): WebtoonMetadata {
  // ── cheerio example (uncomment after `npm install cheerio`) ──────────────
  // const $ = cheerio.load(html);
  // const title = $('h1.entry-title').first().text().trim();
  // const thumbnail = $('div.summary_image img').attr('src') ?? '';
  // const description = $('div.description').text().trim();
  // const author = $('.author-content a').first().text().trim();
  // const statusText = $('.summary-content').filter((_, el) =>
  //   $(el).prev().text().toLowerCase().includes('status'),
  // ).text().trim();

  // Stub regex fallbacks — replace with real selectors:
  const title =
    matchOne(html, /<h1[^>]*>([^<]+)<\/h1>/i) ??
    'Unknown Title';

  const thumbnail =
    matchOne(html, /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i) ??
    matchOne(html, /<img[^>]+class=["'][^"']*cover[^"']*["'][^>]+src=["']([^"']+)["']/i) ??
    '';

  const description =
    matchOne(html, /<div[^>]+class=["'][^"']*description[^"']*["'][^>]*>([\s\S]*?)<\/div>/i)?.replace(/<[^>]+>/g, '').trim();

  const author = matchOne(html, /author[^>]*>[\s\S]*?<a[^>]*>([^<]+)<\/a>/i);

  const status = parseStatus(
    matchOne(html, /status[^>]*>[\s\S]*?<[^>]+>([^<]+)</i),
  );

  const id = detailUrl.replace(baseUrl, '').replace(/^\/+/, '') || title.toLowerCase().replace(/\s+/g, '-');

  return {
    id,
    title,
    thumbnail: absoluteUrl(baseUrl, thumbnail),
    url: detailUrl,
    ...(description ? { description } : {}),
    ...(author ? { author } : {}),
    ...(status ? { status } : {}),
    genres: parseGenres(html),
  };
}

// ─── Chapter list ────────────────────────────────────────────────────────────

/**
 * DOM PARSING — chapter list on a series page (or dedicated /chapters tab)
 *
 * Most scanlator sites render chapters as `<a>` rows inside a list or table.
 * Chapters are usually in reverse order (newest first) — reverse if your
 * reader expects oldest-first.
 *
 * Example — Lord of the Mysteries chapter row HTML (illustrative):
 * ```html
 * <li class="wp-manga-chapter">
 *   <a href="/manga/lord-of-the-mysteries/chapter-1234/">Chapter 1234</a>
 *   <span class="chapter-release-date">2 days ago</span>
 * </li>
 * ```
 *
 * Example — Renegade Immortal (illustrative):
 * ```html
 * <div class="chapter-list">
 *   <a href="/series/renegade-immortal/ch-089/">
 *     <span class="chap-num">Chapter 89</span>
 *     <span class="chap-title">Foundation Establishment</span>
 *   </a>
 * </div>
 * ```
 *
 * cheerio sketch:
 * ```ts
 * const chapters: Chapter[] = [];
 * $('li.wp-manga-chapter, .chapter-list a').each((i, el) => {
 *   const $el = $(el).is('a') ? $(el) : $(el).find('a').first();
 *   const href = $el.attr('href');
 *   const title = $el.text().trim();
 *   const number = parseFloat(title.match(/(\d+(\.\d+)?)/)?.[1] ?? String(i + 1));
 *   chapters.push({
 *     id: href!,
 *     title,
 *     url: absoluteUrl(baseUrl, href!),
 *     number,
 *   });
 * });
 * return chapters.reverse();
 * ```
 */
export function parseChapterList(html: string, baseUrl: string): Chapter[] {
  const chapters: Chapter[] = [];
  const linkPattern =
    /<a[^>]+href=["']([^"']*(?:chapter|ch)[^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi;

  let match: RegExpExecArray | null;
  let index = 0;
  while ((match = linkPattern.exec(html)) !== null) {
    const href = match[1] ?? '';
    const rawTitle = (match[2] ?? '').replace(/<[^>]+>/g, '').trim();
    if (!href || !rawTitle) continue;

    const number = parseChapterNumber(rawTitle);
    chapters.push({
      id: href,
      title: rawTitle,
      url: absoluteUrl(baseUrl, href),
      ...(number !== undefined ? { number } : {}),
      read: false,
    });
    index += 1;
  }

  // Deduplicate by chapter URL and return oldest-first for the reader.
  const unique = [...new Map(chapters.map((ch) => [ch.id, ch])).values()];
  return unique.reverse();
}

// ─── Reader pages ────────────────────────────────────────────────────────────

/**
 * DOM PARSING — chapter reader / viewer page (image URLs)
 *
 * Webtoon pages are almost always a vertical stack of `<img>` tags, sometimes
 * lazy-loaded via `data-src` instead of `src`.
 *
 * Example reader markup (illustrative):
 * ```html
 * <div class="reading-content">
 *   <img id="image-0" data-src="https://cdn.example.com/lotm/ch1234/01.jpg">
 *   <img id="image-1" data-src="https://cdn.example.com/lotm/ch1234/02.jpg">
 * </div>
 * ```
 *
 * Some sites embed JSON instead of bare img tags:
 * ```html
 * <script>var chapterImages = ["https://…/01.jpg","https://…/02.jpg"];</script>
 * ```
 *
 * cheerio sketch:
 * ```ts
 * const pages: PageUrl[] = [];
 * $('div.reading-content img').each((i, el) => {
 *   const src = $(el).attr('data-src') ?? $(el).attr('src');
 *   if (!src) return;
 *   pages.push({
 *     index: i,
 *     url: absoluteUrl(baseUrl, src),
 *     headers: { Referer: chapterUrl },
 *   });
 * });
 * ```
 *
 * For titles like Lord of the Mysteries or Renegade Immortal on CDN-protected
 * hosts, you may need per-page `Referer` headers pointing at the chapter URL.
 */
export function parsePageList(
  html: string,
  chapterUrl: UrlString,
  baseUrl: string,
): PageUrl[] {
  const pages: PageUrl[] = [];

  // Strategy 1: <img> tags in reading container
  const imgPattern =
    /<img[^>]+(?:data-src|data-lazy-src|src)=["']([^"']+\.(?:jpg|jpeg|png|webp)(?:\?[^"']*)?)["'][^>]*>/gi;

  let match: RegExpExecArray | null;
  while ((match = imgPattern.exec(html)) !== null) {
    const src = match[1];
    if (!src || src.includes('avatar') || src.includes('logo')) continue;
    pages.push({
      index: pages.length,
      url: absoluteUrl(baseUrl, src),
      headers: { Referer: chapterUrl },
    });
  }

  // Strategy 2: JSON array embedded in a script tag (uncomment / adapt):
  // const jsonMatch = html.match(/chapterImages\s*=\s*(\[[\s\S]*?\]);/);
  // if (jsonMatch) {
  //   const urls: string[] = JSON.parse(jsonMatch[1]!);
  //   return urls.map((url, index) => ({
  //     index,
  //     url: absoluteUrl(baseUrl, url),
  //     headers: { Referer: chapterUrl },
  //   }));
  // }

  return pages;
}

// ─── Browse / search grids ─────────────────────────────────────────────────

/**
 * DOM PARSING — popular / latest / search result grid
 *
 * Example search results for "Lord of the Mysteries" or "Renegade Immortal":
 * ```html
 * <div class="page-item-detail">
 *   <a href="/manga/lord-of-the-mysteries/">
 *     <img src="https://cdn…/cover.jpg" alt="Lord of the Mysteries">
 *     <h3>Lord of the Mysteries</h3>
 *   </a>
 * </div>
 * ```
 */
export function parseWebtoonCards(html: string, baseUrl: string): WebtoonSummary[] {
  const cards: WebtoonSummary[] = [];
  const cardPattern =
    /<a[^>]+href=["']([^"']*\/manga\/[^"']+)["'][^>]*>[\s\S]*?<\/a>/gi;

  let match: RegExpExecArray | null;
  while ((match = cardPattern.exec(html)) !== null) {
    const href = match[1];
    if (!href) continue;
    const chunk = match[0] ?? '';
    const title =
      matchOne(chunk, /<h\d[^>]*>([^<]+)<\/h\d>/i) ??
      matchOne(chunk, /alt=["']([^"']+)["']/i) ??
      'Unknown';
    const thumb =
      matchOne(chunk, /<img[^>]+src=["']([^"']+)["']/i) ?? '';

    cards.push({
      id: href.replace(/^\/+/, ''),
      title,
      thumbnail: absoluteUrl(baseUrl, thumb),
      url: absoluteUrl(baseUrl, href),
    });
  }

  return [...new Map(cards.map((c) => [c.id, c])).values()];
}

// ─── Small utilities ─────────────────────────────────────────────────────────

function matchOne(html: string, pattern: RegExp): string | undefined {
  const result = pattern.exec(html);
  return result?.[1]?.trim();
}

function parseChapterNumber(title: string): number | undefined {
  const match = title.match(/(\d+(?:\.\d+)?)/);
  return match ? Number.parseFloat(match[1]!) : undefined;
}

function parseStatus(raw: string | undefined): WebtoonStatus | undefined {
  if (!raw) return undefined;
  const lower = raw.toLowerCase();
  if (lower.includes('ongoing') || lower.includes('publishing')) return 'ongoing';
  if (lower.includes('complete') || lower.includes('finished')) return 'completed';
  if (lower.includes('hiatus')) return 'hiatus';
  if (lower.includes('cancel')) return 'cancelled';
  return 'unknown';
}

function parseGenres(html: string): string[] {
  const genres: string[] = [];
  const genrePattern = /class=["'][^"']*genre[^"']*["'][^>]*>[\s\S]*?<a[^>]*>([^<]+)<\/a>/gi;
  let match: RegExpExecArray | null;
  while ((match = genrePattern.exec(html)) !== null) {
    const genre = match[1]?.trim();
    if (genre) genres.push(genre);
  }
  return genres;
}
