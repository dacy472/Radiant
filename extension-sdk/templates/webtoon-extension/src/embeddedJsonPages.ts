/**
 * Fallback chapter-page extractor for Next.js / Nuxt and other sites that embed
 * image URL lists inside `<script>` JSON blobs instead of bare `<img>` tags.
 *
 * Typical targets:
 *   - `<script id="__NEXT_DATA__" type="application/json">…</script>` (Flame Comics, etc.)
 *   - `<script id="__NUXT_DATA__" …>` (Nuxt 3)
 *   - `<script type="application/json">…</script>` hydration payloads
 */

const EMBEDDED_JSON_SCRIPT_IDS = new Set([
  '__NEXT_DATA__',
  '__NUXT_DATA__',
  '__NEXT_PAGE_PROPS__',
]);

const IMAGE_EXTENSION = /\.(?:jpe?g|png|webp|gif|bmp|avif)(?:\?|#|$)/i;
const IMAGE_PATH_HINT = /\/(?:uploads?|media|images?|img|cdn|chapter|pages?)\//i;

const SKIP_URL_FRAGMENTS = [
  'avatar',
  'logo',
  'favicon',
  'icon',
  'spinner',
  'loading',
  'placeholder',
  'banner-ad',
  'doubleclick',
];

/** True when [value] looks like a remote chapter page image URL. */
export function isLikelyImageUrl(value: string): boolean {
  const trimmed = value.trim();
  if (!trimmed.startsWith('http://') && !trimmed.startsWith('https://')) {
    return false;
  }
  const lower = trimmed.toLowerCase();
  if (SKIP_URL_FRAGMENTS.some((fragment) => lower.includes(fragment))) {
    return false;
  }
  return IMAGE_EXTENSION.test(trimmed) || IMAGE_PATH_HINT.test(trimmed);
}

/** True when every string in [arr] is a plausible page image URL. */
export function isImageUrlArray(arr: unknown[]): arr is string[] {
  if (arr.length === 0) return false;
  if (!arr.every((item) => typeof item === 'string')) return false;
  const urls = arr as string[];
  const hits = urls.filter(isLikelyImageUrl).length;
  return hits >= Math.max(1, Math.ceil(urls.length * 0.75));
}

/**
 * Walk a parsed JSON tree and collect every array that looks like a page list.
 */
export function findImageUrlArraysInJson(root: unknown, maxDepth = 40): string[][] {
  const results: string[][] = [];
  const seen = new WeakSet<object>();

  function walk(node: unknown, depth: number): void {
    if (depth > maxDepth || node == null) return;

    if (Array.isArray(node)) {
      if (isImageUrlArray(node)) {
        results.push([...node]);
      }
      for (const item of node) {
        walk(item, depth + 1);
      }
      return;
    }

    if (typeof node === 'object') {
      if (seen.has(node)) return;
      seen.add(node);
      for (const value of Object.values(node as Record<string, unknown>)) {
        walk(value, depth + 1);
      }
    }
  }

  walk(root, 0);
  return results;
}

/**
 * Parse `<script>` blocks that carry JSON payloads (__NEXT_DATA__, application/json, …).
 */
export function extractScriptJsonPayloads(html: string): unknown[] {
  const payloads: unknown[] = [];
  const seenRaw = new Set<string>();

  function tryParse(raw: string): void {
    const trimmed = raw.trim();
    if (!trimmed || seenRaw.has(trimmed)) return;
    seenRaw.add(trimmed);
    try {
      payloads.push(JSON.parse(trimmed));
    } catch {
      // Some sites append trailing semicolons or wrap JSON in JS — try a loose trim.
      const withoutTrailing = trimmed.replace(/;\s*$/, '');
      if (withoutTrailing !== trimmed && !seenRaw.has(withoutTrailing)) {
        tryParse(withoutTrailing);
      }
    }
  }

  const idScriptPattern =
    /<script\b([^>]*)\bid=["']([^"']+)["']([^>]*)>([\s\S]*?)<\/script>/gi;
  let match: RegExpExecArray | null;
  while ((match = idScriptPattern.exec(html)) !== null) {
    const id = match[2] ?? '';
    if (!EMBEDDED_JSON_SCRIPT_IDS.has(id) && !/next|nuxt|hydration/i.test(id)) {
      continue;
    }
    tryParse(match[4] ?? '');
  }

  const jsonTypePattern =
    /<script\b[^>]*type=["']application\/json["'][^>]*>([\s\S]*?)<\/script>/gi;
  while ((match = jsonTypePattern.exec(html)) !== null) {
    tryParse(match[1] ?? '');
  }

  const nuxtAssignPattern =
    /window\.__NUXT__\s*=\s*(\{[\s\S]*?\})\s*;?\s*(?:<\/script>|$)/i;
  const nuxtMatch = html.match(nuxtAssignPattern);
  if (nuxtMatch?.[1]) {
    tryParse(nuxtMatch[1]);
  }

  return payloads;
}

/** Prefer the longest high-confidence image URL array when multiple are found. */
export function pickBestImageUrlArray(candidates: string[][]): string[] | null {
  if (candidates.length === 0) return null;

  const ranked = [...candidates].sort((left, right) => {
    const lengthDiff = right.length - left.length;
    if (lengthDiff !== 0) return lengthDiff;
    const score = (urls: string[]) => urls.filter(isLikelyImageUrl).length;
    return score(right) - score(left);
  });

  const best = ranked[0];
  if (!best) return null;

  const deduped: string[] = [];
  const seen = new Set<string>();
  for (const url of best) {
    if (!isLikelyImageUrl(url) || seen.has(url)) continue;
    seen.add(url);
    deduped.push(url);
  }

  return deduped.length > 0 ? deduped : null;
}

/**
 * Extract ordered chapter page URLs from embedded JSON script blocks in [html].
 * Returns an empty array when no suitable list is found.
 */
export function extractImageUrlsFromEmbeddedJson(html: string): string[] {
  const payloads = extractScriptJsonPayloads(html);
  const arrays: string[][] = [];
  for (const payload of payloads) {
    arrays.push(...findImageUrlArraysInJson(payload));
  }
  return pickBestImageUrlArray(arrays) ?? [];
}
