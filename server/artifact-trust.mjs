export const HOST_FS_MARKER = 'RADIANT_HOST_FS';
export const TRUSTED_PUBLISHERS = new Set(['radiant-official']);

export function hasHostFsMarker(code) {
  return (
    typeof code === 'string' &&
    (code.includes(HOST_FS_MARKER) ||
      /\bnode:fs\b/.test(code) ||
      /require\s*\(\s*['"]fs['"]\s*\)/.test(code))
  );
}

export function assertTrustedArtifact({
  extensionId,
  publisher,
  expectedSha256,
  actualSha256,
  code,
}) {
  if (hasHostFsMarker(code)) {
    throw new Error(`Rejected host-fs marker fixture for ${extensionId}`);
  }
  const name = typeof publisher === 'string' ? publisher.trim() : '';
  if (!TRUSTED_PUBLISHERS.has(name)) {
    throw new Error(`Untrusted publisher for ${extensionId}`);
  }
  if (expectedSha256 && expectedSha256 !== actualSha256) {
    throw new Error(`Tampered bundle hash mismatch for ${extensionId}`);
  }
}
