/** Languages kept when fetching remote extension indexes (matches Android allowlist). */
const ENABLED_EXTENSION_LANGS = new Set(['en', 'all']);
/**
 * Returns true only for English (`en`) or multi-language (`all`) extensions.
 * Missing or empty `lang` is rejected.
 */
export function isEnglishExtension(lang) {
    if (lang === undefined)
        return false;
    const normalized = lang.trim().toLowerCase();
    if (normalized.length === 0)
        return false;
    return ENABLED_EXTENSION_LANGS.has(normalized);
}
function isRecord(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function readString(record, key, label) {
    const value = record[key];
    if (typeof value !== 'string' || value.trim().length === 0) {
        throw new Error(`Invalid extension index: "${label}.${key}" must be a non-empty string`);
    }
    return value.trim();
}
function readOptionalString(record, key) {
    const value = record[key];
    if (value === undefined || value === null)
        return undefined;
    if (typeof value !== 'string') {
        throw new Error(`Invalid extension index: "${key}" must be a string when provided`);
    }
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : undefined;
}
function readOptionalBoolean(record, key) {
    const value = record[key];
    if (value === undefined || value === null)
        return undefined;
    if (typeof value !== 'boolean') {
        throw new Error(`Invalid extension index: "${key}" must be a boolean when provided`);
    }
    return value;
}
function readUrl(record, key, label) {
    const raw = readString(record, key, label);
    try {
        const parsed = new URL(raw);
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
            throw new Error('unsupported protocol');
        }
        return parsed.toString();
    }
    catch {
        throw new Error(`Invalid extension index: "${label}.${key}" must be an absolute http(s) URL`);
    }
}
function parseExtensionEntry(raw, index) {
    if (!isRecord(raw)) {
        throw new Error(`Invalid extension index: extensions[${index}] must be an object`);
    }
    const label = `extensions[${index}]`;
    const entry = {
        id: readString(raw, 'id', label),
        name: readString(raw, 'name', label),
        version: readString(raw, 'version', label),
        iconUrl: readUrl(raw, 'iconUrl', label),
        bundleUrl: readUrl(raw, 'bundleUrl', label),
    };
    const lang = readOptionalString(raw, 'lang');
    const description = readOptionalString(raw, 'description');
    const nsfw = readOptionalBoolean(raw, 'nsfw');
    return {
        ...entry,
        ...(lang !== undefined ? { lang } : {}),
        ...(description !== undefined ? { description } : {}),
        ...(nsfw !== undefined ? { nsfw } : {}),
    };
}
function extensionArrayFromDocument(data) {
    const extensions = data['extensions'];
    if (!Array.isArray(extensions)) {
        throw new Error('Invalid extension index: root object must include an "extensions" array');
    }
    return extensions;
}
/**
 * Parse and validate a remote `index.json` payload.
 * Accepts either `{ extensions: [...] }` or a bare `[...]` array.
 */
export function parseExtensionIndex(data) {
    const rawExtensions = Array.isArray(data)
        ? data
        : extensionArrayFromDocument(isRecord(data) ? data : (() => { throw new Error('Invalid extension index: expected object or array'); })());
    const extensions = rawExtensions.map(parseExtensionEntry);
    const byId = new Map();
    for (const entry of extensions) {
        byId.set(entry.id, entry);
    }
    const root = isRecord(data) && !Array.isArray(data) ? data : null;
    return {
        name: root ? readOptionalString(root, 'name') ?? 'Extension Repository' : 'Extension Repository',
        version: root ? readOptionalString(root, 'version') ?? null : null,
        extensions: [...byId.values()],
    };
}
//# sourceMappingURL=parseExtensionIndex.js.map