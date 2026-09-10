import dotenv from 'dotenv';
import fs from 'node:fs/promises';
import { watch } from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';
import messageService from '../services/message.js';
import pushService from '../services/push.js';
import { recordError } from '../services/error-log.js';
import {
  atomicWritePrivateFile,
  atomicWritePrivateJson,
  browserSessionPaths,
  readBrowserSession,
  readJsonIfExists,
  sessionVersion,
} from '../services/browser-session.js';

dotenv.config();

const DEFAULT_API_URL = 'https://api.message.nogizaka46.com';
const DEFAULT_WEB_URL = 'https://message.nogizaka46.com';
const DEFAULT_APP_ID = 'jp.co.sonymusic.communication.nogizaka 2.5';
const DEFAULT_PLATFORM = 'web';
const DEFAULT_ORGANIZATION_ID = '1';
const DEFAULT_POLL_INTERVAL_MS = 60_000;
const DEFAULT_BROWSER_STATE_FILE = '/data/nogi-browser-state.json';
const MEMORY_RESTART_RSS_MB = 850;
const ACCESS_TOKEN_REFRESH_SKEW_MS = 30_000;

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

function parseBoolean(value, fallback) {
  if (value == null || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function parseGroupIds(value) {
  return String(value || '')
    .split(',')
    .map(item => Number.parseInt(item.trim(), 10))
    .filter(Number.isInteger)
    .filter((id, index, ids) => ids.indexOf(id) === index);
}

function normalizeType(type) {
  const typeMap = {
    text: 'text',
    article: 'text',
    picture: 'image',
    photo: 'image',
    image: 'image',
    audio: 'audio',
    voice: 'audio',
    call: 'audio',
    video: 'video',
    movie: 'video',
  };
  return typeMap[String(type || '').toLowerCase()] || 'text';
}

function firstNonEmpty(...values) {
  return values.find(value => value != null && String(value).trim() !== '') ?? null;
}

function browserExecutablePath() {
  return process.env.NOGI_BROWSER_EXECUTABLE_PATH
    || process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
    || undefined;
}

function tokenExpiry(token) {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return Number.isFinite(decoded.exp) ? new Date(decoded.exp * 1000) : null;
  } catch {
    return null;
  }
}

/**
 * Uses the official web app as the authentication client. The page owns the
 * refresh-token flow; this worker only observes the short-lived access token
 * on ordinary API requests and keeps it in memory for timeline polling.
 */
class NogiBrowserMonitor {
  constructor({ browserType = chromium, messageStore = messageService, pusher = pushService } = {}) {
    this.browserType = browserType;
    this.messageStore = messageStore;
    this.pusher = pusher;
    this.apiUrl = (process.env.NOGI_API_URL || DEFAULT_API_URL).replace(/\/$/, '');
    this.webUrl = (process.env.NOGI_WEB_URL || DEFAULT_WEB_URL).replace(/\/$/, '');
    this.appId = process.env.NOGI_APP_ID || DEFAULT_APP_ID;
    this.platform = process.env.NOGI_APP_PLATFORM || DEFAULT_PLATFORM;
    this.organizationId = process.env.NOGI_ORGANIZATION_ID || DEFAULT_ORGANIZATION_ID;
    this.groupIds = parseGroupIds(process.env.NOGI_GROUP_IDS);
    this.pollIntervalMs = Math.max(
      Number.parseInt(process.env.NOGI_POLL_INTERVAL_SECONDS || '60', 10) * 1000,
      15_000,
    ) || DEFAULT_POLL_INTERVAL_MS;
    this.pageSettleMs = Math.max(
      Number.parseInt(process.env.NOGI_BROWSER_SETTLE_SECONDS || '8', 10) * 1000,
      2_000,
    );
    this.authorizationWaitMs = Math.max(
      Number.parseInt(process.env.NOGI_BROWSER_AUTH_WAIT_SECONDS || '30', 10) * 1000,
      10_000,
    );
    this.requestTimeoutMs = Math.max(
      Number.parseInt(process.env.NOGI_BROWSER_REQUEST_TIMEOUT_SECONDS || '30', 10) * 1000,
      10_000,
    );
    // The periodic restart is opt-in: unset, 0, negative or non-numeric all
    // disable it, and the RSS trigger in shouldRestartBrowser() stays active.
    const restartIntervalSeconds = Number.parseInt(
      process.env.NOGI_BROWSER_RESTART_INTERVAL_SECONDS ?? '',
      10,
    );
    this.browserRestartIntervalMs = Number.isFinite(restartIntervalSeconds) && restartIntervalSeconds > 0
      ? Math.max(restartIntervalSeconds * 1000, 5 * 60_000)
      : 0;
    this.memoryRestartRssMB = MEMORY_RESTART_RSS_MB;
    this.backfillOnStart = parseBoolean(process.env.NOGI_BACKFILL_ON_START, true);
    this.headless = parseBoolean(process.env.NOGI_BROWSER_HEADLESS, true);
    this.blockPageMedia = parseBoolean(process.env.NOGI_BROWSER_BLOCK_MEDIA, true);
    this.storageStateFile = process.env.NOGI_BROWSER_STATE_FILE || DEFAULT_BROWSER_STATE_FILE;
    this.accessTokenStateFile = process.env.NOGI_ACCESS_TOKEN_STATE_FILE
      || path.join(path.dirname(this.storageStateFile), 'nogi-access-token.json');
    this.pageUrl = `${this.webUrl}/organization/${encodeURIComponent(this.organizationId)}/talk?mode=normal`;
    this.browser = null;
    this.context = null;
    this.page = null;
    this.browserStartedAt = 0;
    this.lastFrontendNavigationAt = 0;
    this.statePersistTimer = null;
    this.statePersistPromise = Promise.resolve();
    this.accessTokenWaiters = new Set();
    this.refreshPromise = null;
    this.loopPromise = null;
    this.isRunning = false;
    this.accessToken = '';
    this.observedTokenAt = 0;
    this.lastPersistedAccessToken = '';
    this.hasCompletedInitialPoll = false;
    this.backfilledGroupIds = new Set();
    this.historyBackfillReason = 'startup';
    this.groupMessageIds = new Map();
    this.groups = new Map();
    this.sessionFileWatcher = null;
    this.sessionReloadTimer = null;
    this.pendingSessionReload = false;
    this.isReloadingSession = false;
    this.suspendStoragePersistence = false;
    this.lastPersistedStorageVersion = '';
    this.lastHandledUploadRequestId = '';
    this.consecutiveAuthFailures = 0;
    this.maxConsecutiveAuthFailures = Math.max(
      Number.parseInt(
        process.env.NOGI_MAX_TOKEN_REFRESH_FAILURES
          || process.env.NOGI_MAX_AUTH_FAILURES
          || '3',
        10,
      ),
      1,
    );
    this.authPaused = false;
    this.authState = 'starting';
    this.signedOutLogTimer = null;
    this.authResumeWaiters = new Set();
    this.sessionReloadWaiters = new Set();
    this.activePollingPromise = null;
  }

  normalizeMessage(rawMessage, group) {
    const type = normalizeType(rawMessage.type || rawMessage.content_type);
    const memberName = firstNonEmpty(rawMessage.member_name, rawMessage.memberName, group.name, '乃木坂46');
    const sentAt = firstNonEmpty(
      rawMessage.published_at,
      rawMessage.sent_at,
      rawMessage.created_at,
    );
    const id = firstNonEmpty(rawMessage.id, rawMessage.message_id);
    if (!id || !sentAt) return null;

    const isCanceled = rawMessage.state === 'canceled';

    return {
      id: String(id),
      member_id: String(firstNonEmpty(rawMessage.member_id, rawMessage.memberId, group.id)),
      member_name: memberName,
      member_avatar_url: firstNonEmpty(rawMessage.member_avatar_url, rawMessage.avatar, group.thumbnail),
      phone_image_url: firstNonEmpty(rawMessage.phone_image_url, rawMessage.phone_image, group.phone_image),
      type,
      text: firstNonEmpty(rawMessage.text, rawMessage.message),
      media_url: firstNonEmpty(rawMessage.file, rawMessage.media_url),
      thumbnail_url: firstNonEmpty(rawMessage.thumbnail, rawMessage.thumbnail_url),
      duration_seconds: Number.parseInt(firstNonEmpty(rawMessage.duration, rawMessage.duration_seconds, 0), 10) || null,
      sent_at: new Date(sentAt).toISOString(),
      incoming_call_from: type === 'audio' ? memberName : null,
      ringtone_url: null,
      original_data: rawMessage,
      is_canceled: isCanceled,
    };
  }

  async processMessage(message, sendPush) {
    try {
      const saveResult = await this.messageStore.saveMessage(message);
      const isNew = typeof saveResult === 'object' && saveResult !== null && 'isNew' in saveResult
        ? Boolean(saveResult.isNew)
        : Boolean(saveResult);
      // Push the persisted row, not the normalized object: only the row carries
      // media_local_path/thumbnail_local_path/phone_image_local_path, so the FCM
      // payload points at the protected Relay archive instead of the upstream CDN.
      const pushTarget = typeof saveResult === 'object' && saveResult !== null && saveResult.message
        ? saveResult.message
        : message;
      let pushed = false;

      // 不推送已撤回的消息
      if (sendPush && isNew && !message.is_canceled) {
        try {
          await this.pusher.pushMessage(pushTarget);
          pushed = true;
        } catch (error) {
          await recordError('monitor.push_message', error, { messageId: message.id });
        }
      }

      return { isNew, pushed, processed: true };
    } catch (error) {
      await recordError('monitor.store_message', error, {
        message_id: message.id,
        member_id: message.member_id,
        member_name: message.member_name,
        type: message.type,
        sent_at: message.sent_at,
      });
      return { isNew: false, pushed: false, processed: false };
    }
  }

  async start() {
    if (this.isRunning) return this.loopPromise;

    try {
      await this.openBrowser();
      await this.startSessionFileWatcher();
    } catch (error) {
      await this.closeBrowser();
      throw error;
    }

    console.log(this.browserRestartIntervalMs > 0
      ? `Nogi browser restart policy: RSS > ${this.memoryRestartRssMB}MB, or every ${Math.round(this.browserRestartIntervalMs / 60_000)} min`
      : `Nogi browser restart policy: RSS > ${this.memoryRestartRssMB}MB only (periodic restart disabled)`);
    this.isRunning = true;
    this.authState = 'authenticated';
    this.loopPromise = this.runLoop();
    return this.loopPromise;
  }

  async runLoop() {
    try {
      let loopCount = 0;
      while (this.isRunning) {
        await this.waitForPollingAllowed();
        if (!this.isRunning) break;

        try {
          const polling = (async () => {
            if (!this.page || this.page.isClosed()) await this.openBrowser();
            if (this.shouldRestartBrowser()) await this.restartBrowser();
            if (!this.accessToken) await this.refreshFrontendSession();
            await this.poll();
          })();
          this.activePollingPromise = polling;
          await polling;
          
          loopCount++;
          if (loopCount % 10 === 0) {
            const memUsage = process.memoryUsage();
            console.log(`内存状态: RSS=${Math.round(memUsage.rss / 1024 / 1024)}MB, Heap=${Math.round(memUsage.heapUsed / 1024 / 1024)}MB/${Math.round(memUsage.heapTotal / 1024 / 1024)}MB`);
          }
        } catch (error) {
          const isAuthError = error.message?.includes('官网页面没有发出带 Authorization 的 API 请求')
            || error.message?.includes('官网页面尚未提供访问令牌')
            || error.message?.includes('Nogi API 401');
          
          await recordError('monitor.poll', error, {
            mode: 'browser',
            has_access_token: Boolean(this.accessToken),
            browser_started_at: this.browserStartedAt || null,
            is_auth_error: isAuthError,
            consecutive_failures: this.consecutiveAuthFailures,
          });

          if (isAuthError) {
            if (this.consecutiveAuthFailures >= this.maxConsecutiveAuthFailures) {
              await this.pauseAuthentication(error);
              await this.waitForAuthenticationResume();
              continue;
            }
            
            console.error(
              `认证请求失败；/v2/update_token 连续失败 `
              + `${this.consecutiveAuthFailures}/${this.maxConsecutiveAuthFailures} 次，继续重试。`,
            );
            this.accessToken = '';
            this.observedTokenAt = 0;
            await sleep(Math.max(60_000, this.pollIntervalMs));
            continue;
          }
        } finally {
          this.activePollingPromise = null;
        }
        if (this.isRunning) await sleep(this.pollIntervalMs);
      }
    } finally {
      this.loopPromise = null;
    }
  }

  async stop() {
    this.isRunning = false;
    if (this.signedOutLogTimer) clearInterval(this.signedOutLogTimer);
    this.signedOutLogTimer = null;
    this.releaseAuthenticationWaiters();
    this.releaseSessionReloadWaiters();
    this.stopSessionFileWatcher();
    if (this.loopPromise) await this.loopPromise;
    await this.closeBrowser();
  }

  async clearPersistedAccessToken() {
    this.lastPersistedAccessToken = '';
    try {
      await fs.unlink(this.accessTokenStateFile);
    } catch (error) {
      if (error.code !== 'ENOENT') {
        console.warn('无法清除已失效的访问令牌缓存:', error.message);
      }
    }
  }

  async enterSignedOut(cause) {
    if (this.authState === 'signedOut') return;
    this.authState = 'signedOut';
    console.error('[NOGI_AUTH_SIGNED_OUT] /v2/update_token 返回 400，会话已退出。');
    await this.pauseAuthentication(cause, { auth_state: 'signedOut' });
    if (!this.signedOutLogTimer) {
      this.signedOutLogTimer = setInterval(() => {
        if (this.authState === 'signedOut') {
          console.error('[NOGI_SESSION_UPDATE_REQUIRED] 会话已退出，需要更新会话文件。');
        }
      }, 5 * 60_000);
      this.signedOutLogTimer.unref?.();
    }
  }

  async pauseAuthentication(cause, context = {}) {
    const wasPaused = this.authPaused;
    this.authPaused = true;
    this.accessToken = '';
    this.observedTokenAt = 0;
    await this.clearPersistedAccessToken();
    await this.closeBrowser();

    if (!wasPaused) {
      const message = [
        '[NOGI_AUTH_PAUSED] 官网 access token 已失效，且无法通过官网获取新 token。',
        '已停止 Chromium、官网认证请求和消息轮询；HTTP 健康检查、管理接口、媒体服务及会话文件监听保持运行。',
        '请上传新的浏览器会话文件；新会话通过官网 API 验证后，监控会自动恢复。',
      ].join('\n');
      console.error(message);
      await recordError('monitor.auth_paused', cause || new Error(message), {
        requires_session_update: true,
        consecutive_auth_failures: this.consecutiveAuthFailures,
        ...context,
      });
    }
  }

  waitForAuthenticationResume() {
    if (!this.isRunning || !this.authPaused) return Promise.resolve();
    return new Promise(resolve => this.authResumeWaiters.add(resolve));
  }

  releaseAuthenticationWaiters() {
    for (const resolve of this.authResumeWaiters) resolve();
    this.authResumeWaiters.clear();
  }

  waitForSessionReload() {
    if (!this.isRunning || !this.isReloadingSession) return Promise.resolve();
    return new Promise(resolve => this.sessionReloadWaiters.add(resolve));
  }

  releaseSessionReloadWaiters() {
    for (const resolve of this.sessionReloadWaiters) resolve();
    this.sessionReloadWaiters.clear();
  }

  async waitForPollingAllowed() {
    while (this.isRunning && (this.authPaused || this.isReloadingSession)) {
      if (this.authPaused) await this.waitForAuthenticationResume();
      else await this.waitForSessionReload();
    }
  }

  resumeAuthentication({ log = true } = {}) {
    const wasPaused = this.authPaused;
    if (this.signedOutLogTimer) clearInterval(this.signedOutLogTimer);
    this.signedOutLogTimer = null;
    this.authPaused = false;
    this.consecutiveAuthFailures = 0;
    this.authState = 'authenticated';
    this.releaseAuthenticationWaiters();
    if (wasPaused && log) {
      console.log('[NOGI_AUTH_RESUMED] 新浏览器会话验证成功，官网消息轮询已恢复。');
    }
  }

  assertBrowserActivityAllowed({ sessionActivation = false } = {}) {
    if ((this.authPaused || this.authState === 'signedOut') && !sessionActivation) {
      throw new Error('认证已暂停，等待新会话验证');
    }
  }

  async openBrowser(storageStateOverride = undefined, { sessionActivation = false } = {}) {
    this.assertBrowserActivityAllowed({ sessionActivation });
    await this.closeBrowser();
    let browserTimeout;
    const timeoutPromise = new Promise((_, reject) => {
      browserTimeout = setTimeout(() => reject(new Error('浏览器启动超时(90秒)')), 90_000);
    });
    
    try {
      await Promise.race([
        (async () => {
          const storageState = storageStateOverride === undefined
            ? await this.loadStorageState()
            : storageStateOverride;
          const persistedAccessToken = await this.loadAccessTokenState();
          const executablePath = browserExecutablePath();
          this.browser = await this.browserType.launch({
            headless: this.headless,
            channel: executablePath ? undefined : 'chromium-headless-shell',
            executablePath,
            timeout: 60_000,
            args: [
              '--no-sandbox',
              '--disable-dev-shm-usage',
              '--disable-gpu',
              '--disable-software-rasterizer',
              '--disable-extensions',
              '--disable-background-networking',
              '--disable-sync',
              '--disable-translate',
              '--disable-features=TranslateUI',
              '--disable-default-apps',
              '--no-first-run',
              '--no-zygote',
              '--single-process',
              '--disable-web-security',
              '--js-flags=--max-old-space-size=256',
            ],
          });
          this.assertBrowserActivityAllowed({ sessionActivation });
          this.context = await this.browser.newContext(storageState ? { storageState } : {});
          this.page = this.context.pages()[0] || await this.context.newPage();
          if (persistedAccessToken) {
            this.accessToken = persistedAccessToken;
            this.observedTokenAt = Date.now();
          }
          this.page.on('request', request => this.observeRequest(request));
          this.page.on('response', response => this.observeResponse(response));
          if (this.blockPageMedia) {
            await this.page.route('**/*', route => {
              const resourceType = route.request().resourceType();
              if (['image', 'media', 'font'].includes(resourceType)) {
                return route.abort().catch(() => {});
              }
              return route.continue().catch(() => {});
            });
          }
          this.browserStartedAt = Date.now();
        })(),
        timeoutPromise,
      ]);
    } catch (error) {
      await this.closeBrowser();
      throw error;
    } finally {
      clearTimeout(browserTimeout);
    }
  }

  async closeBrowser() {
    if (this.statePersistTimer) {
      clearTimeout(this.statePersistTimer);
      this.statePersistTimer = null;
    }
    await this.statePersistPromise.catch(() => {});
    await this.context?.close().catch(() => {});
    await this.browser?.close().catch(() => {});
    this.context = null;
    this.browser = null;
    this.page = null;
    this.browserStartedAt = 0;
    this.lastFrontendNavigationAt = 0;
    this.accessToken = '';
    this.observedTokenAt = 0;
    for (const waiter of this.accessTokenWaiters) waiter.reject(new Error('浏览器上下文已关闭'));
    this.accessTokenWaiters.clear();
    
    if (global.gc) {
      global.gc();
      console.log('强制垃圾回收已执行');
    }
  }

  shouldRestartBrowser() {
    const memUsage = process.memoryUsage();
    const heapUsedMB = Math.round(memUsage.heapUsed / 1024 / 1024);
    const rssMB = Math.round(memUsage.rss / 1024 / 1024);
    
    if (rssMB > this.memoryRestartRssMB) {
      console.log(`内存使用过高 (RSS: ${rssMB}MB, Heap: ${heapUsedMB}MB), 触发浏览器重启`);
      return true;
    }
    
    return this.browserRestartIntervalMs > 0
      && this.browserStartedAt > 0
      && Date.now() - this.browserStartedAt >= this.browserRestartIntervalMs;
  }

  shouldRefreshAccessToken() {
    const expiresAt = tokenExpiry(this.accessToken);
    return expiresAt != null && expiresAt.getTime() <= Date.now() + ACCESS_TOKEN_REFRESH_SKEW_MS;
  }

  async restartBrowser() {
    console.log('Nogi browser monitor restarting browser context to release memory');
    const lastFrontendNavigationAt = this.lastFrontendNavigationAt;
    await this.closeBrowser();
    if (this.isRunning) {
      await this.openBrowser();
      this.lastFrontendNavigationAt = lastFrontendNavigationAt;
    }
  }

  observeRequest(request) {
    let url;
    try {
      url = new URL(request.url());
    } catch {
      return;
    }
    if (url.origin !== new URL(this.apiUrl).origin) return;

    const authorization = request.headers().authorization || '';
    if (!authorization.toLowerCase().startsWith('bearer ')) return;
    const token = authorization.slice(7).trim();
    if (!token) return;
    this.accessToken = token;
    this.observedTokenAt = Date.now();
    void this.persistAccessToken();
    for (const waiter of this.accessTokenWaiters) {
      if (!waiter.accepts(token)) continue;
      this.accessTokenWaiters.delete(waiter);
      waiter.resolve(token);
    }
  }

  waitForAccessToken({ excludeToken = '' } = {}) {
    const accepts = token => Boolean(token) && (!excludeToken || token !== excludeToken);
    if (accepts(this.accessToken)) return Promise.resolve(this.accessToken);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.accessTokenWaiters.delete(waiter);
        reject(new Error(excludeToken
          ? '官网页面没有提供不同于失效令牌的新访问令牌'
          : '官网页面没有发出带 Authorization 的 API 请求，请先在浏览器会话中登录'));
      }, this.authorizationWaitMs);
      const waiter = {
        accepts,
        resolve: token => {
          clearTimeout(timer);
          resolve(token);
        },
        reject: error => {
          clearTimeout(timer);
          reject(error);
        },
      };
      this.accessTokenWaiters.add(waiter);
    });
  }

  observeResponse(response) {
    let url;
    try {
      url = new URL(response.url());
    } catch {
      return;
    }
    if (url.origin !== new URL(this.apiUrl).origin || url.pathname !== '/v2/update_token') return;
    const status = response.status();
    if (status === 400) {
      void this.enterSignedOut(new Error('/v2/update_token returned HTTP 400'));
      return;
    }
    if (status < 200 || status >= 300) {
      if (status >= 400) {
        this.consecutiveAuthFailures += 1;
        console.warn(
          `/v2/update_token 失败 (${status})，连续失败 `
          + `${this.consecutiveAuthFailures}/${this.maxConsecutiveAuthFailures} 次`,
        );
      }
      return;
    }

    this.consecutiveAuthFailures = 0;

    // The page commits the rotated refresh token to browser storage
    // asynchronously. Give it a moment, then persist all browser storage.
    if (this.statePersistTimer) clearTimeout(this.statePersistTimer);
    this.statePersistTimer = setTimeout(() => {
      this.statePersistTimer = null;
      this.persistStorageState().catch(error => {
        console.warn('Nogi browser state could not be persisted after token refresh:', error.message);
      });
    }, 250);
  }

  async refreshFrontendSession({ requireNewToken = false, sessionActivation = false } = {}) {
    this.assertBrowserActivityAllowed({ sessionActivation });
    if (this.refreshPromise) return this.refreshPromise;

    this.refreshPromise = (async () => {
      const previousToken = this.accessToken;
      const previousObservedTokenAt = this.observedTokenAt;
      let refreshTimeout;
      const timeoutPromise = new Promise((_, reject) => {
        refreshTimeout = setTimeout(() => reject(new Error('会话刷新超时(45秒)')), 45_000);
      });
      
      try {
        console.log(requireNewToken ? '正在刷新官网访问令牌...' : '正在验证前端会话...');
        await Promise.race([
          (async () => {
            await this.page.goto(this.pageUrl, { waitUntil: 'commit', timeout: 25_000 });
            await this.waitForAccessToken({
              excludeToken: requireNewToken ? previousToken : '',
            });
          })(),
          timeoutPromise,
        ]);
        this.lastFrontendNavigationAt = Date.now();
        if (previousToken && previousToken !== this.accessToken) {
          console.log('Nogi browser session supplied a refreshed access token');
        } else if (!requireNewToken) {
          console.log('Nogi browser session validated with the current access token');
        }
        await this.page.waitForTimeout(this.pageSettleMs);
        await this.persistStorageState();
        await this.persistAccessToken();
        console.log(requireNewToken ? '官网访问令牌刷新成功' : '前端会话验证成功');
      } catch (error) {
        console.error(requireNewToken ? '官网访问令牌刷新失败:' : '前端会话验证失败:', error.message);
        this.accessToken = previousToken;
        this.observedTokenAt = previousObservedTokenAt;
        throw error;
      } finally {
        clearTimeout(refreshTimeout);
      }
    })().finally(() => {
      this.refreshPromise = null;
    });
    return this.refreshPromise;
  }

  headers() {
    if (!this.accessToken) throw new Error('官网页面尚未提供访问令牌');
    return {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-Talk-App-ID': this.appId,
      'X-Talk-App-Platform': this.platform,
      'Accept-Language': process.env.NOGI_ACCEPT_LANGUAGE || 'zh-CN,en-US,ja',
      Authorization: `Bearer ${this.accessToken}`,
    };
  }

  async apiRequest(pathname, { retryAuth = true, sessionActivation = false } = {}) {
    this.assertBrowserActivityAllowed({ sessionActivation });
    // Match the official web TokenManager: refresh near expiry when possible,
    // but still try the current token if that proactive refresh cannot finish.
    // A real 401 below remains the authoritative signal and gets one refresh + retry.
    if (retryAuth && this.shouldRefreshAccessToken()) {
      try {
        await this.refreshFrontendSession({ requireNewToken: true });
      } catch (error) {
        console.warn('官网访问令牌预刷新失败,继续使用当前令牌请求:', error.message);
      }
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.requestTimeoutMs);
    let response;
    try {
      response = await fetch(`${this.apiUrl}${pathname}`, {
        headers: this.headers(),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }

    if (response.status === 401 && retryAuth) {
      try {
        await this.refreshFrontendSession({ requireNewToken: true });
        return this.apiRequest(pathname, { retryAuth: false });
      } catch (refreshError) {
        console.error('会话刷新失败:', refreshError.message);
        const detail = '会话已过期,无法刷新。请上传新的浏览器会话文件。';
        throw new Error(`Nogi API ${response.status} ${pathname}: ${detail}`);
      }
    }

    const responseText = await response.text();
    let payload = null;
    if (responseText) {
      try {
        payload = JSON.parse(responseText);
      } catch {
        payload = responseText;
      }
    }
    if (!response.ok) {
      const detail = typeof payload === 'string' ? payload : payload?.message || payload?.error;
      throw new Error(`Nogi API ${response.status} ${pathname}${detail ? `: ${detail}` : ''}`);
    }
    return payload;
  }

  async resolveGroups() {
    if (this.groupIds.length > 0) {
      return this.groupIds.map(id => ({ id, name: '', phone_image: null, thumbnail: null }));
    }

    const groups = await this.apiRequest(`/v2/groups?organization_id=${encodeURIComponent(this.organizationId)}`);
    if (!Array.isArray(groups)) throw new Error('Nogi API groups response is not an array');

    return groups
      .filter(group => String(group.organization_id) === String(this.organizationId))
      .filter(group => group.state === 'open')
      .filter(group => group.subscription?.state === 'active')
      .map(group => ({
        id: Number(group.id),
        name: String(group.name || '').trim(),
        phone_image: group.phone_image || null,
        thumbnail: group.thumbnail || null,
      }))
      .filter(group => Number.isInteger(group.id));
  }

  async fetchTimelinePage(groupId, continuation = null) {
    const query = new URLSearchParams();
    if (continuation == null) {
      query.set('count', '200');
      query.set('order', 'desc');
    } else {
      query.set('continuation', String(continuation));
    }
    query.set('clear_unread', 'false');

    const payload = await this.apiRequest(`/v2/groups/${encodeURIComponent(groupId)}/timeline?${query}`);
    if (!payload || !Array.isArray(payload.messages)) {
      throw new Error(`Nogi API timeline response for group ${groupId} is invalid`);
    }
    return {
      messages: payload.messages,
      continuation: payload.continuation == null || payload.continuation === ''
        ? null
        : String(payload.continuation),
    };
  }

  async fetchTimeline(groupId) {
    const page = await this.fetchTimelinePage(groupId);
    return page.messages;
  }

  async fetchAllTimeline(groupId) {
    const firstPage = await this.fetchTimelinePage(groupId);
    const messages = [...firstPage.messages];
    const seenContinuations = new Set();
    let continuation = firstPage.continuation;
    let pageCount = 1;

    while (continuation != null) {
      if (seenContinuations.has(continuation)) {
        throw new Error(`Nogi API timeline continuation loop detected for group ${groupId}`);
      }
      seenContinuations.add(continuation);

      const page = await this.fetchTimelinePage(groupId, continuation);
      messages.push(...page.messages);
      continuation = page.continuation;
      pageCount += 1;
    }

    return {
      messages,
      firstPageMessages: firstPage.messages,
      pageCount,
    };
  }

  async fetchPastMessages(groupId) {
    const payload = await this.apiRequest(
      `/v2/groups/${encodeURIComponent(groupId)}/past_messages`,
    );
    if (!payload || !Array.isArray(payload.messages)) {
      throw new Error(`Nogi API past_messages response for group ${groupId} is invalid`);
    }
    return payload.messages;
  }

  async poll() {
    this.assertBrowserActivityAllowed();
    const isInitialSync = !this.hasCompletedInitialPoll;
    const groups = await this.resolveGroups();
    if (groups.length === 0) throw new Error('No active subscribed groups found');

    const activeGroupIds = new Set(groups.map(group => group.id));
    for (const groupId of this.backfilledGroupIds) {
      if (!activeGroupIds.has(groupId)) this.backfilledGroupIds.delete(groupId);
    }
    const hasHistoryBackfill = groups.some(group => !this.backfilledGroupIds.has(group.id));
    let pollTimeout;
    const timeoutPromise = hasHistoryBackfill
      ? null
      : new Promise((_, reject) => {
        pollTimeout = setTimeout(() => reject(new Error('轮询超时(120秒)')), 120_000);
      });

    try {
      const polling = (async () => {
          let fetched = 0;
          let stored = 0;
          let pushed = 0;

          for (const group of groups) {
            const shouldBackfillHistory = !this.backfilledGroupIds.has(group.id);
            const sendPush = shouldBackfillHistory ? !this.backfillOnStart : true;
            let rawMessages;
            let currentPageMessages;
            if (shouldBackfillHistory) {
              const pastMessages = await this.fetchPastMessages(group.id);
              const timeline = await this.fetchAllTimeline(group.id);
              const seenIds = new Set();
              rawMessages = [...timeline.messages, ...pastMessages].filter(rawMessage => {
                const rawId = String(rawMessage.id ?? rawMessage.message_id ?? '');
                if (!rawId || seenIds.has(rawId)) return false;
                seenIds.add(rawId);
                return true;
              });
              currentPageMessages = timeline.firstPageMessages;
              const backfillReason = isInitialSync
                ? 'startup'
                : this.historyBackfillReason || 'new_subscription';
              console.log(
                `Nogi history fetched: reason=${backfillReason}, group=${group.id}, `
                + `timeline_pages=${timeline.pageCount}, `
                + `timeline_messages=${timeline.messages.length}, past_messages=${pastMessages.length}, `
                + `unique_messages=${rawMessages.length}`,
              );
            } else {
              rawMessages = await this.fetchTimeline(group.id);
              currentPageMessages = rawMessages;
            }

            const previousIds = this.groupMessageIds.get(group.id) || new Set();
            const currentIds = new Set(
              currentPageMessages.map(rawMessage => String(rawMessage.id ?? rawMessage.message_id)),
            );
            const newMessages = rawMessages.filter(rawMessage => !previousIds.has(String(rawMessage.id ?? rawMessage.message_id)));
            const failedIds = new Set();
            fetched += newMessages.length;
            for (const rawMessage of newMessages.reverse()) {
              const rawId = String(rawMessage.id ?? rawMessage.message_id);
              const message = this.normalizeMessage(rawMessage, group);
              if (!message) {
                previousIds.add(rawId);
                continue;
              }
              const result = await this.processMessage(message, sendPush);
              stored += result.isNew ? 1 : 0;
              pushed += result.pushed ? 1 : 0;
              if (result.processed) previousIds.add(rawId);
              else failedIds.add(rawId);
            }
            this.groupMessageIds.set(
              group.id,
              new Set([...currentIds].filter(id => !failedIds.has(id))),
            );
            if (shouldBackfillHistory) {
              if (failedIds.size > 0) {
                throw new Error(
                  `Nogi history persistence failed for group ${group.id}: ${failedIds.size} message(s)`,
                );
              }
              this.backfilledGroupIds.add(group.id);
            }
          }

          this.hasCompletedInitialPoll = true;
          if (groups.every(group => this.backfilledGroupIds.has(group.id))) {
            this.historyBackfillReason = null;
          }
          await this.persistStorageState();
          console.log(`Nogi browser monitor poll complete: groups=${groups.length}, fetched=${fetched}, stored=${stored}, pushed=${pushed}`);
      })();

      if (timeoutPromise) await Promise.race([polling, timeoutPromise]);
      else await polling;
    } finally {
      clearTimeout(pollTimeout);
    }
  }

  async loadStorageState() {
    try {
      return (await readBrowserSession(this.storageStateFile)).state;
    } catch (error) {
      if (error.code !== 'ENOENT') console.warn('Nogi browser state could not be loaded:', error.message);
      return null;
    }
  }

  async loadAccessTokenState() {
    try {
      const state = JSON.parse(await fs.readFile(this.accessTokenStateFile, 'utf8'));
      const token = String(state.accessToken || '').trim();
      const expiresAt = tokenExpiry(token);
      if (!token || (expiresAt && expiresAt.getTime() <= Date.now() + 30_000)) return '';
      return token;
    } catch (error) {
      if (error.code !== 'ENOENT') console.warn('Nogi access token state could not be loaded:', error.message);
      return '';
    }
  }

  async persistAccessToken() {
    if (!this.accessToken || this.accessToken === this.lastPersistedAccessToken) return;
    const token = this.accessToken;
    this.lastPersistedAccessToken = token;
    try {
      const directory = path.dirname(this.accessTokenStateFile);
      await fs.mkdir(directory, { recursive: true });
      const tempFile = `${this.accessTokenStateFile}.tmp-${process.pid}-${Date.now()}`;
      await fs.writeFile(tempFile, JSON.stringify({
        accessToken: token,
        savedAt: Date.now(),
      }), { mode: 0o600 });
      await fs.rename(tempFile, this.accessTokenStateFile);
    } catch (error) {
      if (this.lastPersistedAccessToken === token) this.lastPersistedAccessToken = '';
      console.warn('Nogi access token state could not be persisted:', error.message);
    }
  }

  async persistStorageState() {
    if (!this.context || this.suspendStoragePersistence) return this.statePersistPromise;

    const persist = async () => {
      if (!this.context || this.suspendStoragePersistence) return;
      try {
        const state = await this.context.storageState({ indexedDB: true });
        if (this.suspendStoragePersistence) return;
        const serializedState = JSON.stringify(state);
        const { version } = await this.writeStorageState(serializedState);
        this.lastPersistedStorageVersion = version;
      } catch (error) {
        console.warn('Nogi browser state could not be persisted:', error.message);
      }
    };

    this.statePersistPromise = this.statePersistPromise.then(persist, persist);
    return this.statePersistPromise;
  }

  async writeStorageState(serializedState) {
    const version = sessionVersion(serializedState);
    this.lastPersistedStorageVersion = version;
    await atomicWritePrivateFile(this.storageStateFile, serializedState);
    return { version };
  }

  async startSessionFileWatcher() {
    if (this.sessionFileWatcher) return;

    const directory = path.dirname(this.storageStateFile);
    const stateFileName = path.basename(this.storageStateFile);
    const { uploadStatusFilePath } = browserSessionPaths(this.storageStateFile);
    const uploadStatusFileName = path.basename(uploadStatusFilePath);
    await fs.mkdir(directory, { recursive: true });

    // Establish a baseline before subscribing. The reconciliation pass below
    // then catches changes in the small gap between this read and watch().
    try {
      const initialSession = await readBrowserSession(this.storageStateFile);
      this.lastPersistedStorageVersion = initialSession.version;
      const initialUpload = await readJsonIfExists(uploadStatusFilePath).catch(() => null);
      if (initialUpload?.requestId && initialUpload.version === initialSession.version) {
        this.lastHandledUploadRequestId = initialUpload.requestId;
      }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }

    this.sessionFileWatcher = watch(directory, { persistent: false })
      .on('change', (eventType, filename) => {
        if (filename && ![stateFileName, uploadStatusFileName].includes(String(filename))) return;
        if (eventType !== 'change' && eventType !== 'rename') return;
        if (this.sessionReloadTimer) clearTimeout(this.sessionReloadTimer);
        this.sessionReloadTimer = setTimeout(() => {
          this.sessionReloadTimer = null;
          this.handleSessionFileChange().catch(error => {
            console.warn('处理会话文件更新失败:', error.message);
          });
        }, 100);
      })
      .on('error', (error) => {
        console.warn('会话文件监听器错误:', error.message);
        this.sessionFileWatcher = null;
      });

    console.log(`开始监听会话文件目录: ${directory}`);
    queueMicrotask(() => {
      this.handleSessionFileChange().catch(error => {
        console.warn('检查待激活会话失败:', error.message);
      });
    });
  }

  async handleSessionFileChange() {
    let loadedSession;
    try {
      loadedSession = await readBrowserSession(this.storageStateFile);
    } catch (error) {
      if (error.code !== 'ENOENT') console.warn('检查会话文件失败:', error.message);
      return;
    }
    const { uploadStatusFilePath } = browserSessionPaths(this.storageStateFile);
    const upload = await readJsonIfExists(uploadStatusFilePath).catch(() => null);
    const matchingUpload = upload?.requestId && upload.version === loadedSession.version
      ? upload
      : null;
    if (matchingUpload?.requestId === this.lastHandledUploadRequestId) return;
    if (!matchingUpload && loadedSession.version === this.lastPersistedStorageVersion) return;
    loadedSession.requestId = matchingUpload?.requestId || null;
    console.log('检测到外部会话文件更新,准备重载浏览器上下文...');
    await this.reloadSession(loadedSession);
  }

  stopSessionFileWatcher() {
    if (this.sessionReloadTimer) {
      clearTimeout(this.sessionReloadTimer);
      this.sessionReloadTimer = null;
    }
    if (this.sessionFileWatcher) {
      this.sessionFileWatcher.close();
      this.sessionFileWatcher = null;
      console.log('停止监听会话文件');
    }
  }

  async reloadSession(loadedSession = null) {
    if (this.isReloadingSession) {
      this.pendingSessionReload = true;
      console.log('会话重载已在进行中,将在完成后处理最新版本');
      return;
    }

    this.isReloadingSession = true;
    const activePolling = this.activePollingPromise;
    if (activePolling) await activePolling.catch(() => {});
    let requestedVersion = loadedSession?.version || null;
    let requestId = loadedSession?.requestId || null;
    try {
      console.log('开始重载浏览器会话...');
      const requestedSession = loadedSession || await readBrowserSession(this.storageStateFile);
      const newStorageState = requestedSession.state;
      requestedVersion = requestedSession.version;
      requestId = requestedSession.requestId || requestId;
      if (requestId) this.lastHandledUploadRequestId = requestId;
      
      if (!newStorageState) {
        console.warn('无法加载新会话文件,保持当前会话');
        return;
      }

      const { activationStatusFilePath } = browserSessionPaths(this.storageStateFile);
      await atomicWritePrivateJson(activationStatusFilePath, {
        requestId,
        version: requestedVersion,
        status: 'activating',
        updatedAt: new Date().toISOString(),
      });

      // Keep the uploaded snapshot in memory and prevent the old context from
      // overwriting it while the browser is being replaced.
      this.suspendStoragePersistence = true;

      console.log('关闭当前浏览器实例...');
      await this.closeBrowser();
      
      this.accessToken = '';
      this.observedTokenAt = 0;
      this.lastFrontendNavigationAt = 0;
      this.browserStartedAt = 0;
      this.lastPersistedAccessToken = '';
      this.refreshPromise = null;

      console.log('使用新会话重新打开浏览器...');
      await this.openBrowser(newStorageState, { sessionActivation: true });
      // Do not let a token persisted from the previous browser session make a
      // newly uploaded session look valid. Activation must observe a request
      // produced by the uploaded website state itself.
      this.accessToken = '';
      this.observedTokenAt = 0;
      await this.refreshFrontendSession({ sessionActivation: true });
      await this.apiRequest(
        `/v2/groups?organization_id=${encodeURIComponent(this.organizationId)}`,
        { retryAuth: false, sessionActivation: true },
      );
      this.resumeAuthentication();
      this.backfilledGroupIds.clear();
      this.historyBackfillReason = 'session_reload';
      console.log('Nogi history backfill scheduled after browser session activation');

      await atomicWritePrivateJson(activationStatusFilePath, {
        requestId,
        version: requestedVersion,
        status: 'active',
        updatedAt: new Date().toISOString(),
      });
      
      console.log(`✓ 浏览器会话已激活并验证: ${requestedVersion}`);
    } catch (error) {
      console.error('重载会话失败:', error.message);
      if (requestedVersion) {
        const { activationStatusFilePath } = browserSessionPaths(this.storageStateFile);
        await atomicWritePrivateJson(activationStatusFilePath, {
          requestId,
          version: requestedVersion,
          status: 'failed',
          updatedAt: new Date().toISOString(),
          error: error.message,
        }).catch(() => {});
      }
      await recordError('monitor.reload_session', error);
      await this.pauseAuthentication(error, {
        request_id: requestId,
        session_version: requestedVersion,
      });
    } finally {
      this.suspendStoragePersistence = false;
      this.isReloadingSession = false;
      this.releaseSessionReloadWaiters();
      if (requestedVersion) await this.persistStorageState();
      if (this.pendingSessionReload) {
        this.pendingSessionReload = false;
        queueMicrotask(() => {
          this.handleSessionFileChange().catch(error => {
            console.warn('处理排队的会话文件更新失败:', error.message);
          });
        });
      }
    }
  }
}

const monitor = new NogiBrowserMonitor();

export { NogiBrowserMonitor };
export default monitor;

if (import.meta.url === `file://${process.argv[1]}`) {
  const { default: mediaServer } = await import('./media-server.js');
  mediaServer.start().then(() => monitor.start()).catch(error => {
    void recordError('monitor.start', error);
    process.exitCode = 1;
  });

  const shutdown = async () => {
    await monitor.stop();
    await mediaServer.stop();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
