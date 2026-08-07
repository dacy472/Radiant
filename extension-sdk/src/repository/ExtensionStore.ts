import type { UrlString } from '../models.js';
import {
  EMPTY_EXTENSION_STORE_STATE,
  type AvailableExtension,
  type ExtensionStoreState,
  type InstalledExtension,
} from './types.js';

export type ExtensionStoreListener = (state: ExtensionStoreState) => void;

/**
 * Lightweight in-memory store for extension catalog state.
 * UI layers subscribe to receive updates when the available list changes.
 */
export class ExtensionStore {
  private state: ExtensionStoreState = { ...EMPTY_EXTENSION_STORE_STATE };
  private readonly listeners = new Set<ExtensionStoreListener>();

  getState(): ExtensionStoreState {
    return this.state;
  }

  subscribe(listener: ExtensionStoreListener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(): void {
    const snapshot = this.getState();
    for (const listener of this.listeners) {
      listener(snapshot);
    }
  }

  private patch(patch: Partial<ExtensionStoreState>): void {
    this.state = { ...this.state, ...patch };
    this.emit();
  }

  setLoading(isLoading: boolean): void {
    this.patch({ isLoading, ...(isLoading ? { error: null } : {}) });
  }

  setError(error: string): void {
    this.patch({ error, isLoading: false });
  }

  setInstalling(installingId: string | null): void {
    this.patch({
      installingId,
      ...(installingId !== null ? { installError: null } : {}),
    });
  }

  setInstallError(installError: string): void {
    this.patch({ installError, installingId: null });
  }

  addInstalled(extension: InstalledExtension): void {
    const installed = [...this.state.installed.filter((e) => e.id !== extension.id), extension];
    this.patch({ installed, installingId: null, installError: null });
  }

  removeInstalled(extensionId: string): void {
    const installed = this.state.installed.filter((ext) => ext.id !== extensionId);
    if (installed.length !== this.state.installed.length) {
      this.patch({ installed });
    }
  }

  setCatalog(
    available: readonly AvailableExtension[],
    meta: { repoUrl: UrlString; repoName: string; fetchedAt: number },
  ): void {
    this.patch({
      available,
      repoUrl: meta.repoUrl,
      repoName: meta.repoName,
      lastFetchedAt: meta.fetchedAt,
      isLoading: false,
      error: null,
    });
  }

  /** Remove installed extension IDs from the available list. */
  markInstalled(installedIds: Iterable<string>): void {
    const installed = new Set(installedIds);
    const available = this.state.available.filter((ext) => !installed.has(ext.id));
    if (available.length !== this.state.available.length) {
      this.patch({ available });
    }
  }

  syncInstalledFromRecords(records: readonly InstalledExtension[]): void {
    this.patch({ installed: [...records] });
    this.markInstalled(records.map((record) => record.id));
  }

  reset(): void {
    this.state = { ...EMPTY_EXTENSION_STORE_STATE };
    this.emit();
  }
}
