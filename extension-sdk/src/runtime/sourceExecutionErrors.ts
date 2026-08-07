export type SourceExecutionErrorCode = 'timeout' | 'worker' | 'method' | 'serialization' | 'not_supported';

/**
 * Raised when a Source method fails or times out inside a worker executor.
 */
export class SourceExecutionError extends Error {
  readonly code: SourceExecutionErrorCode;
  override readonly cause?: unknown;

  constructor(code: SourceExecutionErrorCode, message: string, cause?: unknown) {
    super(message);
    this.name = 'SourceExecutionError';
    this.code = code;
    if (cause !== undefined) {
      this.cause = cause;
    }
  }
}
