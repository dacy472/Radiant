/**
 * Ensure a Source method result is structured-cloneable / JSON-safe for worker IPC.
 */
export function serializeSourceResult(value) {
    if (value === undefined) {
        return null;
    }
    if (typeof structuredClone === 'function') {
        try {
            structuredClone(value);
            return value;
        }
        catch {
            // Fall through to JSON round-trip.
        }
    }
    return JSON.parse(JSON.stringify(value));
}
//# sourceMappingURL=serialization.js.map