/**
 * Core data models returned by Radiant webtoon extensions.
 * These types mirror the Kotlin models in `com.radiant.webtoon.core.model`.
 */
/** Opaque identifier for a webtoon within a source. */
export type WebtoonId = string;
/** Opaque identifier for a chapter within a source. */
export type ChapterId = string;
/** Absolute or source-relative URL string. */
export type UrlString = string;
/** ISO-8601 timestamp or human-readable date string from the source. */
export type DateString = string;
/**
 * Publication status of a webtoon as reported by the source.
 */
export type WebtoonStatus = 'ongoing' | 'completed' | 'hiatus' | 'cancelled' | 'unknown';
/**
 * Metadata for a single webtoon, without an embedded chapter list.
 * Use {@link Source.getChapterList} to fetch chapters separately.
 */
export interface WebtoonMetadata {
    /** Stable ID within this source (path segment, slug, or canonical URL). */
    readonly id: WebtoonId;
    readonly title: string;
    /** Cover / thumbnail image URL. */
    readonly thumbnail: UrlString;
    /** Canonical detail-page URL on the source site. */
    readonly url: UrlString;
    readonly status?: WebtoonStatus;
    readonly author?: string;
    readonly description?: string;
    readonly genres?: readonly string[];
    /** True when the source marks this entry as mature / NSFW. */
    readonly nsfw?: boolean;
}
/**
 * A single chapter entry in a webtoon series.
 */
export interface Chapter {
    readonly id: ChapterId;
    readonly title: string;
    /** Reader or detail URL for this chapter on the source site. */
    readonly url?: UrlString;
    /** Upload or publication date as returned by the source. */
    readonly date?: DateString;
    /** Numeric chapter index (e.g. 12.5 for bonus chapters). */
    readonly number?: number;
    /** Local read state — extensions should default to `false`. */
    readonly read?: boolean;
}
/**
 * A single reader page (image or embedded asset) inside a chapter.
 */
export interface PageUrl {
    /** Zero-based page order within the chapter. */
    readonly index: number;
    /** Absolute URL of the page image or asset. */
    readonly url: UrlString;
    /**
     * Optional HTTP headers required to fetch this page
     * (e.g. Referer, User-Agent overrides).
     */
    readonly headers?: Readonly<Record<string, string>>;
}
/**
 * Lightweight webtoon summary used in browse / search result lists.
 */
export interface WebtoonSummary {
    readonly id: WebtoonId;
    readonly title: string;
    readonly thumbnail: UrlString;
    readonly url?: UrlString;
}
/**
 * Paginated list of webtoons from browse or search endpoints.
 */
export interface PagedWebtoonResponse {
    readonly webtoons: readonly WebtoonSummary[];
    readonly hasNextPage: boolean;
}
/**
 * Describes what optional features a source implements.
 */
export interface SourceCapabilities {
    readonly supportsSearch: boolean;
    readonly supportsLatest: boolean;
    readonly supportsNsfw: boolean;
    readonly supportsFilters: boolean;
}
/**
 * Static identity and configuration exposed by every extension.
 */
export interface SourceInfo {
    /** Globally unique source ID (e.g. `"en.asurascans"`). */
    readonly id: string;
    readonly name: string;
    /** Root site URL, without trailing slash. */
    readonly baseUrl: UrlString;
    /** BCP-47 or Tachiyomi-style language code (e.g. `"en"`). */
    readonly lang: string;
    /**
     * Display icon — one of:
     * - `https://…` remote URL
     * - `emoji:📖` emoji shorthand
     * - `pkg:com.example.ext` Android package reference
     */
    readonly icon: string;
    readonly capabilities: SourceCapabilities;
}
/**
 * Optional filter passed to search/browse requests.
 */
export interface SourceFilter {
    readonly id: string;
    readonly name: string;
    readonly value: string | number | boolean;
}
export type FilterList = readonly SourceFilter[];
/**
 * Optional per-request context passed into source methods.
 */
export interface SourceRequestContext {
    /** AbortSignal for cooperative cancellation. */
    readonly signal?: AbortSignal;
}
/**
 * Typed errors extensions may throw or return.
 */
export type SourceErrorCode = 'network' | 'parsing' | 'captcha' | 'not_found' | 'unsupported' | 'unknown';
export interface SourceError extends Error {
    readonly code: SourceErrorCode;
    readonly cause?: unknown;
    /** Present when `code === 'captcha'`. */
    readonly captchaUrl?: UrlString;
}
//# sourceMappingURL=models.d.ts.map