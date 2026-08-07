import type {
  Chapter,
  ChapterId,
  FilterList,
  PageUrl,
  PagedWebtoonResponse,
  SourceInfo,
  SourceRequestContext,
  UrlString,
  WebtoonId,
  WebtoonMetadata,
} from './models.js';

/**
 * Strict contract every external Radiant webtoon extension must implement.
 *
 * Extensions are pure async data providers: they fetch and parse remote
 * catalog pages and return normalized TypeScript objects. The host app
 * handles caching, downloads, UI, and reader presentation.
 *
 * @example
 * ```ts
 * export class AsuraScansSource implements Source {
 *   readonly info = { id: 'en.asurascans', name: 'Asura Scans', ... };
 *
 *   async getWebtoonDetails(url, ctx) {
 *     const html = await this.fetch(url, ctx);
 *     return parseDetailPage(html);
 *   }
 * }
 * ```
 */
export interface Source extends SourceInfo {
  // ─── Detail & reader (required) ───────────────────────────────────────────

  /**
   * Fetch metadata for a single webtoon.
   *
   * @param url - Canonical detail-page URL or source-relative webtoon path.
   *              Implementations must accept both absolute URLs and IDs
   *              returned from browse/search results.
   */
  getWebtoonDetails(
    url: UrlString,
    context?: SourceRequestContext,
  ): Promise<WebtoonMetadata>;

  /**
   * List all chapters for a webtoon.
   *
   * @param webtoonId - Stable ID from {@link WebtoonMetadata.id} or browse results.
   */
  getChapterList(
    webtoonId: WebtoonId,
    context?: SourceRequestContext,
  ): Promise<readonly Chapter[]>;

  /**
   * Resolve ordered page URLs for a chapter reader session.
   *
   * @param chapterId - Stable ID from {@link Chapter.id}.
   */
  getPageList(
    chapterId: ChapterId,
    context?: SourceRequestContext,
  ): Promise<readonly PageUrl[]>;

  // ─── Browse & discovery (optional — guard with capabilities) ────────────────

  /**
   * Popular / trending catalog page. Required when `capabilities.supportsSearch`
   * is false and this is the primary entry point for the source.
   */
  fetchPopularManga?(
    page: number,
    context?: SourceRequestContext,
  ): Promise<PagedWebtoonResponse>;

  /** Latest-updates catalog page. Only called when `capabilities.supportsLatest`. */
  fetchLatestManga?(
    page: number,
    context?: SourceRequestContext,
  ): Promise<PagedWebtoonResponse>;

  /** Full-text or filtered search. Only called when `capabilities.supportsSearch`. */
  fetchSearchManga?(
    page: number,
    query: string,
    filters: FilterList,
    context?: SourceRequestContext,
  ): Promise<PagedWebtoonResponse>;

  /** Filter definitions for the search UI. */
  getFilterList?(): FilterList;

  // ─── Host integration helpers (optional) ──────────────────────────────────

  /**
   * Build the canonical manga detail URL for WebView / open-in-browser flows.
   * Default behavior: join `baseUrl` and `webtoonId`.
   */
  getMangaPageUrl?(webtoonId: WebtoonId): UrlString;

  /** HTTP headers to inject when loading source pages in a WebView. */
  getWebViewHeaders?(): Readonly<Record<string, string>>;
}

/**
 * Runtime type guard — verifies an unknown value exposes the required Source API.
 */
export function isSource(value: unknown): value is Source {
  if (value === null || typeof value !== 'object') return false;
  const candidate = value as Partial<Source>;
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.name === 'string' &&
    typeof candidate.baseUrl === 'string' &&
    typeof candidate.lang === 'string' &&
    typeof candidate.getWebtoonDetails === 'function' &&
    typeof candidate.getChapterList === 'function' &&
    typeof candidate.getPageList === 'function'
  );
}

/**
 * Validates that a Source implementation returns well-formed data.
 * Useful in extension CI or host-side smoke tests.
 */
export async function assertSourceContract(
  source: Source,
  sampleDetailUrl: UrlString,
): Promise<void> {
  const metadata = await source.getWebtoonDetails(sampleDetailUrl);
  if (!metadata.id || !metadata.title) {
    throw new Error('getWebtoonDetails must return id and title');
  }

  const chapters = await source.getChapterList(metadata.id);
  if (!Array.isArray(chapters)) {
    throw new Error('getChapterList must return an array');
  }

  if (chapters.length > 0) {
    const pages = await source.getPageList(chapters[0]!.id);
    if (!Array.isArray(pages) || pages.length === 0) {
      throw new Error('getPageList must return a non-empty array for readable chapters');
    }
    for (const [i, page] of pages.entries()) {
      if (page.index !== i) {
        throw new Error(`PageUrl.index must be zero-based and sequential (expected ${i}, got ${page.index})`);
      }
      if (!page.url) {
        throw new Error('Each PageUrl must include a url');
      }
    }
  }
}
