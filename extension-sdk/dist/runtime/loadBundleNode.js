import vm from 'node:vm';
import { hasHostFsMarker } from './artifactTrust.js';
import { detectBundleFormat } from './detectBundleFormat.js';
import { ExtensionBundleError } from './errors.js';
import { validateSourceInstance, resolveSourceExport } from './resolveSourceExport.js';
function createSandboxFetch(fetchImpl) {
    return ((input, init) => fetchImpl(String(input), init));
}
/**
 * Execute a non-ESM bundle inside a restricted vm context.
 * Only fetch/URL/TextEncoder/console are exposed to the guest code.
 */
function loadScriptBundle(options) {
    const moduleRef = { exports: {} };
    const exportsRef = moduleRef.exports;
    const sandbox = {
        module: moduleRef,
        exports: exportsRef,
        fetch: createSandboxFetch(options.fetchImpl),
        URL,
        TextEncoder,
        TextDecoder,
        console: {
            log: (...args) => console.log(`[ext:${options.extensionId}]`, ...args),
            warn: (...args) => console.warn(`[ext:${options.extensionId}]`, ...args),
            error: (...args) => console.error(`[ext:${options.extensionId}]`, ...args),
        },
    };
    vm.createContext(sandbox);
    const wrapped = `'use strict';\n${options.code}\n`;
    try {
        vm.runInContext(wrapped, sandbox, {
            filename: `${options.extensionId}.bundle.js`,
            timeout: options.sandboxTimeoutMs,
        });
    }
    catch (error) {
        throw new ExtensionBundleError('sandbox', `Bundle execution failed for ${options.extensionId}`, error);
    }
    return Promise.resolve(resolveSourceExport(moduleRef.exports)).then((resolved) => validateSourceInstance(resolved, options.expectedId));
}
export async function loadBundleOnNode(options) {
    if (hasHostFsMarker(options.code)) {
        throw new ExtensionBundleError('untrusted', `Rejected host-fs marker fixture for ${options.extensionId}`);
    }
    const format = detectBundleFormat(options.code);
    if (format === 'esm') {
        throw new ExtensionBundleError('sandbox', `Refusing host import() of ESM bundle for ${options.extensionId}`);
    }
    return loadScriptBundle(options);
}
//# sourceMappingURL=loadBundleNode.js.map