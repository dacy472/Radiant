/** Detect Node.js vs browser runtime (mirrors {@link BundleRuntime}). */
export function detectPlatform(explicit) {
    if (explicit && explicit !== 'auto') {
        return explicit;
    }
    const proc = globalThis.process;
    return proc?.versions?.node ? 'node' : 'web';
}
//# sourceMappingURL=platform.js.map