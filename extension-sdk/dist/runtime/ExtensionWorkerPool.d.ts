import type { DownloadedBundle } from './downloadBundle.js';
export interface ExtensionWorkerPoolOptions {
    readonly maxWorkers?: number;
    readonly timeoutMs?: number;
    readonly cacheDir?: string;
    readonly sandboxTimeoutMs?: number;
}
/**
 * Node.js worker_threads pool for extension scraping — limits concurrency and
 * isolates Cheerio/DOMParser work from the main event loop.
 */
export declare class ExtensionWorkerPool {
    private readonly maxWorkers;
    private readonly timeoutMs;
    private readonly cacheDir;
    private readonly sandboxTimeoutMs;
    private readonly workerScriptPath;
    private readonly workers;
    private readonly pendingJobs;
    private readonly inflightByJobId;
    constructor(options?: ExtensionWorkerPoolOptions);
    run(extensionId: string, method: string, args: readonly unknown[], bundle: DownloadedBundle, expectedId?: string): Promise<unknown>;
    dispose(): Promise<void>;
    private enqueue;
    private createWorker;
    private removeWorker;
    private dispatch;
    private pump;
    private completeJob;
    private failJob;
}
//# sourceMappingURL=ExtensionWorkerPool.d.ts.map