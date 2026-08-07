import type { DownloadedBundle } from './downloadBundle.js';
import { ExtensionWorkerPool } from './ExtensionWorkerPool.js';
import type { RuntimePlatform } from './platform.js';
import { detectPlatform } from './platform.js';
import { serializeSourceResult } from './serialization.js';
import { SourceExecutionError } from './sourceExecutionErrors.js';

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
export class SourceExecutor {
  private readonly platform: 'node' | 'web';
  private readonly nodePool: ExtensionWorkerPool | undefined;
  private readonly cacheDir: string;
  private readonly sandboxTimeoutMs: number;
  private readonly timeoutMs: number;

  constructor(options: SourceExecutorOptions = {}) {
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
  async runMethod(options: SourceExecutorRunOptions): Promise<unknown> {
    const args = options.args ?? [];

    if (this.platform === 'node') {
      if (!this.nodePool) {
        throw new SourceExecutionError('not_supported', 'Node worker pool is not initialized');
      }
      return this.nodePool.run(
        options.extensionId,
        options.method,
        args,
        options.bundle,
        options.expectedId,
      );
    }

    return this.runInWebWorker(options, args);
  }

  async dispose(): Promise<void> {
    if (this.nodePool) {
      await this.nodePool.dispose();
    }
  }

  private async runInWebWorker(
    options: SourceExecutorRunOptions,
    args: readonly unknown[],
  ): Promise<unknown> {
    if (typeof Worker === 'undefined') {
      throw new SourceExecutionError(
        'not_supported',
        'Web Workers are not available in this host environment',
      );
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

    return new Promise<unknown>((resolve, reject) => {
      const timeoutId = setTimeout(() => {
        worker.terminate();
        reject(
          new SourceExecutionError(
            'timeout',
            `Extension method "${options.method}" timed out after ${this.timeoutMs}ms`,
          ),
        );
      }, this.timeoutMs);

      worker.onmessage = (event: MessageEvent<{ jobId: string; success: boolean; value?: unknown; error?: string }>) => {
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

      worker.onerror = (event: ErrorEvent) => {
        clearTimeout(timeoutId);
        worker.terminate();
        reject(new SourceExecutionError('worker', event.message || 'Web Worker error', event.error));
      };

      worker.postMessage(payload);
    });
  }
}
