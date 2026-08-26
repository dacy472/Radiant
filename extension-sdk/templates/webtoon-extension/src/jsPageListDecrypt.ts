import vm from 'node:vm';
import type { PageUrl, UrlString } from '@radiant/extension-sdk';
import { absoluteUrl } from './http.js';

const OBFUSCATION_MARKERS = [
  'eval(',
  'Function(',
  'atob(',
  'decodeURIComponent',
  'decrypt',
  '__NEXT_DATA__',
];

const URL_REGEX =
  /https?:\/\/[^\s"'<>\\]+?\.(?:jpg|jpeg|png|webp)(?:\?[^\s"'<>\\]*)?/gi;

function createSandbox(): vm.Context {
  return vm.createContext({
    URL,
    TextEncoder,
    TextDecoder,
    console,
    JSON,
    Array,
    Object,
    String,
    Number,
    parseInt,
    parseFloat,
    atob: (value: string) => Buffer.from(value, 'base64').toString('utf8'),
    btoa: (value: string) => Buffer.from(value, 'utf8').toString('base64'),
  });
}

function evaluateScript(script: string, timeoutMs = 5_000): unknown {
  const sandbox = createSandbox();
  const wrapped = `'use strict';\n${script}`;
  return vm.runInContext(wrapped, sandbox, { timeout: timeoutMs });
}

function parseUrlList(raw: unknown): string[] {
  if (Array.isArray(raw)) {
    return raw.filter((entry): entry is string => typeof entry === 'string' && entry.startsWith('http'));
  }
  if (typeof raw === 'string') {
    try {
      return parseUrlList(JSON.parse(raw));
    } catch {
      const matches = raw.match(URL_REGEX) ?? [];
      return [...new Set(matches)];
    }
  }
  return [];
}

function buildCandidates(script: string): string[] {
  const trimmed = script.trim();
  return [
    `(function(){ try { var __result = (function(){ ${trimmed} })(); if (Array.isArray(__result)) return JSON.stringify(__result); if (typeof images !== 'undefined') return JSON.stringify(images); if (typeof pages !== 'undefined') return JSON.stringify(pages); return '[]'; } catch(e) { return '[]'; } })()`,
    `(function(){ try { ${trimmed}; if (typeof images !== 'undefined') return JSON.stringify(images); if (typeof pages !== 'undefined') return JSON.stringify(pages); return '[]'; } catch(e) { return '[]'; } })()`,
    trimmed,
  ];
}

/**
 * Evaluate obfuscated inline scripts from a chapter HTML page and return image URLs.
 */
export function decryptPageListFromScripts(html: string): string[] {
  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
    .map((match) => match[1]?.trim())
    .filter((script): script is string => !!script && script.length > 0);

  for (const script of scripts) {
    if (!OBFUSCATION_MARKERS.some((marker) => script.includes(marker))) continue;
    for (const candidate of buildCandidates(script)) {
      try {
        const result = evaluateScript(candidate);
        const urls = parseUrlList(result);
        if (urls.length > 0) return urls;
      } catch {
        // Try the next wrapper.
      }
    }
  }

  const fallback = html.match(URL_REGEX) ?? [];
  return [...new Set(fallback)];
}

/**
 * Convert decrypted URL strings into reader page objects.
 */
export function toPageUrls(
  urls: readonly string[],
  chapterUrl: UrlString,
  baseUrl: string,
): PageUrl[] {
  return urls.map((url, index) => ({
    index,
    url: absoluteUrl(baseUrl, url),
    headers: { Referer: chapterUrl },
  }));
}
