import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

const { MediaArchive } = await import('../src/services/media.js');
const { setErrorLogDbWriter } = await import('../src/services/error-log.js');

setErrorLogDbWriter(null);

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d]);

function fakeFetch(bytesFor) {
  const calls = [];
  const impl = async url => {
    calls.push(url);
    const bytes = typeof bytesFor === 'function' ? bytesFor(url) : bytesFor;
    let consumed = false;
    return {
      ok: true,
      status: 200,
      headers: { get: () => String(bytes.length) },
      body: {
        getReader: () => ({
          async read() {
            if (consumed) return { done: true, value: undefined };
            consumed = true;
            return { done: false, value: new Uint8Array(bytes) };
          },
          releaseLock() {},
        }),
      },
    };
  };
  impl.calls = calls;
  return impl;
}

async function withArchive(bytesFor, run) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-archive-'));
  try {
    const fetchImpl = fakeFetch(bytesFor);
    const archive = new MediaArchive({ fetchImpl, storageDir: directory });
    await run({ archive, fetchImpl, directory });
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
}

function callMessage(id, phoneImageUrl) {
  return { id, type: 'audio', incoming_call_from: '一ノ瀬 美空', phone_image_url: phoneImageUrl };
}

async function objectFiles(directory) {
  return fs.readdir(path.join(directory, 'objects'));
}

test('stores one object when different calls reuse the same call image bytes', async () => {
  await withArchive(JPEG, async ({ archive, fetchImpl, directory }) => {
    const first = await archive.archiveMessage(callMessage('call-a', 'https://cdn.example.com/a.jpg'));
    const second = await archive.archiveMessage(callMessage('call-b', 'https://cdn.example.com/a.jpg?v=2'));

    assert.ok(first.phoneImageLocalPath);
    assert.equal(second.phoneImageLocalPath, first.phoneImageLocalPath);
    assert.equal((await objectFiles(directory)).length, 1);
    assert.equal((await fs.stat(first.phoneImageLocalPath)).size, JPEG.length);
    assert.equal(fetchImpl.calls.length, 2);
  });
});

test('reuses the object without re-downloading an identical URL', async () => {
  await withArchive(JPEG, async ({ archive, fetchImpl, directory }) => {
    const first = await archive.archiveMessage(callMessage('call-a', 'https://cdn.example.com/same.jpg'));
    const second = await archive.archiveMessage(callMessage('call-b', 'https://cdn.example.com/same.jpg'));

    assert.equal(second.phoneImageLocalPath, first.phoneImageLocalPath);
    assert.equal(fetchImpl.calls.length, 1);
    assert.equal((await objectFiles(directory)).length, 1);
  });
});

test('keeps a separate object for different bytes', async () => {
  const bytesFor = url => (url.includes('png') ? PNG : JPEG);
  await withArchive(bytesFor, async ({ archive, directory }) => {
    const jpeg = await archive.archiveMessage(callMessage('call-a', 'https://cdn.example.com/a.jpg'));
    const png = await archive.archiveMessage(callMessage('call-b', 'https://cdn.example.com/b.png'));

    assert.notEqual(png.phoneImageLocalPath, jpeg.phoneImageLocalPath);
    assert.equal((await objectFiles(directory)).length, 2);
  });
});

test('names image objects from magic bytes rather than the URL', async () => {
  await withArchive(PNG, async ({ archive }) => {
    const result = await archive.archiveMessage(callMessage('call-png', 'https://cdn.example.com/photo'));

    assert.ok(result.phoneImageLocalPath.endsWith('.png'));
  });
});

test('archives media and the call image for the same message', async () => {
  const audio = Buffer.from('ID3-audio-placeholder');
  const bytesFor = url => (url.endsWith('.m4a') ? audio : JPEG);
  await withArchive(bytesFor, async ({ archive }) => {
    const result = await archive.archiveMessage({
      id: 'call-full',
      type: 'audio',
      incoming_call_from: '一ノ瀬 美空',
      media_url: 'https://cdn.example.com/voice.m4a',
      thumbnail_url: null,
      phone_image_url: 'https://cdn.example.com/photo.jpg',
    });

    assert.ok(result.mediaLocalPath.endsWith('.m4a'));
    assert.ok(result.phoneImageLocalPath.endsWith('.jpg'));
    assert.notEqual(result.mediaLocalPath, result.phoneImageLocalPath);
  });
});
