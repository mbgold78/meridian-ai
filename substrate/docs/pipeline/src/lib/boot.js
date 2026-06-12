import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { pollApi } from './apiPoll.js';
import { EXIT, PipelineError } from './errors.js';

/** Runner-allocated free port (plan §6: no port-file mechanism exists). */
export function allocateFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close((err) => (err ? reject(err) : resolve(port)));
    });
  });
}

/**
 * Boot app-0.1.0.jar with the canonical capture flags on a fresh free port,
 * then poll the seed-readiness predicate. The runner builds NOTHING itself —
 * the jar must already exist (:docs gradle tasks depend on :app:bootJar).
 *
 * @returns {{baseUrl:string, port:number, stop:Function, logFile:string}}
 */
export async function bootApp({ jarPath, config, logDir, label }) {
  if (!fs.existsSync(jarPath)) {
    throw new PipelineError(
      EXIT.ENV,
      `app jar not found: ${jarPath} — run via gradle (docsCapture/docsVerify depend on :app:bootJar) or pass --jar/--base-url`,
    );
  }
  const port = await allocateFreePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  fs.mkdirSync(logDir, { recursive: true });
  const logFile = path.join(logDir, `boot-${label}.log`);
  const fd = fs.openSync(logFile, 'w');
  const args = ['-jar', jarPath, ...config.app.bootArgs, `--server.port=${port}`];
  const proc = spawn('java', args, { stdio: ['ignore', fd, fd] });
  let exited = false;
  proc.once('exit', () => {
    exited = true;
    fs.closeSync(fd);
  });

  const readiness = { ...config.readiness.api, timeoutMs: config.app.bootTimeoutMs ?? config.readiness.api.timeoutMs };
  try {
    await pollApi(baseUrl, readiness, {
      label: `boot-${label}`,
      intervalMs: 1000,
      log: (m) => console.log(`  ${m}`),
      isAlive: () => !exited,
    });
  } catch (e) {
    await stopProc(proc, exited);
    throw new PipelineError(EXIT.ENV, `${e.message} — boot log: ${logFile}`);
  }

  return {
    baseUrl,
    port,
    logFile,
    stop: async () => {
      await stopProc(proc, exited);
    },
  };
}

async function stopProc(proc, alreadyExited) {
  if (alreadyExited || proc.exitCode !== null) return;
  if (process.platform === 'win32') {
    // proc.kill() only terminates the direct child on Windows; taskkill /T
    // reaps the whole tree (java spawns no children today — belt and braces).
    spawnSync('taskkill', ['/pid', String(proc.pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    proc.kill('SIGTERM');
  }
  try {
    await Promise.race([once(proc, 'exit'), new Promise((r) => setTimeout(r, 15000))]);
  } catch {
    /* already exited */
  }
}
