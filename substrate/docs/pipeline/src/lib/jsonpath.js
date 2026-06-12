/**
 * Minimal JSONPath evaluator covering the forms the DOC-1 plan documents
 * (§3 `resolve.pick` / `waits.api.until.path`):
 *
 *   $.data.length
 *   $.data[0].id
 *   $.data[?(@.attentionSignal=='decline-pending')].id
 *
 * Semantics:
 *   - `$`            root
 *   - `.name`        property access; on an array, `.length` returns the
 *                    array length, any other name maps over the FIRST element
 *   - `[<int>]`      array index
 *   - `[?(@.k=='v')]` filter — keeps matching elements (array result)
 *
 * Anything fancier is out of scope by design; extend when a spec needs it.
 */
export function evalJsonPath(path, root) {
  if (typeof path !== 'string' || !path.startsWith('$')) {
    throw new Error(`jsonpath: path must start with '$': ${path}`);
  }
  let rest = path.slice(1);
  let cur = root;
  const tokenRe = /^(?:\.([A-Za-z_][A-Za-z0-9_-]*)|\[(\d+)\]|\[\?\(@\.([A-Za-z_][A-Za-z0-9_-]*)\s*==\s*'([^']*)'\)\])/;
  while (rest.length > 0) {
    const m = tokenRe.exec(rest);
    if (!m) throw new Error(`jsonpath: cannot parse '${rest}' in ${path}`);
    rest = rest.slice(m[0].length);
    if (cur === null || cur === undefined) return undefined;
    if (m[1] !== undefined) {
      const name = m[1];
      if (Array.isArray(cur)) {
        if (name === 'length') {
          cur = cur.length;
        } else {
          cur = cur.length > 0 ? cur[0]?.[name] : undefined;
        }
      } else {
        cur = cur[name];
      }
    } else if (m[2] !== undefined) {
      if (!Array.isArray(cur)) return undefined;
      cur = cur[Number(m[2])];
    } else {
      if (!Array.isArray(cur)) return undefined;
      cur = cur.filter((el) => el != null && String(el[m[3]]) === m[4]);
    }
  }
  return cur;
}

/** Evaluate an `until` predicate ({path, equals} | {path, min}) against a JSON body. */
export function untilSatisfied(until, body) {
  const value = evalJsonPath(until.path, body);
  if ('equals' in until) {
    // eslint-disable-next-line eqeqeq — YAML numbers vs JSON strings: compare loosely on purpose
    return { ok: value == until.equals, value };
  }
  if ('min' in until) {
    return { ok: typeof value === 'number' && value >= until.min, value };
  }
  throw new Error('until: needs `equals` or `min`');
}
