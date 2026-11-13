import { ExtensionBundleError } from './errors.js';
/** Marker used by the untrusted host-filesystem fixture. Never execute that code. */
export const HOST_FS_MARKER = 'RADIANT_HOST_FS';
/** Publishers allowed to execute bundles on the Node dev server. Catalog ID is not enough. */
export const TRUSTED_PUBLISHERS = new Set(['radiant-official']);
export function hasHostFsMarker(code) {
    return code.includes(HOST_FS_MARKER) || /\bnode:fs\b/.test(code) || /require\s*\(\s*['"]fs['"]\s*\)/.test(code);
}
export function assertTrustedArtifact(input) {
    if (hasHostFsMarker(input.code)) {
        throw new ExtensionBundleError('untrusted', `Rejected host-fs marker fixture for ${input.extensionId}`);
    }
    const publisher = input.publisher?.trim() ?? '';
    if (!TRUSTED_PUBLISHERS.has(publisher)) {
        throw new ExtensionBundleError('untrusted', `Untrusted publisher for ${input.extensionId}`);
    }
    if (input.expectedSha256 && input.expectedSha256 !== input.actualSha256) {
        throw new ExtensionBundleError('integrity', `Tampered bundle hash mismatch for ${input.extensionId}`);
    }
}
//# sourceMappingURL=artifactTrust.js.map