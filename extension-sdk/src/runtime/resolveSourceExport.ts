import type { Source } from '../source.js';
import { isSource } from '../source.js';
import { ExtensionBundleError } from './errors.js';

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

async function instantiateExport(exported: unknown): Promise<unknown> {
  if (typeof exported === 'function') {
    try {
      const instance = new (exported as new () => unknown)();
      return instance;
    } catch {
      return (exported as () => unknown)();
    }
  }
  return exported;
}

/**
 * Extract a Source instance from a loaded module object or sandbox export.
 */
export async function resolveSourceExport(moduleExports: unknown): Promise<unknown> {
  if (!isRecord(moduleExports)) {
    throw new ExtensionBundleError('parse', 'Bundle did not export an object');
  }

  if ('default' in moduleExports) {
    return instantiateExport(moduleExports['default']);
  }

  if (typeof moduleExports['createSource'] === 'function') {
    return (moduleExports['createSource'] as () => unknown)();
  }

  if (typeof moduleExports['Source'] === 'function') {
    return instantiateExport(moduleExports['Source']);
  }

  return moduleExports;
}

/**
 * Ensure the loaded value satisfies the Source contract.
 */
export function validateSourceInstance(value: unknown, expectedId?: string): Source {
  if (!isSource(value)) {
    throw new ExtensionBundleError(
      'contract',
      'Bundle export does not implement the Source interface ' +
        '(requires id, name, baseUrl, lang, getWebtoonDetails, getChapterList, getPageList)',
    );
  }

  if (!value.icon || typeof value.icon !== 'string') {
    throw new ExtensionBundleError('contract', 'Source.icon must be a non-empty string');
  }

  if (!value.capabilities || typeof value.capabilities !== 'object') {
    throw new ExtensionBundleError('contract', 'Source.capabilities must be defined');
  }

  if (expectedId !== undefined && value.id !== expectedId) {
    throw new ExtensionBundleError(
      'contract',
      `Source id mismatch: expected "${expectedId}", got "${value.id}"`,
    );
  }

  return value;
}
