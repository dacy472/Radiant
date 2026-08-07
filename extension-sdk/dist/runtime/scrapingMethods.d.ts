/** Source methods that perform network fetch + HTML/DOM parsing — run off the main thread. */
export declare const SCRAPING_SOURCE_METHODS: readonly ["getWebtoonDetails", "getChapterList", "getPageList", "fetchPopularManga", "fetchLatestManga", "fetchSearchManga"];
export type ScrapingSourceMethod = (typeof SCRAPING_SOURCE_METHODS)[number];
export declare function isScrapingSourceMethod(method: string): method is ScrapingSourceMethod;
//# sourceMappingURL=scrapingMethods.d.ts.map