import { loadBundleOnWeb } from './loadBundleWeb.js';
import { serializeSourceResult } from './serialization.js';
const sourceCache = new Map();
async function loadSourceForExtension(payload) {
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
const workerScope = globalThis;
workerScope.onmessage = (event) => {
    void handleRun(event.data).then((result) => {
        workerScope.postMessage(result);
    });
};
//# sourceMappingURL=runSourceInWorkerWeb.js.map