import assert from 'node:assert/strict';
import test from 'node:test';

const { parseMediaRequest } = await import('../src/monitor/media-server.js');
const { pool } = await import('../src/db/index.js');

test.after(() => pool.end());

test('accepts every media kind the relay URL generator emits', () => {
  assert.deepEqual(parseMediaRequest('/v1/messages/abc/media/media'), { messageId: 'abc', kind: 'media' });
  assert.deepEqual(parseMediaRequest('/v1/messages/abc/media/thumbnail'), { messageId: 'abc', kind: 'thumbnail' });
  // Regression: publicUrl(..., 'phone_image') produced this path but the media
  // server only matched media|thumbnail, so incoming-call backgrounds 404'd.
  assert.deepEqual(parseMediaRequest('/v1/messages/abc/media/phone_image'), {
    messageId: 'abc',
    kind: 'phone_image',
  });
});

test('rejects unknown kinds and non-media paths', () => {
  assert.equal(parseMediaRequest('/v1/messages/abc/media/unknown'), null);
  assert.equal(parseMediaRequest('/v1/messages/abc/media'), null);
  assert.equal(parseMediaRequest('/v1/messages/abc/media/media/extra'), null);
  assert.equal(parseMediaRequest('/v1/messages/abc/media/phone_image%2F..'), null);
  assert.equal(parseMediaRequest('/health'), null);
});

test('decodes url-encoded message ids', () => {
  assert.deepEqual(parseMediaRequest('/v1/messages/a%2Fb/media/media'), { messageId: 'a/b', kind: 'media' });
});
