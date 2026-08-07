import { detectBundleFormat } from './detectBundleFormat.js';
import { ExtensionBundleError } from './errors.js';
import { validateSourceInstance, resolveSourceExport } from './resolveSourceExport.js';
/**
 * Browser loader — uses a Blob URL + dynamic import() for ESM bundles.
 * Script bundles are rejected in the browser runtime (use Node host or ship ESM).
 */
export async function loadBundleOnWeb(options) {
    const format = detectBundleFormat(options.code);
    if (format !== 'esm') {
        throw new ExtensionBundleError('sandbox', 'Only ESM bundles are supported in browser hosts. Rebuild the extension with `export default`.');
    }
    const blob = new Blob([options.code], { type: 'text/javascript' });
    const blobUrl = URL.createObjectURL(blob);
    try {
        const loaded = (await import(/* @vite-ignore */ blobUrl));
        const resolved = await resolveSourceExport(loaded);
        return validateSourceInstance(resolved, options.expectedId);
    }
    catch (error) {
        if (error instanceof ExtensionBundleError) {
            throw error;
        }
        throw new ExtensionBundleError('parse', `Failed to import bundle for ${options.extensionId} in browser`, error);
    }
    finally {
        URL.revokeObjectURL(blobUrl);
    }
}
//# sourceMappingURL=loadBundleWeb.js.map