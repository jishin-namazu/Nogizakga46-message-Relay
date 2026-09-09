import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import express from 'express';

const { NogiBrowserMonitor } = await import('../src/monitor/nogi-browser.js');
const { default: adminRouter } = await import('../src/routes/admin.js');
const { setErrorLogDbWriter } = await import('../src/services/error-log.js');
const {
  atomicWritePrivateFile,
  atomicWritePrivateJson,
  browserSessionPaths,
  readJsonIfExists,
  sessionVersion,
} = await import('../src/services/browser-session.js');

setErrorLogDbWriter(null);

function createMonitor() {
  const monitor = new NogiBrowserMonitor({
    messageStore: {},
    pusher: {},
  });
  monitor.authorizationWaitMs = 25;
  monitor.pageSettleMs = 0;
  monitor.persistStorageState = async () => {};
  monitor.persistAccessToken = async () => {};
  return monitor;
}

function authenticatedRequest(token) {
  return {
    url: () => 'https://api.message.nogizaka46.com/v2/groups',
    headers: () => ({ authorization: `Bearer ${token}` }),
  };
}

function jwtWithExpiry(expiresAtSeconds) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none' })}.${encode({ exp: expiresAtSeconds })}.signature`;
}

test('frontend validation keeps the current access token while the website loads', async () => {
  const monitor = createMonitor();
  monitor.accessToken = 'current-token';
  monitor.page = {
    async goto() {
      assert.equal(monitor.accessToken, 'current-token');
      monitor.observeRequest(authenticatedRequest('current-token'));
    },
    async waitForTimeout() {},
  };

  await monitor.refreshFrontendSession();

  assert.equal(monitor.accessToken, 'current-token');
  assert.ok(monitor.lastFrontendNavigationAt > 0);
});

test('a usable current access token does not require periodic frontend navigation', () => {
  const monitor = createMonitor();
  monitor.accessToken = 'opaque-current-token';
  monitor.lastFrontendNavigationAt = Date.now() - 24 * 60 * 60 * 1000;

  assert.equal(monitor.shouldRefreshAccessToken(), false);
});

test('an access token near expiry is proactively refreshed before the API request', async (t) => {
  const monitor = createMonitor();
  monitor.accessToken = jwtWithExpiry(Math.floor(Date.now() / 1000) + 10);
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let refreshes = 0;
  let requestAuthorization = '';
  monitor.refreshFrontendSession = async ({ requireNewToken }) => {
    assert.equal(requireNewToken, true);
    refreshes += 1;
    monitor.accessToken = 'rotated-token';
  };
  globalThis.fetch = async (_url, options) => {
    requestAuthorization = options.headers.Authorization;
    return { status: 200, ok: true, text: async () => '[]' };
  };

  await monitor.apiRequest('/v2/groups');

  assert.equal(refreshes, 1);
  assert.equal(requestAuthorization, 'Bearer rotated-token');
});

test('a failed proactive refresh still tries the current token', async (t) => {
  const monitor = createMonitor();
  const currentToken = jwtWithExpiry(Math.floor(Date.now() / 1000) + 10);
  monitor.accessToken = currentToken;
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  monitor.refreshFrontendSession = async () => { throw new Error('refresh unavailable'); };
  let requestAuthorization = '';
  globalThis.fetch = async (_url, options) => {
    requestAuthorization = options.headers.Authorization;
    return { status: 200, ok: true, text: async () => '[]' };
  };

  await monitor.apiRequest('/v2/groups');

  assert.equal(requestAuthorization, `Bearer ${currentToken}`);
});

test('a real 401 refreshes the token and retries the original request once', async (t) => {
  const monitor = createMonitor();
  monitor.accessToken = 'failed-token';
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const requestAuthorizations = [];
  let refreshes = 0;
  monitor.refreshFrontendSession = async ({ requireNewToken }) => {
    assert.equal(requireNewToken, true);
    refreshes += 1;
    monitor.accessToken = 'rotated-token';
  };
  globalThis.fetch = async (_url, options) => {
    requestAuthorizations.push(options.headers.Authorization);
    if (requestAuthorizations.length === 1) {
      return { status: 401, ok: false, text: async () => '' };
    }
    return { status: 200, ok: true, text: async () => '[]' };
  };

  const payload = await monitor.apiRequest('/v2/groups');

  assert.deepEqual(payload, []);
  assert.equal(refreshes, 1);
  assert.deepEqual(requestAuthorizations, ['Bearer failed-token', 'Bearer rotated-token']);
});

