import { Worker } from 'node:worker_threads';
import { fileURLToPath } from 'node:url';
import type { DownloadedBundle } from './downloadBundle.js';
import type { WorkerRunPayload, WorkerRunResult } from './runSourceInWorker.js';
import { SourceExecutionError } from './sourceExecutionErrors.js';

export interface ExtensionWorkerPoolOptions {
  readonly maxWorkers?: number;
  readonly timeoutMs?: number;
  readonly cacheDir?: string;
  readonly sandboxTimeoutMs?: number;
}

interface PendingJob {
  readonly payload: WorkerRunPayload;
  readonly resolve: (value: unknown) => void;
  readonly reject: (error: Error) => void;
  readonly timeoutId: ReturnType<typeof setTimeout>;
}

interface PooledWorker {
  readonly worker: Worker;
  busy: boolean;
  currentJobId?: string | undefined;
}

/**
 * Node.js worker_threads pool for extension scraping — limits concurrency and
 * isolates Cheerio/DOMParser work from the main event loop.
 */
export class ExtensionWorkerPool {
  private readonly maxWorkers: number;
  private readonly timeoutMs: number;
  private readonly cacheDir: string;
  private readonly sandboxTimeoutMs: number;
  private readonly workerScriptPath: string;
  private readonly workers: PooledWorker[] = [];
  private readonly pendingJobs: PendingJob[] = [];
  private readonly inflightByJobId = new Map<string, PendingJob>();

  constructor(options: ExtensionWorkerPoolOptions = {}) {
    this.maxWorkers = options.maxWorkers ?? 3;
    this.timeoutMs = options.timeoutMs ?? 30_000;
    this.cacheDir = options.cacheDir ?? '';
    this.sandboxTimeoutMs = options.sandboxTimeoutMs ?? 5_000;
    this.workerScriptPath = fileURLToPath(new URL('./runSourceInWorker.js', import.meta.url));
  }

  async run(
    extensionId: string,
    method: string,
    args: readonly unknown[],
    bundle: DownloadedBundle,
    expectedId?: string,
  ): Promise<unknown> {
    const jobId = `${extensionId}:${method}:${Date.now()}:${Math.random().toString(36).slice(2)}`;
    const payload: WorkerRunPayload = {
      jobId,
      extensionId,
      method,
      args,
      bundle,
      cacheDir: this.cacheDir,
      sandboxTimeoutMs: this.sandboxTimeoutMs,
      ...(expectedId !== undefined ? { expectedId } : {}),
    };

    return new Promise<unknown>((resolve, reject) => {
      const timeoutId = setTimeout(() => {
        this.failJob(jobId, new SourceExecutionError(
          'timeout',
          `Extension method "${method}" timed out after ${this.timeoutMs}ms`,
        ));
      }, this.timeoutMs);

      const job: PendingJob = { payload, resolve, reject, timeoutId };
      this.inflightByJobId.set(jobId, job);
      this.enqueue(job);
    });
  }

  async dispose(): Promise<void> {
    for (const job of this.inflightByJobId.values()) {
      clearTimeout(job.timeoutId);
      job.reject(new SourceExecutionError('worker', 'Worker pool disposed'));
    }
    this.inflightByJobId.clear();
    this.pendingJobs.length = 0;

    const workers = [...this.workers];
    this.workers.length = 0;
    await Promise.all(workers.map((entry) => entry.worker.terminate()));
  }

  private enqueue(job: PendingJob): void {
    const idle = this.workers.find((entry) => !entry.busy);
    if (idle) {
      this.dispatch(idle, job);
      return;
    }

    if (this.workers.length < this.maxWorkers) {
      const entry = this.createWorker();
      this.workers.push(entry);
      this.dispatch(entry, job);
      return;
    }

    this.pendingJobs.push(job);
  }

  private createWorker(): PooledWorker {
    const worker = new Worker(this.workerScriptPath);
    const entry: PooledWorker = { worker, busy: false };

    worker.on('message', (result: WorkerRunResult) => {
      const running = this.workers.find((entry) => entry.currentJobId === result.jobId);
      if (running) {
        running.busy = false;
        delete running.currentJobId;
        this.completeJob(result);
        this.pump(running);
        return;
      }
      this.completeJob(result);
    });

    worker.on('error', (error) => {
      if (entry.currentJobId) {
        this.failJob(
          entry.currentJobId,
          new SourceExecutionError('worker', 'Extension worker crashed', error),
        );
      }
      entry.busy = false;
      delete entry.currentJobId;
      this.removeWorker(entry);
    });

    worker.on('exit', (code) => {
      if (code !== 0 && entry.currentJobId) {
        this.failJob(
          entry.currentJobId,
          new SourceExecutionError('worker', `Extension worker exited with code ${code}`),
        );
      }
      this.removeWorker(entry);
    });

    return entry;
  }

  private removeWorker(entry: PooledWorker): void {
    const index = this.workers.indexOf(entry);
    if (index >= 0) {
      this.workers.splice(index, 1);
    }
  }

  private dispatch(entry: PooledWorker, job: PendingJob): void {
    entry.busy = true;
    entry.currentJobId = job.payload.jobId;
    entry.worker.postMessage(job.payload);
  }

  private pump(entry: PooledWorker): void {
    const next = this.pendingJobs.shift();
    if (next) {
      this.dispatch(entry, next);
    }
  }

  private completeJob(result: WorkerRunResult): void {
    const job = this.inflightByJobId.get(result.jobId);
    if (!job) {
      return;
    }

    clearTimeout(job.timeoutId);
    this.inflightByJobId.delete(result.jobId);

    if (result.success) {
      job.resolve(result.value);
      return;
    }

    job.reject(new SourceExecutionError('method', result.error));
  }

  private failJob(jobId: string, error: SourceExecutionError): void {
    const job = this.inflightByJobId.get(jobId);
    if (!job) {
      return;
    }

    clearTimeout(job.timeoutId);
    this.inflightByJobId.delete(jobId);

    const workerEntry = this.workers.find((entry) => entry.currentJobId === jobId);
    if (workerEntry) {
      workerEntry.busy = false;
      delete workerEntry.currentJobId;
      void workerEntry.worker.terminate();
      this.removeWorker(workerEntry);
    }

    job.reject(error);

    const index = this.pendingJobs.findIndex((pending) => pending.payload.jobId === jobId);
    if (index >= 0) {
      this.pendingJobs.splice(index, 1);
    }
  }
}
