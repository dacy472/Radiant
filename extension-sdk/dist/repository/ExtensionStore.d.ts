import type { UrlString } from '../models.js';
import { type AvailableExtension, type ExtensionStoreState, type InstalledExtension } from './types.js';
export type ExtensionStoreListener = (state: ExtensionStoreState) => void;
/**
 * Lightweight in-memory store for extension catalog state.
 * UI layers subscribe to receive updates when the available list changes.
 */
export declare class ExtensionStore {
    private state;
    private readonly listeners;
    getState(): ExtensionStoreState;
    subscribe(listener: ExtensionStoreListener): () => void;
    private emit;
    private patch;
    setLoading(isLoading: boolean): void;
    setError(error: string): void;
    setInstalling(installingId: string | null): void;
    setInstallError(installError: string): void;
    addInstalled(extension: InstalledExtension): void;
    removeInstalled(extensionId: string): void;
    setCatalog(available: readonly AvailableExtension[], meta: {
        repoUrl: UrlString;
        repoName: string;
        fetchedAt: number;
    }): void;
    /** Remove installed extension IDs from the available list. */
    markInstalled(installedIds: Iterable<string>): void;
    syncInstalledFromRecords(records: readonly InstalledExtension[]): void;
    reset(): void;
}
//# sourceMappingURL=ExtensionStore.d.ts.map