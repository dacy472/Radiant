import os from 'node:os';
import path from 'node:path';
import type { FetchLike } from '../repository/ExtensionRepository.js';
import type { Source } from '../source.js';
import type { DownloadedBundle } from './downloadBundle.js';
import { downloadBundle } from './downloadBundle.js';
import { loadBundleOnNode } from './loadBundleNode.js';
import { loadBundleOnWeb } from './loadBundleWeb.js';
import { detectPlatform, type RuntimePlatform } from './platform.js';

export type { RuntimePlatform } from './platform.js';

export interface BundleRuntimeOptions {
  readonly fetch?: FetchLike;
  readonly cacheDir?: string;
  readonly maxBundleBytes?: number;
  readonly sandboxTimeoutMs?: number;
  readonly platform?: RuntimePlatform;
}

/**
 * Downloads extension bundles and instantiates validated {@link Source} implementations.
 */
export class BundleRuntime {
  private readonly fetchImpl: FetchLike;
  private readonly cacheDir: string;
  private readonly maxBundleBytes: number;
  private readonly sandboxTimeoutMs: number;
  private readonly platform: 'node' | 'web';

  constructor(options: BundleRuntimeOptions = {}) {
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.cacheDir =
      options.cacheDir ??
      path.join(os.tmpdir(), 'radiant-extension-bundles');
    this.maxBundleBytes = options.maxBundleBytes ?? 5 * 1024 * 1024;
    this.sandboxTimeoutMs = options.sandboxTimeoutMs ?? 5_000;
    this.platform = detectPlatform(options.platform);
  }

  /** Download raw JavaScript from a bundle URL. */
  downloadBundle(bundleUrl: string): Promise<DownloadedBundle> {
    return downloadBundle(bundleUrl, this.fetchImpl, this.maxBundleBytes);
  }

  /** Evaluate a previously downloaded bundle and return a Source instance. */
  async instantiateSource(
    bundle: DownloadedBundle,
    extensionId: string,
    expectedId?: string,
  ): Promise<Source> {
    if (this.platform === 'node') {
      return loadBundleOnNode({
        extensionId,
        code: bundle.code,
        fetchImpl: this.fetchImpl,
        cacheDir: this.cacheDir,
        sha256: bundle.sha256,
        sandboxTimeoutMs: this.sandboxTimeoutMs,
        expectedId: expectedId ?? extensionId,
      });
    }

    return loadBundleOnWeb({
      extensionId,
      code: bundle.code,
      fetchImpl: this.fetchImpl,
      expectedId: expectedId ?? extensionId,
    });
  }

  /** Resolved bundle cache directory used for ESM extension imports. */
  getCacheDir(): string {
    return this.cacheDir;
  }

  /** Convenience: download + instantiate in one call. */
  async loadSourceFromUrl(
    bundleUrl: string,
    extensionId: string,
    expectedId?: string,
  ): Promise<{ bundle: DownloadedBundle; source: Source }> {
    const bundle = await this.downloadBundle(bundleUrl);
    const source = await this.instantiateSource(bundle, extensionId, expectedId);
    return { bundle, source };
  }
}
