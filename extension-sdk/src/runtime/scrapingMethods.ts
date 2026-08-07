/** Source methods that perform network fetch + HTML/DOM parsing — run off the main thread. */
export const SCRAPING_SOURCE_METHODS = [
  'getWebtoonDetails',
  'getChapterList',
  'getPageList',
  'fetchPopularManga',
  'fetchLatestManga',
  'fetchSearchManga',
] as const;

export type ScrapingSourceMethod = (typeof SCRAPING_SOURCE_METHODS)[number];

export function isScrapingSourceMethod(method: string): method is ScrapingSourceMethod {
  return (SCRAPING_SOURCE_METHODS as readonly string[]).includes(method);
}
