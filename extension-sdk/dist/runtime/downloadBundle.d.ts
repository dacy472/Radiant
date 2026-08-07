import type { FetchLike } from '../repository/ExtensionRepository.js';
import type { UrlString } from '../models.js';
export interface DownloadedBundle {
    readonly code: string;
    readonly bundleUrl: UrlString;
    readonly contentType: string | null;
    readonly byteLength: number;
    readonly sha256: string;
}
/**
 * Download raw JavaScript source from a remote bundle URL.
 */
export declare function downloadBundle(bundleUrl: string, fetchImpl: FetchLike, maxBytes?: number): Promise<DownloadedBundle>;
//# sourceMappingURL=downloadBundle.d.ts.map