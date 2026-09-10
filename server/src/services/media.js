import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { recordError } from './error-log.js';

const DEFAULT_STORAGE_DIR = '/app/nogi-media';
const MAX_MEDIA_BYTES = Number.parseInt(process.env.MEDIA_MAX_BYTES || `${100 * 1024 * 1024}`, 10);

// Archived files are addressed by content, not by message. Members routinely
// reuse the same call image across many calls, so identical bytes are stored
// once and every message row points at that shared object.
const OBJECTS_DIR = 'objects';
const TEMP_DIR = '.tmp';
const URL_CACHE_LIMIT = 1000;

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function detectImageExtension(buffer) {
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'jpg';
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(PNG_SIGNATURE)) return 'png';
  if (buffer.length >= 6) {
    const header = buffer.subarray(0, 6).toString('ascii');
    if (header === 'GIF87a' || header === 'GIF89a') return 'gif';
  }
  if (
    buffer.length >= 12
    && buffer.subarray(0, 4).toString('ascii') === 'RIFF'
    && buffer.subarray(8, 12).toString('ascii') === 'WEBP'
  ) {
    return 'webp';
  }
  return null;
}

function extensionFor(url, kind, type) {
  try {
    const extension = path.extname(new URL(url).pathname).slice(1).toLowerCase();
    if (/^[a-z0-9]{2,5}$/.test(extension)) return extension;
  } catch {
    // Fall through to a type-based extension.
  }

  if (kind === 'thumbnail' || kind === 'phone_image') return 'jpg';
  return { image: 'jpg', audio: 'm4a', video: 'mp4' }[type] || 'bin';
}

function isImageKind(kind, type) {
  return kind === 'thumbnail' || kind === 'phone_image' || type === 'image';
}

class MediaArchive {
  constructor({ fetchImpl = globalThis.fetch, storageDir = process.env.MEDIA_STORAGE_DIR || DEFAULT_STORAGE_DIR } = {}) {
    this.fetchImpl = fetchImpl;
    this.storageDir = path.resolve(storageDir);
    // A URL identifies stable bytes on the official CDN, so remembering the
    // object per URL avoids re-fetching a member's call image for every call.
    this.urlObjects = new Map();
  }

  async ensureStorage() {
    await fsp.mkdir(this.storageDir, { recursive: true });
  }

  objectPath(digest, extension) {
    return path.join(this.storageDir, OBJECTS_DIR, `${digest}.${extension}`);
  }

  async download(url, kind, type) {
    if (!url || !url.startsWith('https://')) throw new Error('media URL must use HTTPS');
    await this.ensureStorage();

    const cachedUrl = this.urlObjects.get(url);
    if (cachedUrl && await this.storedFile(cachedUrl)) return cachedUrl;

    const tempDir = path.join(this.storageDir, TEMP_DIR);
    await fsp.mkdir(tempDir, { recursive: true });
    const tempPath = path.join(tempDir, `${process.pid}-${Date.now()}-${randomUUID()}`);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 90_000);
    const digest = createHash('sha256');
    let output;
    try {
      const response = await this.fetchImpl(url, { redirect: 'follow', signal: controller.signal });
      if (!response.ok || !response.body) throw new Error(`media download returned HTTP ${response.status}`);

      const contentLength = Number.parseInt(response.headers.get('content-length') || '', 10);
      if (Number.isFinite(contentLength) && contentLength > MAX_MEDIA_BYTES) {
        throw new Error(`media exceeds ${MAX_MEDIA_BYTES} byte limit`);
      }

      output = fs.createWriteStream(tempPath, { flags: 'wx' });
      const reader = response.body.getReader();
      let total = 0;
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = Buffer.from(value);
          total += chunk.byteLength;
          if (total > MAX_MEDIA_BYTES) throw new Error(`media exceeds ${MAX_MEDIA_BYTES} byte limit`);
          digest.update(chunk);
          if (!output.write(chunk)) await once(output, 'drain');
        }
      } finally {
        reader.releaseLock();
      }
      output.end();
      await once(output, 'finish');
      output = null;

      const extension = (isImageKind(kind, type) ? await this.sniffImageExtension(tempPath) : null)
        || extensionFor(url, kind, type);
      const objectPath = this.objectPath(digest.digest('hex'), extension);

      if (await this.storedFile(objectPath)) {
        await fsp.rm(tempPath, { force: true });
      } else {
        await fsp.mkdir(path.dirname(objectPath), { recursive: true });
        await fsp.rename(tempPath, objectPath);
      }

      if (this.urlObjects.size >= URL_CACHE_LIMIT) this.urlObjects.clear();
      this.urlObjects.set(url, objectPath);
      return objectPath;
    } catch (error) {
      output?.destroy();
      await fsp.rm(tempPath, { force: true }).catch(() => {});
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }

  async sniffImageExtension(filePath) {
    let handle;
    try {
      handle = await fsp.open(filePath, 'r');
      const buffer = Buffer.alloc(16);
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
      return detectImageExtension(buffer.subarray(0, bytesRead));
    } catch {
      return null;
    } finally {
      await handle?.close().catch(() => {});
    }
  }

  async archiveMessage(message) {
    const result = { mediaLocalPath: null, thumbnailLocalPath: null, phoneImageLocalPath: null };
    if (!message) return result;

    if (message.type !== 'text' && message.media_url) {
      result.mediaLocalPath = await this.archiveOne(message.media_url, 'media', message.type, message.id);
    }
    if (message.type !== 'text' && message.thumbnail_url) {
      result.thumbnailLocalPath = await this.archiveOne(message.thumbnail_url, 'thumbnail', message.type, message.id);
    }
    if (message.type === 'audio' && message.incoming_call_from && message.phone_image_url) {
      result.phoneImageLocalPath = await this.archiveOne(message.phone_image_url, 'phone_image', 'image', message.id);
    }
    return result;
  }

  async archiveOne(url, kind, type, messageId) {
    try {
      return await this.download(url, kind, type);
    } catch (error) {
      await recordError('media.archive', error, { message_id: messageId, kind, url });
      return null;
    }
  }

  publicUrl(messageId, kind = 'media') {
    const baseUrl = (
      process.env.PUBLIC_MEDIA_BASE_URL
      || process.env.PUBLIC_BASE_URL
      || 'https://nogi-relay.fly.dev'
    ).replace(/\/$/, '');
    return `${baseUrl}/v1/messages/${encodeURIComponent(messageId)}/media/${kind}`;
  }

  isSafeStoredPath(filePath) {
    if (!filePath) return false;
    const relative = path.relative(this.storageDir, path.resolve(filePath));
    return relative && !relative.startsWith('..') && !path.isAbsolute(relative);
  }

  async storedFile(filePath) {
    if (!this.isSafeStoredPath(filePath)) return null;
    try {
      const stat = await fsp.stat(filePath);
      return stat.isFile() && stat.size > 0 ? filePath : null;
    } catch {
      return null;
    }
  }
}

const mediaArchive = new MediaArchive();

export { MediaArchive };
export default mediaArchive;
