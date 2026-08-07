import { isMainThread, parentPort } from 'node:worker_threads';
import { loadBundleOnNode } from './loadBundleNode.js';
import { serializeSourceResult } from './serialization.js';
const sourceCache = new Map();
async function loadSourceForExtension(payload) {
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
async function handleRun(payload) {
    try {
        const source = await loadSourceForExtension(payload);
        const candidate = source;
        const fn = candidate[payload.method];
        if (typeof fn !== 'function') {
            return {
                jobId: payload.jobId,
                success: false,
                error: `Method "${payload.method}" is not a function on the Source instance`,
            };
        }
        const value = await fn.apply(source, [...payload.args]);
        return {
            jobId: payload.jobId,
            success: true,
            value: serializeSourceResult(value),
        };
    }
    catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return {
            jobId: payload.jobId,
            success: false,
            error: message,
        };
    }
}
if (!isMainThread && parentPort) {
    parentPort.on('message', (payload) => {
        void handleRun(payload).then((result) => {
            parentPort.postMessage(result);
        });
    });
}
//# sourceMappingURL=runSourceInWorker.js.map