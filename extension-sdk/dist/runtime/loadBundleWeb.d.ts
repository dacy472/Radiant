import type { FetchLike } from '../repository/ExtensionRepository.js';
import type { Source } from '../source.js';
export interface WebLoadBundleOptions {
    readonly extensionId: string;
    readonly code: string;
    readonly fetchImpl: FetchLike;
    readonly expectedId?: string;
}
/**
 * Browser loader — uses a Blob URL + dynamic import() for ESM bundles.
 * Script bundles are rejected in the browser runtime (use Node host or ship ESM).
 */
export declare function loadBundleOnWeb(options: WebLoadBundleOptions): Promise<Source>;
//# sourceMappingURL=loadBundleWeb.d.ts.map