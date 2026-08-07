import type { Source } from '../source.js';
/**
 * Extract a Source instance from a loaded module object or sandbox export.
 */
export declare function resolveSourceExport(moduleExports: unknown): Promise<unknown>;
/**
 * Ensure the loaded value satisfies the Source contract.
 */
export declare function validateSourceInstance(value: unknown, expectedId?: string): Source;
//# sourceMappingURL=resolveSourceExport.d.ts.map