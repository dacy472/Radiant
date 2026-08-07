import os from 'node:os';
import path from 'node:path';
import { downloadBundle } from './downloadBundle.js';
import { loadBundleOnNode } from './loadBundleNode.js';
import { loadBundleOnWeb } from './loadBundleWeb.js';
import { detectPlatform } from './platform.js';
/**
 * Downloads extension bundles and instantiates validated {@link Source} implementations.
 */
export class BundleRuntime {
    fetchImpl;
    cacheDir;
    maxBundleBytes;
    sandboxTimeoutMs;
    platform;
    constructor(options = {}) {
        this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
        this.cacheDir =
            options.cacheDir ??
                path.join(os.tmpdir(), 'radiant-extension-bundles');
        this.maxBundleBytes = options.maxBundleBytes ?? 5 * 1024 * 1024;
        this.sandboxTimeoutMs = options.sandboxTimeoutMs ?? 5_000;
        this.platform = detectPlatform(options.platform);
    }
    /** Download raw JavaScript from a bundle URL. */
    downloadBundle(bundleUrl) {
        return downloadBundle(bundleUrl, this.fetchImpl, this.maxBundleBytes);
    }
    /** Evaluate a previously downloaded bundle and return a Source instance. */
    async instantiateSource(bundle, extensionId, expectedId) {
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
    getCacheDir() {
        return this.cacheDir;
    }
    /** Convenience: download + instantiate in one call. */
    async loadSourceFromUrl(bundleUrl, extensionId, expectedId) {
        const bundle = await this.downloadBundle(bundleUrl);
        const source = await this.instantiateSource(bundle, extensionId, expectedId);
        return { bundle, source };
    }
}
//# sourceMappingURL=BundleRuntime.js.map