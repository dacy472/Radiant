import type { DownloadedBundle } from './downloadBundle.js';
export interface WebWorkerRunPayload {
    readonly jobId: string;
    readonly extensionId: string;
    readonly method: string;
    readonly args: readonly unknown[];
    readonly bundle: DownloadedBundle;
    readonly expectedId?: string;
}
export interface WebWorkerRunSuccess {
    readonly jobId: string;
    readonly success: true;
    readonly value: unknown;
}
export interface WebWorkerRunFailure {
    readonly jobId: string;
    readonly success: false;
    readonly error: string;
}
export type WebWorkerRunResult = WebWorkerRunSuccess | WebWorkerRunFailure;
//# sourceMappingURL=runSourceInWorkerWeb.d.ts.map