test('401 recovery rejects a page that only reuses the failed access token', async () => {
  const monitor = createMonitor();
  monitor.accessToken = 'failed-token';
  monitor.observedTokenAt = 123;
  monitor.page = {
    async goto() {
      monitor.observeRequest(authenticatedRequest('failed-token'));
    },
    async waitForTimeout() {},
  };

  await assert.rejects(
    monitor.refreshFrontendSession({ requireNewToken: true }),
    /不同于失效令牌的新访问令牌/,
  );
  assert.equal(monitor.accessToken, 'failed-token');
  assert.equal(monitor.observedTokenAt, 123);
});

test('401 recovery waits for and accepts a rotated access token', async () => {
  const monitor = createMonitor();
  monitor.accessToken = 'failed-token';
  monitor.page = {
    async goto() {
      monitor.observeRequest(authenticatedRequest('failed-token'));
      setTimeout(() => monitor.observeRequest(authenticatedRequest('rotated-token')), 0);
    },
    async waitForTimeout() {},
  };

  await monitor.refreshFrontendSession({ requireNewToken: true });

  assert.equal(monitor.accessToken, 'rotated-token');
});

test('/v2/update_token must fail three times before authentication is confirmed invalid', () => {
  const monitor = createMonitor();
  const response = status => ({
    url: () => 'https://api.message.nogizaka46.com/v2/update_token',
    status: () => status,
  });

  monitor.observeResponse(response(401));
  monitor.observeResponse(response(401));
  assert.equal(monitor.consecutiveAuthFailures, 2);
  assert.equal(
    monitor.consecutiveAuthFailures >= monitor.maxConsecutiveAuthFailures,
    false,
  );

  monitor.observeResponse(response(401));
  assert.equal(monitor.consecutiveAuthFailures, 3);
  assert.equal(
    monitor.consecutiveAuthFailures >= monitor.maxConsecutiveAuthFailures,
    true,
  );
});

test('successful /v2/update_token response resets the consecutive failure count', () => {
  const monitor = createMonitor();
  monitor.consecutiveAuthFailures = 2;
  monitor.observeResponse({
    url: () => 'https://api.message.nogizaka46.com/v2/update_token',
    status: () => 200,
  });

  assert.equal(monitor.consecutiveAuthFailures, 0);
});

test('HTTP 400 from /v2/update_token enters signedOut immediately', async () => {
  const monitor = createMonitor();
  monitor.isRunning = true;
  monitor.closeBrowser = async () => {};
  monitor.clearPersistedAccessToken = async () => {};
  monitor.pauseAuthentication = async cause => {
    assert.match(cause.message, /HTTP 400/);
    monitor.authPaused = true;
  };

  monitor.observeResponse({
    url: () => 'https://api.message.nogizaka46.com/v2/update_token',
    status: () => 400,
  });
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(monitor.authState, 'signedOut');
  assert.equal(monitor.authPaused, true);
  clearInterval(monitor.signedOutLogTimer);
});

test('browser restart preserves the last successful frontend navigation time', async () => {
  const monitor = createMonitor();
  const previousNavigation = Date.now() - 123_456;
  monitor.lastFrontendNavigationAt = previousNavigation;
  monitor.isRunning = true;
  monitor.closeBrowser = async () => {
    monitor.lastFrontendNavigationAt = 0;
  };
  monitor.openBrowser = async () => {};

  await monitor.restartBrowser();

  assert.equal(monitor.lastFrontendNavigationAt, previousNavigation);
});

test('confirmed authentication failure closes Chromium and waits for a verified session', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-auth-pause-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const monitor = createMonitor();
  monitor.isRunning = true;
  monitor.accessToken = 'failed-token';
  monitor.consecutiveAuthFailures = monitor.maxConsecutiveAuthFailures;
  monitor.accessTokenStateFile = path.join(directory, 'access-token.json');
  await fs.writeFile(monitor.accessTokenStateFile, '{"accessToken":"failed-token"}', 'utf8');
  let browserCloses = 0;
  monitor.closeBrowser = async () => {
    browserCloses += 1;
    monitor.accessToken = '';
  };

  await monitor.pauseAuthentication(new Error('Nogi API 401'));

  assert.equal(monitor.authPaused, true);
  assert.equal(monitor.accessToken, '');
  assert.equal(browserCloses, 1);
  await assert.rejects(fs.access(monitor.accessTokenStateFile), { code: 'ENOENT' });

  let resumed = false;
  const waiting = monitor.waitForAuthenticationResume().then(() => { resumed = true; });
  await Promise.resolve();
  assert.equal(resumed, false);
  monitor.resumeAuthentication({ log: false });
  await waiting;
  assert.equal(resumed, true);
  assert.equal(monitor.authPaused, false);
  assert.equal(monitor.consecutiveAuthFailures, 0);
});

