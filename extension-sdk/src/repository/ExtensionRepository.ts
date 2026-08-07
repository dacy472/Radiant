import type { UrlString } from '../models.js';
import type { Source } from '../source.js';
import { BundleRuntime } from '../runtime/BundleRuntime.js';
import type { DownloadedBundle } from '../runtime/downloadBundle.js';
import { ExtensionBundleError } from '../runtime/errors.js';
import { isScrapingSourceMethod } from '../runtime/scrapingMethods.js';
import { SourceExecutor } from '../runtime/SourceExecutor.js';
import { SourceExecutionError } from '../runtime/sourceExecutionErrors.js';
import { isEnglishExtension, parseExtensionIndex } from './parseExtensionIndex.js';
import { ExtensionStore } from './ExtensionStore.js';
import type {
  AvailableExtension,
  ExtensionIndex,
  ExtensionStoreState,
  InstallExtensionResult,
  InstalledExtension,
  RemoteExtensionEntry,
} from './types.js';

/** Curated extension IDs — only exact matches are loaded from any remote index. */
export const VERIFIED_EXTENSIONS = ['en.mock', 'en.picsum', 'en.mangadex'] as const;

const VERIFIED_EXTENSION_IDS = new Set<string>(VERIFIED_EXTENSIONS);

function isVerifiedExtension(id: string): boolean {
  return VERIFIED_EXTENSION_IDS.has(id);
}

export type FetchLike = (
  input: string,
  init?: RequestInit,
) => Promise<{ ok: boolean; status: number; json(): Promise<unknown>; text(): Promise<string> }>;

export interface ExtensionRepositoryOptions {
  readonly fetch?: FetchLike;
  readonly runtime?: BundleRuntime;
  readonly bundleCacheDir?: string;
  readonly executor?: SourceExecutor;
  readonly workerConcurrency?: number;
  readonly workerTimeoutMs?: number;
}

const DEFAULT_HEADERS: Readonly<Record<string, string>> = {
  Accept: 'application/json',
  'User-Agent': 'RadiantExtensionRepository/1.0',
};

interface ActiveExtension {
  readonly record: InstalledExtension;
  readonly source: Source;
  readonly bundle: DownloadedBundle;
}

/**
 * Fetches remote extension repository indexes, installs JS bundles, and runs
 * validated {@link Source} instances.
 */
export class ExtensionRepository {
  private readonly fetchImpl: FetchLike;
  private readonly runtime: BundleRuntime;
  private readonly executor: SourceExecutor;
  private readonly activeExtensions = new Map<string, ActiveExtension>();

  constructor(
    private readonly store: ExtensionStore,
    options: ExtensionRepositoryOptions = {},
  ) {
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.runtime =
      options.runtime ??
      new BundleRuntime({
        fetch: this.fetchImpl,
        ...(options.bundleCacheDir !== undefined ? { cacheDir: options.bundleCacheDir } : {}),
        platform: 'node',
      });
    this.executor =
      options.executor ??
      new SourceExecutor({
        cacheDir: this.runtime.getCacheDir(),
        ...(options.workerConcurrency !== undefined
          ? { maxWorkers: options.workerConcurrency }
          : {}),
        ...(options.workerTimeoutMs !== undefined ? { timeoutMs: options.workerTimeoutMs } : {}),
        platform: 'node',
      });
  }

  /** Current snapshot — safe for synchronous UI rendering. */
  getState(): ExtensionStoreState {
    return this.store.getState();
  }

  /** Subscribe to catalog + install updates. */
  subscribe(listener: (state: ExtensionStoreState) => void): () => void {
    return this.store.subscribe(listener);
  }

  /** Extensions ready to display in the "Available" section. */
  getAvailableExtensions(): readonly AvailableExtension[] {
    return this.store.getState().available;
  }

  /** Installed extension metadata (without live Source instances). */
  getInstalledExtensions(): readonly InstalledExtension[] {
    return this.store.getState().installed;
  }

  /** Live Source instance for an installed extension, if loaded. */
  getSource(extensionId: string): Source | undefined {
    return this.activeExtensions.get(extensionId)?.source;
  }

  /**
   * Run a Source method in a background worker (scraping / network I/O).
   * Prefer this over calling {@link getSource} methods directly for HTML parsing work.
   */
  async runSourceMethod(
    extensionId: string,
    method: string,
    args: readonly unknown[] = [],
  ): Promise<unknown> {
    const active = this.activeExtensions.get(extensionId);
    if (!active) {
      throw new ExtensionBundleError(
        'not_found',
        `Installed extension "${extensionId}" was not found or is not loaded`,
      );
    }

    if (!isScrapingSourceMethod(method)) {
      throw new SourceExecutionError(
        'method',
        `Method "${method}" must be invoked on the main-thread Source instance via getSource()`,
      );
    }

    return this.executor.runMethod({
      extensionId,
      method,
      args,
      bundle: active.bundle,
      expectedId: extensionId,
    });
  }

