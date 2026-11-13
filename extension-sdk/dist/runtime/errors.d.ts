export type ExtensionBundleErrorCode = 'download' | 'size_limit' | 'parse' | 'sandbox' | 'contract' | 'not_found' | 'already_installed' | 'untrusted' | 'integrity';
/**
 * Raised when a remote bundle cannot be downloaded, parsed, sandboxed, or validated.
 */
export declare class ExtensionBundleError extends Error {
    readonly code: ExtensionBundleErrorCode;
    readonly cause?: unknown;
    constructor(code: ExtensionBundleErrorCode, message: string, cause?: unknown);
}
//# sourceMappingURL=errors.d.ts.map