test('auth-paused run loop cannot restart Chromium or poll', async () => {
  const monitor = createMonitor();
  monitor.isRunning = true;
  monitor.authPaused = true;
  let browserOpens = 0;
  let polls = 0;
  monitor.openBrowser = async () => { browserOpens += 1; };
  monitor.poll = async () => { polls += 1; };

  const loop = monitor.runLoop();
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(browserOpens, 0);
  assert.equal(polls, 0);

  monitor.isRunning = false;
  monitor.releaseAuthenticationWaiters();
  await loop;
  assert.equal(monitor.authPaused, true);
});

test('ordinary browser activity is rejected while authentication is paused', async () => {
  const monitor = createMonitor();
  monitor.authPaused = true;
  let launches = 0;
  monitor.browserType = { launch: async () => { launches += 1; } };

  await assert.rejects(monitor.openBrowser(), /认证已暂停/);
  await assert.rejects(monitor.refreshFrontendSession(), /认证已暂停/);
  await assert.rejects(monitor.apiRequest('/v2/groups'), /认证已暂停/);
  await assert.rejects(monitor.poll(), /认证已暂停/);
  assert.equal(launches, 0);
});

test('atomic session writes replace the complete file without temporary leftovers', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-session-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const stateFile = path.join(directory, 'state.json');
  await fs.writeFile(stateFile, '{"old":true}', 'utf8');

  await atomicWritePrivateFile(stateFile, '{"new":true}');

  assert.equal(await fs.readFile(stateFile, 'utf8'), '{"new":true}');
  assert.deepEqual(await fs.readdir(directory), ['state.json']);
});

test('session reload activates the already-read snapshot and records verification', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-reload-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const monitor = createMonitor();
  monitor.storageStateFile = path.join(directory, 'state.json');
  monitor.authPaused = true;
  const uploadedState = { cookies: [], origins: [{ origin: 'https://message.nogizaka46.com' }] };
  const serialized = JSON.stringify(uploadedState);
  let openedWith = null;
  monitor.closeBrowser = async () => {};
  monitor.openBrowser = async state => {
    openedWith = state;
    monitor.context = {};
    monitor.accessToken = 'token-from-previous-session';
  };
  monitor.refreshFrontendSession = async () => {
    assert.equal(monitor.accessToken, '');
    monitor.accessToken = 'token-observed-from-uploaded-session';
  };
  let validationRequests = 0;
  monitor.apiRequest = async () => {
    validationRequests += 1;
    return [];
  };
  monitor.persistStorageState = async () => {};

  await monitor.reloadSession({
    state: uploadedState,
    version: sessionVersion(serialized),
    requestId: 'request-1',
  });

  assert.strictEqual(openedWith, uploadedState);
  assert.equal(validationRequests, 1);
  const { activationStatusFilePath } = browserSessionPaths(monitor.storageStateFile);
  const activation = await readJsonIfExists(activationStatusFilePath);
  assert.equal(activation.requestId, 'request-1');
  assert.equal(activation.version, sessionVersion(serialized));
  assert.equal(activation.status, 'active');
  assert.equal(monitor.authPaused, false);
  assert.ok(Number.isFinite(Date.parse(activation.updatedAt)));
});

test('directory watcher observes atomic replacement and correlates its upload request', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-watch-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const monitor = createMonitor();
  monitor.storageStateFile = path.join(directory, 'state.json');
  const state = { cookies: [], origins: [] };
  const serialized = JSON.stringify(state);
  const version = sessionVersion(serialized);
  const requestId = 'watch-request';
  let resolveReload;
  const reloaded = new Promise(resolve => { resolveReload = resolve; });
  monitor.reloadSession = async loaded => resolveReload(loaded);
  await monitor.startSessionFileWatcher();
  t.after(() => monitor.stopSessionFileWatcher());

  await atomicWritePrivateFile(monitor.storageStateFile, serialized);
  const { uploadStatusFilePath } = browserSessionPaths(monitor.storageStateFile);
  await atomicWritePrivateJson(uploadStatusFilePath, {
    requestId,
    version,
    uploadedAt: new Date().toISOString(),
  });

  const loaded = await Promise.race([
    reloaded,
    new Promise((_, reject) => setTimeout(() => reject(new Error('watcher timeout')), 2_000)),
  ]);
  assert.deepEqual(loaded.state, state);
  assert.equal(loaded.version, version);
  assert.equal(loaded.requestId, requestId);
});

