import { ExtensionWorkerPool } from './ExtensionWorkerPool.js';
import { detectPlatform } from './platform.js';
import { serializeSourceResult } from './serialization.js';
import { SourceExecutionError } from './sourceExecutionErrors.js';
/**
 * Unified API for running Source scraping methods off the main thread.
 * Node hosts use worker_threads; browser hosts use Web Workers when available.
 */
export class SourceExecutor {
    platform;
    nodePool;
    cacheDir;
    sandboxTimeoutMs;
    timeoutMs;
    constructor(options = {}) {
        this.platform = detectPlatform(options.platform);
        this.cacheDir = options.cacheDir ?? '';
        this.sandboxTimeoutMs = options.sandboxTimeoutMs ?? 5_000;
        this.timeoutMs = options.timeoutMs ?? 30_000;
        if (this.platform === 'node') {
            this.nodePool = new ExtensionWorkerPool({
                ...(options.maxWorkers !== undefined ? { maxWorkers: options.maxWorkers } : {}),
                timeoutMs: this.timeoutMs,
                cacheDir: this.cacheDir,
                sandboxTimeoutMs: this.sandboxTimeoutMs,
            });
        }
    }
    /** Execute a Source method in a background worker and return a serialized result. */
    async runMethod(options) {
        const args = options.args ?? [];
        if (this.platform === 'node') {
            if (!this.nodePool) {
                throw new SourceExecutionError('not_supported', 'Node worker pool is not initialized');
            }
            return this.nodePool.run(options.extensionId, options.method, args, options.bundle, options.expectedId);
        }
        return this.runInWebWorker(options, args);
    }
    async dispose() {
        if (this.nodePool) {
            await this.nodePool.dispose();
        }
    }
    async runInWebWorker(options, args) {
        if (typeof Worker === 'undefined') {
            throw new SourceExecutionError('not_supported', 'Web Workers are not available in this host environment');
        }
        const workerUrl = new URL('./runSourceInWorkerWeb.js', import.meta.url);
        const worker = new Worker(workerUrl, { type: 'module' });
        const jobId = `${options.extensionId}:${options.method}:${Date.now()}`;
        const payload = {
            jobId,
            extensionId: options.extensionId,
            method: options.method,
            args,
            bundle: options.bundle,
            cacheDir: this.cacheDir,
            sandboxTimeoutMs: this.sandboxTimeoutMs,
            ...(options.expectedId !== undefined ? { expectedId: options.expectedId } : {}),
        };
        return new Promise((resolve, reject) => {
            const timeoutId = setTimeout(() => {
                worker.terminate();
                reject(new SourceExecutionError('timeout', `Extension method "${options.method}" timed out after ${this.timeoutMs}ms`));
            }, this.timeoutMs);
            worker.onmessage = (event) => {
                const result = event.data;
                if (result.jobId !== jobId) {
                    return;
                }
                clearTimeout(timeoutId);
                worker.terminate();
                if (result.success) {
                    resolve(serializeSourceResult(result.value));
                    return;
                }
                reject(new SourceExecutionError('method', result.error ?? 'Source method failed'));
            };
            worker.onerror = (event) => {
                clearTimeout(timeoutId);
                worker.terminate();
                reject(new SourceExecutionError('worker', event.message || 'Web Worker error', event.error));
            };
            worker.postMessage(payload);
        });
    }
}
//# sourceMappingURL=SourceExecutor.js.map