/**
 * Runtime type guard — verifies an unknown value exposes the required Source API.
 */
export function isSource(value) {
    if (value === null || typeof value !== 'object')
        return false;
    const candidate = value;
    return (typeof candidate.id === 'string' &&
        typeof candidate.name === 'string' &&
        typeof candidate.baseUrl === 'string' &&
        typeof candidate.lang === 'string' &&
        typeof candidate.getWebtoonDetails === 'function' &&
        typeof candidate.getChapterList === 'function' &&
        typeof candidate.getPageList === 'function');
}
/**
 * Validates that a Source implementation returns well-formed data.
 * Useful in extension CI or host-side smoke tests.
 */
export async function assertSourceContract(source, sampleDetailUrl) {
    const metadata = await source.getWebtoonDetails(sampleDetailUrl);
    if (!metadata.id || !metadata.title) {
        throw new Error('getWebtoonDetails must return id and title');
    }
    const chapters = await source.getChapterList(metadata.id);
    if (!Array.isArray(chapters)) {
        throw new Error('getChapterList must return an array');
    }
    if (chapters.length > 0) {
        const pages = await source.getPageList(chapters[0].id);
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
//# sourceMappingURL=source.js.map