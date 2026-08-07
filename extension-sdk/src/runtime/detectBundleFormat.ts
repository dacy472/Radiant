export type BundleFormat = 'esm' | 'script';

/**
 * Heuristic detection for ESM vs. plain script/CJS bundles.
 */
export function detectBundleFormat(code: string): BundleFormat {
  const trimmed = code.trim();
  if (
    /\bexport\s+default\b/.test(trimmed) ||
    /\bexport\s+(class|function|const|let|var)\b/.test(trimmed) ||
    /\bexport\s*\{/.test(trimmed)
  ) {
    return 'esm';
  }
  return 'script';
}
