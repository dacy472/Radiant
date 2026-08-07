import type { Source } from '../source.js';
import type { DownloadedBundle } from './downloadBundle.js';
import { loadBundleOnWeb } from './loadBundleWeb.js';
import { serializeSourceResult } from './serialization.js';

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

const sourceCache = new Map<string, Source>();

async function loadSourceForExtension(payload: WebWorkerRunPayload): Promise<Source> {
  const cached = sourceCache.get(payload.extensionId);
  if (cached) {
    return cached;
  }

  const fetchImpl = globalThis.fetch.bind(globalThis);
  const source = await loadBundleOnWeb({
    extensionId: payload.extensionId,
    code: payload.bundle.code,
    fetchImpl,
    ...(payload.expectedId !== undefined ? { expectedId: payload.expectedId } : {}),
  });

  sourceCache.set(payload.extensionId, source);
  return source;
}

async function handleRun(payload: WebWorkerRunPayload): Promise<WebWorkerRunResult> {
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

const workerScope = globalThis as typeof globalThis & {
  onmessage?: (event: MessageEvent<WebWorkerRunPayload>) => void;
  postMessage: (message: WebWorkerRunResult) => void;
};

workerScope.onmessage = (event: MessageEvent<WebWorkerRunPayload>) => {
  void handleRun(event.data).then((result) => {
    workerScope.postMessage(result);
  });
};