test('directory watcher records an existing session as its startup baseline', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-watch-baseline-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const monitor = createMonitor();
  monitor.storageStateFile = path.join(directory, 'state.json');
  const state = { cookies: [{ name: 'existing' }], origins: [] };
  const serialized = JSON.stringify(state);
  const version = sessionVersion(serialized);
  await fs.writeFile(monitor.storageStateFile, serialized, 'utf8');
  let reloads = 0;
  monitor.reloadSession = async () => { reloads += 1; };

  await monitor.startSessionFileWatcher();
  t.after(() => monitor.stopSessionFileWatcher());
  await new Promise(resolve => setTimeout(resolve, 200));

  assert.equal(monitor.lastPersistedStorageVersion, version);
  assert.equal(reloads, 0);
});

test('session reload waits for an in-flight poll before replacing Chromium', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-reload-lock-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const monitor = createMonitor();
  monitor.storageStateFile = path.join(directory, 'state.json');
  monitor.authPaused = true;
  let finishPoll;
  monitor.activePollingPromise = new Promise(resolve => { finishPoll = resolve; });
  let opened = false;
  monitor.closeBrowser = async () => {};
  monitor.openBrowser = async () => { opened = true; monitor.context = {}; };
  monitor.refreshFrontendSession = async () => { monitor.accessToken = 'new-token'; };
  monitor.apiRequest = async () => [];
  monitor.persistStorageState = async () => {};
  const state = { cookies: [], origins: [] };

  const reload = monitor.reloadSession({
    state,
    version: sessionVersion(JSON.stringify(state)),
    requestId: 'serialized-reload',
  });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(opened, false);
  finishPoll();
  await reload;
  assert.equal(opened, true);
  assert.equal(monitor.authPaused, false);
});

test('session activation is marked failed when the verification API rejects it', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-invalid-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const monitor = createMonitor();
  monitor.storageStateFile = path.join(directory, 'state.json');
  monitor.closeBrowser = async () => {};
  monitor.openBrowser = async () => { monitor.context = {}; };
  monitor.refreshFrontendSession = async () => {};
  monitor.apiRequest = async () => { throw new Error('Nogi API 401'); };
  monitor.persistStorageState = async () => {};
  const state = { cookies: [], origins: [] };

  await monitor.reloadSession({
    state,
    version: sessionVersion(JSON.stringify(state)),
    requestId: 'invalid-request',
  });

  const { activationStatusFilePath } = browserSessionPaths(monitor.storageStateFile);
  const activation = await readJsonIfExists(activationStatusFilePath);
  assert.equal(activation.requestId, 'invalid-request');
  assert.equal(activation.status, 'failed');
  assert.match(activation.error, /401/);
  assert.equal(monitor.authPaused, true);
});

test('admin API reports pending until the monitor records matching activation', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-admin-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const previousStateFile = process.env.NOGI_BROWSER_STATE_FILE;
  process.env.NOGI_BROWSER_STATE_FILE = path.join(directory, 'state.json');
  t.after(() => {
    if (previousStateFile === undefined) delete process.env.NOGI_BROWSER_STATE_FILE;
    else process.env.NOGI_BROWSER_STATE_FILE = previousStateFile;
  });

  const app = express();
  app.use(express.json());
  app.use('/v1/admin', adminRouter);
  const server = await new Promise(resolve => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening));
  });
  t.after(() => new Promise(resolve => server.close(resolve)));
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}/v1/admin/browser-session`;

  const uploadResponse = await fetch(baseUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ session: { cookies: [], origins: [] } }),
  });
  const upload = await uploadResponse.json();
  assert.equal(uploadResponse.status, 202);
  assert.equal(upload.activationStatus, 'pending');
  assert.equal(upload.activated, false);

  const { activationStatusFilePath } = browserSessionPaths(process.env.NOGI_BROWSER_STATE_FILE);
  await atomicWritePrivateJson(activationStatusFilePath, {
    requestId: upload.requestId,
    version: upload.version,
    status: 'active',
    updatedAt: new Date().toISOString(),
  });
  const status = await (await fetch(`${baseUrl}/status`)).json();
  assert.equal(status.requestId, upload.requestId);
  assert.equal(status.activationStatus, 'active');
  assert.equal(status.activated, true);
});
