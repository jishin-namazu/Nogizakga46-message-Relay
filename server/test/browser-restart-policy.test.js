import assert from 'node:assert/strict';
import test from 'node:test';

const { NogiBrowserMonitor } = await import('../src/monitor/nogi-browser.js');
const { setErrorLogDbWriter } = await import('../src/services/error-log.js');
const { pool } = await import('../src/db/index.js');

setErrorLogDbWriter(null);
test.after(() => pool.end());

function withEnv(value, run) {
  const previous = process.env.NOGI_BROWSER_RESTART_INTERVAL_SECONDS;
  if (value === undefined) delete process.env.NOGI_BROWSER_RESTART_INTERVAL_SECONDS;
  else process.env.NOGI_BROWSER_RESTART_INTERVAL_SECONDS = value;
  try {
    return run();
  } finally {
    if (previous === undefined) delete process.env.NOGI_BROWSER_RESTART_INTERVAL_SECONDS;
    else process.env.NOGI_BROWSER_RESTART_INTERVAL_SECONDS = previous;
  }
}

test('periodic browser restart is disabled by default', () => {
  withEnv(undefined, () => {
    const monitor = new NogiBrowserMonitor();
    assert.equal(monitor.browserRestartIntervalMs, 0);
  });
});

test('an explicit 0, negative or non-numeric value keeps the timer disabled', () => {
  for (const raw of ['0', '-60', 'not-a-number', '']) {
    withEnv(raw, () => {
      assert.equal(new NogiBrowserMonitor().browserRestartIntervalMs, 0, `raw=${raw}`);
    });
  }
});

test('a positive interval re-enables the periodic restart with a 5 minute floor', () => {
  withEnv('60', () => {
    assert.equal(new NogiBrowserMonitor().browserRestartIntervalMs, 5 * 60_000);
  });
  withEnv('43200', () => {
    assert.equal(new NogiBrowserMonitor().browserRestartIntervalMs, 12 * 60 * 60_000);
  });
});

test('an elapsed configured interval still triggers a restart', () => {
  withEnv('1800', () => {
    const monitor = new NogiBrowserMonitor();
    monitor.browserStartedAt = Date.now() - 31 * 60 * 1000;
    assert.equal(monitor.shouldRestartBrowser(), true);
  });
});

test('a stale browser never triggers a restart while the timer is disabled', () => {
  withEnv(undefined, () => {
    const monitor = new NogiBrowserMonitor();
    monitor.browserStartedAt = Date.now() - 24 * 60 * 60 * 1000;
    // RSS in tests is far below memoryRestartRssMB, so only the timer could fire.
    assert.equal(monitor.shouldRestartBrowser(), false);
  });
});
