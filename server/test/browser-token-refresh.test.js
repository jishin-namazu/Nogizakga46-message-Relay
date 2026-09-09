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

test('scheduled frontend validation may keep the current access token', async () => {
  const monitor = createMonitor();
  monitor.accessToken = 'current-token';
  monitor.page = {
    async goto() {
      monitor.observeRequest(authenticatedRequest('current-token'));
    },
    async waitForTimeout() {},
  };

  await monitor.refreshFrontendSession();

  assert.equal(monitor.accessToken, 'current-token');
  assert.ok(monitor.lastFrontendNavigationAt > 0);
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

test('browser restart preserves the frontend validation schedule', async () => {
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
  const uploadedState = { cookies: [], origins: [{ origin: 'https://message.nogizaka46.com' }] };
  const serialized = JSON.stringify(uploadedState);
  let openedWith = null;
  monitor.closeBrowser = async () => {};
  monitor.openBrowser = async state => {
    openedWith = state;
    monitor.context = {};
  };
  monitor.refreshFrontendSession = async () => {};
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
