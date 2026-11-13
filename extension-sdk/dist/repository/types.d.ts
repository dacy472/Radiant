import type { UrlString } from '../models.js';
/**
 * Single extension entry as published in a remote `index.json` repository.
 */
export interface RemoteExtensionEntry {
    readonly id: string;
    readonly name: string;
    readonly version: string;
    readonly iconUrl: UrlString;
    readonly bundleUrl: UrlString;
    readonly lang?: string;
    readonly description?: string;
    readonly nsfw?: boolean;
    readonly publisher?: string;
    readonly sha256?: string;
}
/**
 * Parsed repository index document.
 */
export interface ExtensionIndex {
    readonly name: string;
    readonly version: string | null;
    readonly extensions: readonly RemoteExtensionEntry[];
}
/**
 * Extension enriched with repository metadata after a successful fetch.
 */
export interface AvailableExtension extends RemoteExtensionEntry {
    readonly repoUrl: UrlString;
    readonly fetchedAt: number;
}
/**
 * Observable state consumed by UI layers (Compose, React, server JSON, etc.).
 */
export interface ExtensionStoreState {
    /** Extensions from the last successful index fetch, minus installed IDs. */
    readonly available: readonly AvailableExtension[];
    /** Successfully loaded JS bundles on this host. */
    readonly installed: readonly InstalledExtension[];
    readonly isLoading: boolean;
    readonly installingId: string | null;
    readonly error: string | null;
    readonly installError: string | null;
    readonly repoUrl: UrlString | null;
    readonly repoName: string | null;
    readonly lastFetchedAt: number | null;
}
/** A downloaded, validated, and instantiated JS extension bundle. */
export interface InstalledExtension {
    readonly id: string;
    readonly name: string;
    readonly version: string;
    readonly iconUrl: UrlString;
    readonly bundleUrl: UrlString;
    readonly bundleHash: string;
    readonly installedAt: number;
}
export interface InstallExtensionResult {
    readonly extension: InstalledExtension;
    readonly sourceId: string;
}
export declare const EMPTY_EXTENSION_STORE_STATE: ExtensionStoreState;
//# sourceMappingURL=types.d.ts.map