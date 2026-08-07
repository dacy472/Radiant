import type { ExtensionIndex } from './types.js';
/**
 * Returns true only for English (`en`) or multi-language (`all`) extensions.
 * Missing or empty `lang` is rejected.
 */
export declare function isEnglishExtension(lang: string | undefined): boolean;
/**
 * Parse and validate a remote `index.json` payload.
 * Accepts either `{ extensions: [...] }` or a bare `[...]` array.
 */
export declare function parseExtensionIndex(data: unknown): ExtensionIndex;
//# sourceMappingURL=parseExtensionIndex.d.ts.map