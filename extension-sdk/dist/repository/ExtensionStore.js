import { EMPTY_EXTENSION_STORE_STATE, } from './types.js';
/**
 * Lightweight in-memory store for extension catalog state.
 * UI layers subscribe to receive updates when the available list changes.
 */
export class ExtensionStore {
    state = { ...EMPTY_EXTENSION_STORE_STATE };
    listeners = new Set();
    getState() {
        return this.state;
    }
    subscribe(listener) {
        this.listeners.add(listener);
        listener(this.state);
        return () => {
            this.listeners.delete(listener);
        };
    }
    emit() {
        const snapshot = this.getState();
        for (const listener of this.listeners) {
            listener(snapshot);
        }
    }
    patch(patch) {
        this.state = { ...this.state, ...patch };
        this.emit();
    }
    setLoading(isLoading) {
        this.patch({ isLoading, ...(isLoading ? { error: null } : {}) });
    }
    setError(error) {
        this.patch({ error, isLoading: false });
    }
    setInstalling(installingId) {
        this.patch({
            installingId,
            ...(installingId !== null ? { installError: null } : {}),
        });
    }
    setInstallError(installError) {
        this.patch({ installError, installingId: null });
    }
    addInstalled(extension) {
        const installed = [...this.state.installed.filter((e) => e.id !== extension.id), extension];
        this.patch({ installed, installingId: null, installError: null });
    }
    removeInstalled(extensionId) {
        const installed = this.state.installed.filter((ext) => ext.id !== extensionId);
        if (installed.length !== this.state.installed.length) {
            this.patch({ installed });
        }
    }
    setCatalog(available, meta) {
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
    markInstalled(installedIds) {
        const installed = new Set(installedIds);
        const available = this.state.available.filter((ext) => !installed.has(ext.id));
        if (available.length !== this.state.available.length) {
            this.patch({ available });
        }
    }
    syncInstalledFromRecords(records) {
        this.patch({ installed: [...records] });
        this.markInstalled(records.map((record) => record.id));
    }
    reset() {
        this.state = { ...EMPTY_EXTENSION_STORE_STATE };
        this.emit();
    }
}
//# sourceMappingURL=ExtensionStore.js.map