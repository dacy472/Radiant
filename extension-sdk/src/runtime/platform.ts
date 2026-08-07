export type RuntimePlatform = 'node' | 'web' | 'auto';

/** Detect Node.js vs browser runtime (mirrors {@link BundleRuntime}). */
export function detectPlatform(explicit?: RuntimePlatform): 'node' | 'web' {
  if (explicit && explicit !== 'auto') {
    return explicit;
  }
  const proc = (globalThis as { process?: { versions?: { node?: string } } }).process;
  return proc?.versions?.node ? 'node' : 'web';
}
