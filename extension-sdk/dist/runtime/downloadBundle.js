import { ExtensionBundleError } from './errors.js';
const DEFAULT_MAX_BYTES = 5 * 1024 * 1024; // 5 MiB
const BUNDLE_HEADERS = {
    Accept: 'application/javascript, text/javascript, application/ecmascript, text/plain, */*',
    'User-Agent': 'RadiantExtensionRuntime/1.0',
};
function assertHttpUrl(bundleUrl) {
    try {
        const parsed = new URL(bundleUrl);
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
            throw new Error('unsupported protocol');
        }
        return parsed.toString();
    }
    catch {
        throw new ExtensionBundleError('download', `Invalid bundleUrl: ${bundleUrl}`);
    }
}
async function sha256Hex(data) {
    if (globalThis.crypto?.subtle) {
        const encoded = new TextEncoder().encode(data);
        const digest = await globalThis.crypto.subtle.digest('SHA-256', encoded);
        return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
    }
    const { createHash } = await import('node:crypto');
    return createHash('sha256').update(data).digest('hex');
}
/**
 * Download raw JavaScript source from a remote bundle URL.
 */
export async function downloadBundle(bundleUrl, fetchImpl, maxBytes = DEFAULT_MAX_BYTES) {
    const normalizedUrl = assertHttpUrl(bundleUrl);
    let response;
    try {
        response = await fetchImpl(normalizedUrl, { headers: { ...BUNDLE_HEADERS } });
    }
    catch (error) {
        throw new ExtensionBundleError('download', `Network error while downloading bundle from ${normalizedUrl}`, error);
    }
    if (!response.ok) {
        throw new ExtensionBundleError('download', `HTTP ${response.status} while downloading bundle from ${normalizedUrl}`);
    }
    const code = await response.text();
    const byteLength = new TextEncoder().encode(code).byteLength;
    if (byteLength === 0) {
        throw new ExtensionBundleError('download', 'Bundle response was empty');
    }
    if (byteLength > maxBytes) {
        throw new ExtensionBundleError('size_limit', `Bundle exceeds maximum size of ${maxBytes} bytes (got ${byteLength})`);
    }
    const contentType = typeof response.headers?.get === 'function'
        ? response.headers.get('content-type')
        : null;
    return {
        code,
        bundleUrl: normalizedUrl,
        contentType,
        byteLength,
        sha256: await sha256Hex(code),
    };
}
//# sourceMappingURL=downloadBundle.js.map