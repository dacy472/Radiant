/**
 * Shared HTTP helpers for extension authors.
 *
 * The Radiant host injects `fetch` into the sandbox. Use these wrappers so
 * every request shares headers (User-Agent, Referer) and respects cancellation.
 */
import type { SourceRequestContext, UrlString } from '@radiant/extension-sdk';

export interface FetchHtmlOptions {
  readonly context?: SourceRequestContext;
  readonly headers?: Readonly<Record<string, string>>;
}

/**
 * Download a page and return its HTML body as a string.
 *
 * HTML FETCHING GOES HERE:
 * - Build the absolute URL (join baseUrl + path, or use the url argument as-is).
 * - Pass `context?.signal` so the host can cancel long-running requests.
 * - Add site-specific headers if Cloudflare or hotlink protection blocks you.
 */
export async function fetchHtml(
  url: UrlString,
  options: FetchHtmlOptions = {},
): Promise<string> {
  const response = await fetch(url, {
    signal: options.context?.signal,
    headers: {
      'User-Agent':
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      Accept: 'text/html,application/xhtml+xml',
      ...options.headers,
    },
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${url}`);
  }

  return response.text();
}

/** Join a site root and a relative path without double slashes. */
export function absoluteUrl(baseUrl: string, pathOrUrl: string): UrlString {
  if (pathOrUrl.startsWith('http://') || pathOrUrl.startsWith('https://')) {
    return pathOrUrl;
  }
  const base = baseUrl.replace(/\/+$/, '');
  const path = pathOrUrl.replace(/^\/+/, '');
  return `${base}/${path}`;
}

/**
 * Stable ID helper — many sources use the URL path as the canonical webtoon ID.
 *
 * Example:
 *   extractIdFromUrl('https://example.com/manga/lord-of-the-mysteries')
 *   → 'manga/lord-of-the-mysteries'
 */
export function extractIdFromUrl(detailUrl: UrlString, baseUrl: string): string {
  const base = baseUrl.replace(/\/+$/, '');
  return detailUrl.replace(base, '').replace(/^\/+/, '');
}
