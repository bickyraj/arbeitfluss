/** Display/copy protection only: persisted data is never mutated. */
const credentialKey = /authorization|cookie|password|passwd|secret|token|apikey|privatekey|credential/i;
const normalizedKey = (key: string) => key.replace(/[^a-z0-9]/gi, '');

export function inspectionText(value: string | null | undefined): string {
  return (value ?? '')
    .replace(/\b(Bearer|Basic)\s+[a-z0-9._~+/=-]+/gi, '$1 [REDACTED]')
    .replace(/\beyJ[a-z0-9_-]+\.[a-z0-9_-]+\.[a-z0-9_-]+\b/gi, '[REDACTED]')
    .replace(/(https?:\/\/)[^\s/@]+:[^\s/@]+@/gi, '$1[REDACTED]@')
    .replace(/([?&](?:access_token|refresh_token|token|secret|api_key|apikey|password)=)[^&#\s]+/gi, '$1[REDACTED]')
    .replace(/\b(password|passwd|secret|secretHash|token|api[_-]?key|credential)(["']?\s*[:=]\s*["']?)(?!\[REDACTED\])[^\s,"';}\]]+/gi,
      '$1$2[REDACTED]');
}

export function inspectionValue(value: unknown): { value: unknown; redacted: boolean; limited: boolean } {
  let redacted = false;
  let limited = false;
  const seen = new WeakSet<object>();
  function visit(item: unknown, depth: number): unknown {
    if (typeof item === 'string') {
      const safe = inspectionText(item);
      if (safe !== item) redacted = true;
      return safe;
    }
    if (item == null || typeof item !== 'object') return item;
    if (depth > 64 || seen.has(item)) {
      limited = true;
      return '[Inspection depth limit]';
    }
    seen.add(item);
    let result: unknown;
    if (Array.isArray(item)) {
      result = item.map((child) => visit(child, depth + 1));
    } else {
      const object = item as Record<string, unknown>;
      const headerName = object['name'] ?? object['key'] ?? object['header'];
      const credentialPair = typeof headerName === 'string' && credentialKey.test(normalizedKey(headerName));
      result = Object.fromEntries(Object.entries(object).map(([key, child]) => {
        if (credentialKey.test(normalizedKey(key)) || (credentialPair && key === 'value')) {
          redacted = true;
          return [key, '[REDACTED]'];
        }
        return [key, visit(child, depth + 1)];
      }));
    }
    seen.delete(item);
    return result;
  }
  const safe = visit(value, 0);
  return { value: safe, redacted, limited };
}

export function formatDuration(milliseconds: number | null | undefined): string {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return '—';
  if (milliseconds < 1000) return `${Math.max(0, milliseconds)} ms`;
  if (milliseconds < 60_000) return `${(milliseconds / 1000).toFixed(2)} s`;
  return `${Math.floor(milliseconds / 60_000)}m ${String(Math.floor(milliseconds / 1000) % 60).padStart(2, '0')}s`;
}
