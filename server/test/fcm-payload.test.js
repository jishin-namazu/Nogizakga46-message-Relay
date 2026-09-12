import assert from 'node:assert/strict';
import test from 'node:test';

const { buildBlogDataPayload, buildDataPayload, pushPayload } = await import('../src/services/firebase.js');

function message(overrides = {}) {
  return {
    id: 'msg-fcm-1',
    member_id: '47',
    member_name: '一ノ瀬 美空',
    member_avatar_url: null,
    phone_image_url: null,
    type: 'text',
    text: null,
    media_url: null,
    thumbnail_url: null,
    duration_seconds: null,
    sent_at: '2026-09-10T07:29:50.138Z',
    incoming_call_from: null,
    ringtone_url: null,
    is_played: false,
    ...overrides,
  };
}

test('carries the inline payload when the encoded data map fits the byte budget', () => {
  const data = buildDataPayload(message({ text: 'あ'.repeat(500) }), true);

  assert.equal(data.message_id, 'msg-fcm-1');
  assert.equal(data.type, 'text');
  assert.equal(typeof data.payload, 'string');
  assert.ok(Buffer.byteLength(JSON.stringify(data), 'utf8') <= 4096);
});

test('drops the inline payload when UTF-8 bytes exceed the cap even though characters fit', () => {
  const long = message({ text: 'あ'.repeat(2000) });
  const raw = JSON.stringify(pushPayload(long));

  // Precondition: the removed character-based guard would have sent this payload.
  assert.ok(raw.length < 3800, 'expected the old length check to pass');
  assert.ok(Buffer.byteLength(raw, 'utf8') > 4096, 'expected the real payload to exceed 4096 bytes');

  const data = buildDataPayload(long, true);

  assert.equal(data.payload, undefined);
  assert.deepEqual(Object.keys(data).sort(), ['message_id', 'type']);
  assert.ok(Buffer.byteLength(JSON.stringify(data), 'utf8') <= 4096);
});

test('honours includePayload=false without ever exceeding the cap', () => {
  const data = buildDataPayload(message({ text: 'あ'.repeat(5000) }), false);

  assert.equal(data.payload, undefined);
  assert.deepEqual(Object.keys(data).sort(), ['message_id', 'type']);
});

test('builds a lightweight blog notification without body HTML', () => {
  const data = buildBlogDataPayload({
    id: '104835',
    member_id: '63107',
    member_name: '瀬戸口 心月',
    title: 'ヒグラシの声がする',
    published_at: '2026-09-12T20:37:40+09:00',
    post_url: 'https://www.nogizaka46.com/s/n46/diary/detail/104835',
  });

  assert.equal(data.type, 'blog');
  assert.equal(data.blog_id, '104835');
  assert.equal(data.text, undefined);
  assert.ok(Buffer.byteLength(JSON.stringify(data), 'utf8') < 4096);
});
