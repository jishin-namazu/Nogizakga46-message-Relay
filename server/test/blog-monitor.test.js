import assert from 'node:assert/strict';
import test from 'node:test';

const { BlogMonitor, normalizeBlogPost, parseJsonp } = await import('../src/monitor/blog-monitor.js');

test('parses the official JSONP blog response without comments', () => {
  const payload = parseJsonp('res({"count":"1","data":[{"code":"104835"}]});');
  assert.equal(payload.count, '1');
  assert.equal(payload.data[0].code, '104835');

  const post = normalizeBlogPost({
    code: '104835',
    title: 'ヒグラシの声がする',
    date: '2026/09/12 20:37:40',
    arti_code: '63107',
    artist_img: 'https://www.nogizaka46.com/member.jpg',
    img: 'https://www.nogizaka46.com/blog.jpg',
    link: 'https://www.nogizaka46.com/s/n46/diary/detail/104835',
    name: '瀬戸口 心月',
    text: '<p>正文</p>',
    comments: [{ text: '対象外' }],
  });
  assert.deepEqual(Object.keys(post).sort(), [
    'id', 'image_url', 'member_avatar_url', 'member_id', 'member_name',
    'post_url', 'published_at', 'title',
  ]);
  assert.equal(post.published_at, '2026-09-12T20:37:40+09:00');
});

test('first poll establishes a baseline and later polls push only new ids', async () => {
  const rows = new Map();
  const pushed = [];
  let page = [
    { id: '2', title: 'two' },
    { id: '1', title: 'one' },
  ];
  let headId = null;
  const store = {
    getState: async () => headId,
    setState: async (_key, value) => { headId = value; },
    existingIds: async ids => new Set(ids.filter(id => rows.has(id))),
    savePost: async (post, notificationSuppressed) => {
      const isNew = !rows.has(post.id);
      const saved = isNew
        ? { ...post, notification_suppressed: notificationSuppressed }
        : { ...rows.get(post.id), ...post };
      rows.set(post.id, saved);
      return { post: saved, isNew };
    },
    pendingNotifications: async () => [...rows.values()].filter(post => !post.notification_suppressed && !post.attempted),
    markNotificationAttempted: async id => {
      rows.get(id).attempted = true;
    },
  };
  const monitor = new BlogMonitor({
    store,
    pusher: { pushBlogPost: async post => pushed.push(post.id) },
    fetchPage: async ({ limit }) => ({ total: page.length, posts: limit === 1 ? page.slice(0, 1) : page }),
  });

  const baseline = await monitor.poll();
  assert.equal(baseline.baseline, true);
  assert.deepEqual(pushed, []);

  page = [{ id: '3', title: 'three' }, ...page];
  const update = await monitor.poll();
  assert.equal(update.baseline, false);
  assert.deepEqual(pushed, ['3']);
  assert.equal(rows.get('3').attempted, true);
});

test('a partial catch-up keeps the old boundary and resumes past rows saved before the failure', async () => {
  const rows = new Map([['old', { id: 'old', notification_suppressed: true }]]);
  const pushed = [];
  let headId = 'old';
  let failSecondPage = true;
  const source = [
    { id: 'new-3' },
    { id: 'new-2' },
    { id: 'new-1' },
    { id: 'old' },
    { id: 'older' },
  ];
  const store = {
    getState: async () => headId,
    setState: async (_key, value) => { headId = value; },
    existingIds: async ids => new Set(ids.filter(id => rows.has(id))),
    savePost: async (post, suppressed) => {
      const isNew = !rows.has(post.id);
      if (isNew) rows.set(post.id, { ...post, notification_suppressed: suppressed });
      return { post: rows.get(post.id), isNew };
    },
    pendingNotifications: async () => [...rows.values()].filter(post => !post.notification_suppressed && !post.attempted),
    markNotificationAttempted: async id => { rows.get(id).attempted = true; },
  };
  const monitor = new BlogMonitor({
    store,
    pusher: { pushBlogPost: async post => pushed.push(post.id) },
    fetchPage: async ({ offset, limit }) => {
      if (failSecondPage && offset === 2) throw new Error('temporary source failure');
      return { total: source.length, posts: source.slice(offset, offset + limit) };
    },
  });
  monitor.pageSize = 2;

  await assert.rejects(monitor.poll(), /temporary source failure/);
  assert.equal(headId, 'old');
  assert.deepEqual(pushed, []);

  failSecondPage = false;
  await monitor.poll();
  assert.equal(headId, 'new-3');
  assert.deepEqual(new Set(pushed), new Set(['new-1', 'new-2', 'new-3']));
});
