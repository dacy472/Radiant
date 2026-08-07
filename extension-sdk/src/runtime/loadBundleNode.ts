import vm from 'node:vm';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import type { FetchLike } from '../repository/ExtensionRepository.js';
import type { Source } from '../source.js';
import { detectBundleFormat } from './detectBundleFormat.js';
import { ExtensionBundleError } from './errors.js';
import { validateSourceInstance, resolveSourceExport } from './resolveSourceExport.js';

export interface NodeLoadBundleOptions {
  readonly extensionId: string;
  readonly code: string;
  readonly fetchImpl: FetchLike;
  readonly cacheDir: string;
  readonly sha256: string;
  readonly sandboxTimeoutMs: number;
  readonly expectedId?: string;
}

function createSandboxFetch(fetchImpl: FetchLike): typeof fetch {
  return ((input: RequestInfo | URL, init?: RequestInit) =>
    fetchImpl(String(input), init)) as typeof fetch;
}

/**
 * Execute a non-ESM bundle inside a restricted vm context.
 * Only fetch/URL/TextEncoder/console are exposed to the guest code.
 */
function loadScriptBundle(options: NodeLoadBundleOptions): Promise<Source> {
  const moduleRef: { exports: Record<string, unknown> } = { exports: {} };
  const exportsRef = moduleRef.exports;

  const sandbox: vm.Context = {
    module: moduleRef,
    exports: exportsRef,
    fetch: createSandboxFetch(options.fetchImpl),
    URL,
    TextEncoder,
    TextDecoder,
    console: {
      log: (...args: unknown[]) => console.log(`[ext:${options.extensionId}]`, ...args),
      warn: (...args: unknown[]) => console.warn(`[ext:${options.extensionId}]`, ...args),
      error: (...args: unknown[]) => console.error(`[ext:${options.extensionId}]`, ...args),
    },
  };

  vm.createContext(sandbox);

  const wrapped = `'use strict';\n${options.code}\n`;
  try {
    vm.runInContext(wrapped, sandbox, {
      filename: `${options.extensionId}.bundle.js`,
      timeout: options.sandboxTimeoutMs,
    });
  } catch (error) {
    throw new ExtensionBundleError(
      'sandbox',
      `Bundle execution failed for ${options.extensionId}`,
      error,
    );
  }

  return Promise.resolve(resolveSourceExport(moduleRef.exports)).then((resolved) =>
    validateSourceInstance(resolved, options.expectedId),
  );
}

/**
 * Load an ESM bundle from a content-addressed cache file via dynamic import().
 */
async function loadEsmBundle(options: NodeLoadBundleOptions): Promise<Source> {
  const cacheDir = path.join(options.cacheDir, options.extensionId);
  await mkdir(cacheDir, { recursive: true });
  const filePath = path.join(cacheDir, `${options.sha256}.mjs`);
  await writeFile(filePath, options.code, 'utf8');

  let loaded: Record<string, unknown>;
  try {
    loaded = (await import(pathToFileURL(filePath).href)) as Record<string, unknown>;
  } catch (error) {
    throw new ExtensionBundleError(
      'parse',
      `Failed to import ESM bundle for ${options.extensionId}`,
      error,
    );
  }

  const resolved = await resolveSourceExport(loaded);
  return validateSourceInstance(resolved, options.expectedId);
}

export async function loadBundleOnNode(options: NodeLoadBundleOptions): Promise<Source> {
  const format = detectBundleFormat(options.code);
  if (format === 'esm') {
    return loadEsmBundle(options);
  }
  return loadScriptBundle(options);
}
