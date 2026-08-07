export type ExtensionBundleErrorCode =
  | 'download'
  | 'size_limit'
  | 'parse'
  | 'sandbox'
  | 'contract'
  | 'not_found'
  | 'already_installed';

/**
 * Raised when a remote bundle cannot be downloaded, parsed, sandboxed, or validated.
 */
export class ExtensionBundleError extends Error {
  readonly code: ExtensionBundleErrorCode;
  override readonly cause?: unknown;

  constructor(code: ExtensionBundleErrorCode, message: string, cause?: unknown) {
    super(message);
    this.name = 'ExtensionBundleError';
    this.code = code;
    if (cause !== undefined) {
      this.cause = cause;
    }
  }
}
