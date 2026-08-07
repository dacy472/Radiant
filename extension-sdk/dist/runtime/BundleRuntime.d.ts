import type { FetchLike } from '../repository/ExtensionRepository.js';
import type { Source } from '../source.js';
import type { DownloadedBundle } from './downloadBundle.js';
import { type RuntimePlatform } from './platform.js';
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
export declare class BundleRuntime {
    private readonly fetchImpl;
    private readonly cacheDir;
    private readonly maxBundleBytes;
    private readonly sandboxTimeoutMs;
    private readonly platform;
    constructor(options?: BundleRuntimeOptions);
    /** Download raw JavaScript from a bundle URL. */
    downloadBundle(bundleUrl: string): Promise<DownloadedBundle>;
    /** Evaluate a previously downloaded bundle and return a Source instance. */
    instantiateSource(bundle: DownloadedBundle, extensionId: string, expectedId?: string): Promise<Source>;
    /** Resolved bundle cache directory used for ESM extension imports. */
    getCacheDir(): string;
    /** Convenience: download + instantiate in one call. */
    loadSourceFromUrl(bundleUrl: string, extensionId: string, expectedId?: string): Promise<{
        bundle: DownloadedBundle;
        source: Source;
    }>;
}
//# sourceMappingURL=BundleRuntime.d.ts.map