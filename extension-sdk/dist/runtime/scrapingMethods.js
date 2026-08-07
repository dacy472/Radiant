/** Source methods that perform network fetch + HTML/DOM parsing — run off the main thread. */
export const SCRAPING_SOURCE_METHODS = [
    'getWebtoonDetails',
    'getChapterList',
    'getPageList',
    'fetchPopularManga',
    'fetchLatestManga',
    'fetchSearchManga',
];
export function isScrapingSourceMethod(method) {
    return SCRAPING_SOURCE_METHODS.includes(method);
}
//# sourceMappingURL=scrapingMethods.js.map