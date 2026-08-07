import type { DownloadedBundle } from './downloadBundle.js';
export interface WorkerRunPayload {
    readonly jobId: string;
    readonly extensionId: string;
    readonly method: string;
    readonly args: readonly unknown[];
    readonly bundle: DownloadedBundle;
    readonly cacheDir: string;
    readonly sandboxTimeoutMs: number;
    readonly expectedId?: string;
}
export interface WorkerRunSuccess {
    readonly jobId: string;
    readonly success: true;
    readonly value: unknown;
}
export interface WorkerRunFailure {
    readonly jobId: string;
    readonly success: false;
    readonly error: string;
}
export type WorkerRunResult = WorkerRunSuccess | WorkerRunFailure;
//# sourceMappingURL=runSourceInWorker.d.ts.map