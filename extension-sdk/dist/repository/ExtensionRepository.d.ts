import type { Source } from '../source.js';
import { BundleRuntime } from '../runtime/BundleRuntime.js';
import { SourceExecutor } from '../runtime/SourceExecutor.js';
import { ExtensionStore } from './ExtensionStore.js';
import type { AvailableExtension, ExtensionStoreState, InstallExtensionResult, InstalledExtension } from './types.js';
/** Curated extension IDs — only exact matches are loaded from any remote index. */
export declare const VERIFIED_EXTENSIONS: readonly ["en.mock", "en.picsum", "en.mangadex"];
export type FetchLike = (input: string, init?: RequestInit) => Promise<{
    ok: boolean;
    status: number;
    json(): Promise<unknown>;
    text(): Promise<string>;
}>;
export interface ExtensionRepositoryOptions {
    readonly fetch?: FetchLike;
    readonly runtime?: BundleRuntime;
    readonly bundleCacheDir?: string;
    readonly executor?: SourceExecutor;
    readonly workerConcurrency?: number;
    readonly workerTimeoutMs?: number;
}
/**
 * Fetches remote extension repository indexes, installs JS bundles, and runs
 * validated {@link Source} instances.
 */
export declare class ExtensionRepository {
    private readonly store;
    private readonly fetchImpl;
    private readonly runtime;
    private readonly executor;
    private readonly activeExtensions;
    constructor(store: ExtensionStore, options?: ExtensionRepositoryOptions);
    /** Current snapshot — safe for synchronous UI rendering. */
    getState(): ExtensionStoreState;
    /** Subscribe to catalog + install updates. */
    subscribe(listener: (state: ExtensionStoreState) => void): () => void;
    /** Extensions ready to display in the "Available" section. */
    getAvailableExtensions(): readonly AvailableExtension[];
    /** Installed extension metadata (without live Source instances). */
    getInstalledExtensions(): readonly InstalledExtension[];
    /** Live Source instance for an installed extension, if loaded. */
    getSource(extensionId: string): Source | undefined;
    /**
     * Run a Source method in a background worker (scraping / network I/O).
     * Prefer this over calling {@link getSource} methods directly for HTML parsing work.
     */
    runSourceMethod(extensionId: string, method: string, args?: readonly unknown[]): Promise<unknown>;
    /**
     * Download and parse `index.json` from a remote repository URL.
     * Updates store state on success or failure.
     */
    fetchIndex(repoUrl: string): Promise<readonly AvailableExtension[]>;
    /**
     * Download a bundle, evaluate it in a sandbox, validate the Source contract,
     * and register the extension for runtime use.
     */
    installExtension(extensionId: string): Promise<InstallExtensionResult>;
    /**
     * Remove an installed extension and evict its in-memory Source instance.
     */
    uninstallExtension(extensionId: string): void;
    clearInstalled(): void;
    private getInstalledIds;
    private findInstalledCatalogEntry;
    private toAvailableExtensions;
}
/** Append `index.json` when the URL points at a repo root or directory. */
export declare function normalizeRepoUrl(repoUrl: string): string;
/** Shared singleton used by the Radiant backend server. */
export declare const defaultExtensionStore: ExtensionStore;
export declare const defaultExtensionRepository: ExtensionRepository;
//# sourceMappingURL=ExtensionRepository.d.ts.map