import type { Chapter, ChapterId, FilterList, PageUrl, PagedWebtoonResponse, SourceInfo, SourceRequestContext, UrlString, WebtoonId, WebtoonMetadata } from './models.js';
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
    /**
     * Fetch metadata for a single webtoon.
     *
     * @param url - Canonical detail-page URL or source-relative webtoon path.
     *              Implementations must accept both absolute URLs and IDs
     *              returned from browse/search results.
     */
    getWebtoonDetails(url: UrlString, context?: SourceRequestContext): Promise<WebtoonMetadata>;
    /**
     * List all chapters for a webtoon.
     *
     * @param webtoonId - Stable ID from {@link WebtoonMetadata.id} or browse results.
     */
    getChapterList(webtoonId: WebtoonId, context?: SourceRequestContext): Promise<readonly Chapter[]>;
    /**
     * Resolve ordered page URLs for a chapter reader session.
     *
     * @param chapterId - Stable ID from {@link Chapter.id}.
     */
    getPageList(chapterId: ChapterId, context?: SourceRequestContext): Promise<readonly PageUrl[]>;
    /**
     * Popular / trending catalog page. Required when `capabilities.supportsSearch`
     * is false and this is the primary entry point for the source.
     */
    fetchPopularManga?(page: number, context?: SourceRequestContext): Promise<PagedWebtoonResponse>;
    /** Latest-updates catalog page. Only called when `capabilities.supportsLatest`. */
    fetchLatestManga?(page: number, context?: SourceRequestContext): Promise<PagedWebtoonResponse>;
    /** Full-text or filtered search. Only called when `capabilities.supportsSearch`. */
    fetchSearchManga?(page: number, query: string, filters: FilterList, context?: SourceRequestContext): Promise<PagedWebtoonResponse>;
    /** Filter definitions for the search UI. */
    getFilterList?(): FilterList;
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
export declare function isSource(value: unknown): value is Source;
/**
 * Validates that a Source implementation returns well-formed data.
 * Useful in extension CI or host-side smoke tests.
 */
export declare function assertSourceContract(source: Source, sampleDetailUrl: UrlString): Promise<void>;
//# sourceMappingURL=source.d.ts.map