import type { DownloadedBundle } from './downloadBundle.js';
import type { RuntimePlatform } from './platform.js';
export interface SourceExecutorRunOptions {
    readonly extensionId: string;
    readonly method: string;
    readonly args?: readonly unknown[];
    readonly bundle: DownloadedBundle;
    readonly expectedId?: string;
}
export interface SourceExecutorOptions {
    readonly platform?: RuntimePlatform;
    readonly maxWorkers?: number;
    readonly timeoutMs?: number;
    readonly cacheDir?: string;
    readonly sandboxTimeoutMs?: number;
}
/**
 * Unified API for running Source scraping methods off the main thread.
 * Node hosts use worker_threads; browser hosts use Web Workers when available.
 */
export declare class SourceExecutor {
    private readonly platform;
    private readonly nodePool;
    private readonly cacheDir;
    private readonly sandboxTimeoutMs;
    private readonly timeoutMs;
    constructor(options?: SourceExecutorOptions);
    /** Execute a Source method in a background worker and return a serialized result. */
    runMethod(options: SourceExecutorRunOptions): Promise<unknown>;
    dispose(): Promise<void>;
    private runInWebWorker;
}
//# sourceMappingURL=SourceExecutor.d.ts.map