export default class PicsumSource {
  id = 'en.picsum';
  name = 'Picsum Demo';
  baseUrl = 'https://picsum.photos';
  lang = 'en';
  icon = 'https://picsum.photos/seed/radiant/128';
  capabilities = {
    supportsSearch: false,
    supportsLatest: false,
    supportsNsfw: false,
    supportsFilters: false,
  };

  async getWebtoonDetails(url) {
    return {
      id: 'picsum-1',
      title: 'Picsum Demo',
      thumbnail: 'https://picsum.photos/seed/picsum-cover/200/300',
      url,
    };
  }

  async getChapterList() {
    return [{ id: 'picsum-ch-1', title: 'Chapter 1', number: 1 }];
  }

  async getPageList() {
    return [
      { index: 0, url: 'https://picsum.photos/seed/picsum-a/800/1200' },
      { index: 1, url: 'https://picsum.photos/seed/picsum-b/800/1200' },
    ];
  }
}
