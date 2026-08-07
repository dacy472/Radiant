/**
 * Raised when a Source method fails or times out inside a worker executor.
 */
export class SourceExecutionError extends Error {
    code;
    cause;
    constructor(code, message, cause) {
        super(message);
        this.name = 'SourceExecutionError';
        this.code = code;
        if (cause !== undefined) {
            this.cause = cause;
        }
    }
}
//# sourceMappingURL=sourceExecutionErrors.js.map