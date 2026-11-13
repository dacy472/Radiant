/** Marker used by the untrusted host-filesystem fixture. Never execute that code. */
export declare const HOST_FS_MARKER = "RADIANT_HOST_FS";
/** Publishers allowed to execute bundles on the Node dev server. Catalog ID is not enough. */
export declare const TRUSTED_PUBLISHERS: Set<string>;
export interface ArtifactTrustInput {
    readonly extensionId: string;
    readonly publisher?: string;
    readonly expectedSha256?: string;
    readonly actualSha256: string;
    readonly code: string;
}
export declare function hasHostFsMarker(code: string): boolean;
export declare function assertTrustedArtifact(input: ArtifactTrustInput): void;
//# sourceMappingURL=artifactTrust.d.ts.map