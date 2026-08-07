/**
 * Raised when a remote bundle cannot be downloaded, parsed, sandboxed, or validated.
 */
export class ExtensionBundleError extends Error {
    code;
    cause;
    constructor(code, message, cause) {
        super(message);
        this.name = 'ExtensionBundleError';
        this.code = code;
        if (cause !== undefined) {
            this.cause = cause;
        }
    }
}
//# sourceMappingURL=errors.js.map