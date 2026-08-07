export type SourceExecutionErrorCode = 'timeout' | 'worker' | 'method' | 'serialization' | 'not_supported';
/**
 * Raised when a Source method fails or times out inside a worker executor.
 */
export declare class SourceExecutionError extends Error {
    readonly code: SourceExecutionErrorCode;
    readonly cause?: unknown;
    constructor(code: SourceExecutionErrorCode, message: string, cause?: unknown);
}
//# sourceMappingURL=sourceExecutionErrors.d.ts.map