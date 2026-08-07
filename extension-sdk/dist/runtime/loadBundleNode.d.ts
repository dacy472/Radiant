import type { FetchLike } from '../repository/ExtensionRepository.js';
import type { Source } from '../source.js';
export interface NodeLoadBundleOptions {
    readonly extensionId: string;
    readonly code: string;
    readonly fetchImpl: FetchLike;
    readonly cacheDir: string;
    readonly sha256: string;
    readonly sandboxTimeoutMs: number;
    readonly expectedId?: string;
}
export declare function loadBundleOnNode(options: NodeLoadBundleOptions): Promise<Source>;
//# sourceMappingURL=loadBundleNode.d.ts.map