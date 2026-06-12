import { setTimeout as delay } from 'node:timers/promises';
import { untilSatisfied } from './jsonpath.js';
import { EXIT, PipelineError } from './errors.js';

/**
 * Poll an API predicate until it holds (plan §5: readiness by API poll,
 * never a blind sleep — the interval below is poll cadence, the exit
 * condition is always the predicate).
 *
 * @param {string} baseUrl       e.g. http://127.0.0.1:53211
 * @param {{get:string, until:object, timeoutMs?:number}} wait
 * @param {{label?:string, intervalMs?:number, log?:Function, isAlive?:Function}} opts
 */
export async function pollApi(baseUrl, wait, opts = {}) {
  const timeoutMs = wait.timeoutMs ?? 60000;
  const intervalMs = opts.intervalMs ?? 500;
  const log = opts.log ?? (() => {});
  const started = Date.now();
  let lastDetail = 'no response yet';
  while (Date.now() - started < timeoutMs) {
    if (opts.isAlive && !opts.isAlive()) {
      throw new PipelineError(EXIT.ENV, `${opts.label ?? 'api-poll'}: app process exited while polling ${wait.get}`);
    }
    try {
      const res = await fetch(baseUrl + wait.get, { signal: AbortSignal.timeout(10000) });
      if (res.ok) {
        const body = await res.json();
        const { ok, value } = untilSatisfied(wait.until, body);
        if (ok) {
          log(`${opts.label ?? 'api-poll'}: ${wait.get} ${wait.until.path} = ${JSON.stringify(value)} (satisfied after ${Date.now() - started}ms)`);
          return value;
        }
        lastDetail = `${wait.until.path} = ${JSON.stringify(value)}, want ${JSON.stringify(wait.until.equals ?? `>=${wait.until.min}`)}`;
      } else {
        lastDetail = `HTTP ${res.status}`;
      }
    } catch (e) {
      lastDetail = e.cause?.code ?? e.name ?? String(e);
    }
    await delay(intervalMs);
  }
  throw new PipelineError(
    EXIT.ENV,
    `${opts.label ?? 'api-poll'}: timeout after ${timeoutMs}ms polling ${wait.get} (last: ${lastDetail})`,
  );
}
