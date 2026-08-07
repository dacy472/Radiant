import { isMainThread, parentPort } from 'node:worker_threads';
import type { Source } from '../source.js';
import type { DownloadedBundle } from './downloadBundle.js';
import { loadBundleOnNode } from './loadBundleNode.js';
import { serializeSourceResult } from './serialization.js';

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

const sourceCache = new Map<string, Source>();

async function loadSourceForExtension(payload: WorkerRunPayload): Promise<Source> {
  const cached = sourceCache.get(payload.extensionId);
  if (cached) {
    return cached;
  }

  const fetchImpl = globalThis.fetch.bind(globalThis);
  const source = await loadBundleOnNode({
    extensionId: payload.extensionId,
    code: payload.bundle.code,
    fetchImpl,
    cacheDir: payload.cacheDir,
    sha256: payload.bundle.sha256,
    sandboxTimeoutMs: payload.sandboxTimeoutMs,
    ...(payload.expectedId !== undefined ? { expectedId: payload.expectedId } : {}),
  });

  sourceCache.set(payload.extensionId, source);
  return source;
}

async function handleRun(payload: WorkerRunPayload): Promise<WorkerRunResult> {
  try {
    const source = await loadSourceForExtension(payload);
    const candidate = source as unknown as Record<string, unknown>;
    const fn = candidate[payload.method];

    if (typeof fn !== 'function') {
      return {
        jobId: payload.jobId,
        success: false,
        error: `Method "${payload.method}" is not a function on the Source instance`,
      };
    }

    const value = await (fn as (...args: unknown[]) => Promise<unknown>).apply(source, [...payload.args]);
    return {
      jobId: payload.jobId,
      success: true,
      value: serializeSourceResult(value),
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      jobId: payload.jobId,
      success: false,
      error: message,
    };
  }
}

if (!isMainThread && parentPort) {
  parentPort.on('message', (payload: WorkerRunPayload) => {
    void handleRun(payload).then((result) => {
      parentPort!.postMessage(result);
    });
  });
}