  /**
   * Download and parse `index.json` from a remote repository URL.
   * Updates store state on success or failure.
   */
  async fetchIndex(repoUrl: string): Promise<readonly AvailableExtension[]> {
    const normalizedUrl = normalizeRepoUrl(repoUrl);
    this.store.setLoading(true);

    try {
      const response = await this.fetchImpl(normalizedUrl, { headers: { ...DEFAULT_HEADERS } });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status} while fetching extension index`);
      }

      const payload = await response.json();
      const index = parseExtensionIndex(payload);
      const verifiedIndex: ExtensionIndex = {
        ...index,
        extensions: index.extensions.filter(
          (entry) => isEnglishExtension(entry.lang) && isVerifiedExtension(entry.id),
        ),
      };
      const available = this.toAvailableExtensions(verifiedIndex, normalizedUrl);
      const fetchedAt = Date.now();

      this.store.setCatalog(available, {
        repoUrl: normalizedUrl,
        repoName: index.name,
        fetchedAt,
      });
      this.store.markInstalled(this.getInstalledIds());

      return this.store.getState().available;
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to fetch extension index';
      this.store.setError(message);
      throw error;
    }
  }

  /**
   * Download a bundle, evaluate it in a sandbox, validate the Source contract,
   * and register the extension for runtime use.
   */
  async installExtension(extensionId: string): Promise<InstallExtensionResult> {
    if (this.activeExtensions.has(extensionId)) {
      throw new ExtensionBundleError('already_installed', `Extension "${extensionId}" is already installed`);
    }

    const catalogEntry =
      this.store.getState().available.find((entry) => entry.id === extensionId) ??
      this.findInstalledCatalogEntry(extensionId);

    if (!catalogEntry) {
      throw new ExtensionBundleError(
        'not_found',
        `Extension "${extensionId}" was not found in the available catalog`,
      );
    }

    this.store.setInstalling(extensionId);

    try {
      const { bundle, source } = await this.runtime.loadSourceFromUrl(
        catalogEntry.bundleUrl,
        extensionId,
        extensionId,
      );

      const record: InstalledExtension = {
        id: catalogEntry.id,
        name: catalogEntry.name,
        version: catalogEntry.version,
        iconUrl: catalogEntry.iconUrl,
        bundleUrl: catalogEntry.bundleUrl,
        bundleHash: bundle.sha256,
        installedAt: Date.now(),
      };

      this.activeExtensions.set(extensionId, { record, source, bundle });
      this.store.addInstalled(record);
      this.store.markInstalled(this.getInstalledIds());

      return { extension: record, sourceId: source.id };
    } catch (error) {
      const message =
        error instanceof ExtensionBundleError
          ? error.message
          : error instanceof Error
            ? error.message
            : 'Extension install failed';
      this.store.setInstallError(message);
      throw error;
    }
  }

  /**
   * Remove an installed extension and evict its in-memory Source instance.
   */
  uninstallExtension(extensionId: string): void {
    this.activeExtensions.delete(extensionId);
    this.store.removeInstalled(extensionId);
    this.store.markInstalled(this.getInstalledIds());
  }

  clearInstalled(): void {
    this.activeExtensions.clear();
    this.store.syncInstalledFromRecords([]);
  }

  private getInstalledIds(): string[] {
    const ids = new Set<string>(this.activeExtensions.keys());
    for (const extension of this.store.getState().installed) {
      ids.add(extension.id);
    }
    return [...ids];
  }

  private findInstalledCatalogEntry(extensionId: string): AvailableExtension | undefined {
    const installed = this.store.getState().installed.find((entry) => entry.id === extensionId);
    if (!installed) return undefined;
    return {
      id: installed.id,
      name: installed.name,
      version: installed.version,
      iconUrl: installed.iconUrl,
      bundleUrl: installed.bundleUrl,
      repoUrl: this.store.getState().repoUrl ?? installed.bundleUrl,
      fetchedAt: installed.installedAt,
    };
  }

  private toAvailableExtensions(
    index: ExtensionIndex,
    repoUrl: UrlString,
  ): readonly AvailableExtension[] {
    const fetchedAt = Date.now();
    return index.extensions.map((entry: RemoteExtensionEntry) => ({
      ...entry,
      repoUrl,
      fetchedAt,
    }));
  }
}

/** Append `index.json` when the URL points at a repo root or directory. */
export function normalizeRepoUrl(repoUrl: string): string {
  const trimmed = repoUrl.trim();
  if (trimmed.length === 0) {
    throw new Error('Repository URL must not be empty');
  }

  const withoutTrailingSlash = trimmed.replace(/\/+$/, '');
  if (withoutTrailingSlash.endsWith('.json')) {
    return withoutTrailingSlash;
  }
  return `${withoutTrailingSlash}/index.json`;
}

/** Shared singleton used by the Radiant backend server. */
export const defaultExtensionStore = new ExtensionStore();
export const defaultExtensionRepository = new ExtensionRepository(defaultExtensionStore);
