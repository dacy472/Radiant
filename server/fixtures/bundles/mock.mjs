export default class MockSource {
  id = 'en.mock';
  name = 'Mock Catalog';
  baseUrl = 'https://example.com';
  lang = 'en';
  icon = 'https://picsum.photos/seed/mock/128';
  capabilities = {
    supportsSearch: true,
    supportsLatest: false,
    supportsNsfw: false,
    supportsFilters: false,
  };

  async getWebtoonDetails(url) {
    return {
      id: 'mock-1',
      title: 'Mock Webtoon',
      thumbnail: 'https://picsum.photos/seed/mock-cover/200/300',
      url,
    };
  }

  async getChapterList() {
    return [{ id: 'mock-ch-1', title: 'Chapter 1', number: 1 }];
  }

  async getPageList() {
    return [{ index: 0, url: 'https://picsum.photos/seed/mock-page/800/1200' }];
  }
}
