import assert from 'node:assert/strict';
import test from 'node:test';

const { NogiBrowserMonitor } = await import('../src/monitor/nogi-browser.js');
const { setErrorLogDbWriter } = await import('../src/services/error-log.js');
const { pool } = await import('../src/db/index.js');

setErrorLogDbWriter(null);
test.after(() => pool.end());

test('follows timeline continuation until the API returns no next cursor', async () => {
  const monitor = new NogiBrowserMonitor();
  const requests = [];
  const responses = [
    { messages: [{ id: '3' }, { id: '2' }], continuation: 'next+/=' },
    { messages: [{ id: '1' }], continuation: 'last cursor' },
    { messages: [{ id: '0' }], continuation: null },
  ];
  monitor.apiRequest = async pathname => {
    requests.push(pathname);
    return responses.shift();
  };

  const result = await monitor.fetchAllTimeline(47);

  assert.deepEqual(result.messages.map(message => message.id), ['3', '2', '1', '0']);
  assert.deepEqual(result.firstPageMessages.map(message => message.id), ['3', '2']);
  assert.equal(result.pageCount, 3);
  assert.equal(
    requests[0],
    '/v2/groups/47/timeline?count=200&order=desc&clear_unread=false',
  );
  assert.equal(
    requests[1],
    '/v2/groups/47/timeline?continuation=next%2B%2F%3D&clear_unread=false',
  );
  assert.equal(
    requests[2],
    '/v2/groups/47/timeline?continuation=last+cursor&clear_unread=false',
  );
});

test('fails safely if the API repeats a timeline continuation cursor', async () => {
  const monitor = new NogiBrowserMonitor();
  monitor.apiRequest = async pathname => {
    if (pathname.includes('count=200')) {
      return { messages: [], continuation: 'repeated' };
    }
    return { messages: [], continuation: 'repeated' };
  };

  await assert.rejects(
    monitor.fetchAllTimeline(47),
    /continuation loop detected for group 47/,
  );
});

test('does not push a message that already exists in storage', async () => {
  let pushCount = 0;
  const monitor = new NogiBrowserMonitor({
    messageStore: {
      async saveMessage(message) {
        return { message, isNew: false };
      },
    },
    pusher: {
      async pushMessage() {
        pushCount += 1;
      },
    },
  });

  const result = await monitor.processMessage({ id: 'existing' }, true);

  assert.equal(result.isNew, false);
  assert.equal(result.pushed, false);
  assert.equal(pushCount, 0);
});

test('initial poll imports past messages and every timeline page without pushes', async () => {
  const savedIds = [];
  let pushCount = 0;
  let pastFetchCount = 0;
  let fullTimelineFetchCount = 0;
  const monitor = new NogiBrowserMonitor({
    messageStore: {
      async saveMessage(message) {
        savedIds.push(message.id);
        return { message, isNew: true };
      },
    },
    pusher: {
      async pushMessage() {
        pushCount += 1;
      },
    },
  });
  monitor.persistStorageState = async () => {};
  monitor.resolveGroups = async () => [{
    id: 47,
    name: 'Member 47',
    phone_image: null,
    thumbnail: null,
  }];
  monitor.fetchPastMessages = async () => {
    pastFetchCount += 1;
    return [
      { id: '1', type: 'text', published_at: '2026-01-02T00:00:00Z' },
      { id: '0', type: 'text', published_at: '2026-01-01T00:00:00Z' },
    ];
  };
  monitor.fetchAllTimeline = async () => {
    fullTimelineFetchCount += 1;
    const firstPageMessages = [
      { id: '3', type: 'text', published_at: '2026-01-04T00:00:00Z' },
      { id: '2', type: 'text', published_at: '2026-01-03T00:00:00Z' },
    ];
    return {
      messages: [...firstPageMessages, {
        id: '1',
        type: 'text',
        published_at: '2026-01-02T00:00:00Z',
      }],
      firstPageMessages,
      pageCount: 2,
    };
  };

  await monitor.poll();

  assert.equal(pastFetchCount, 1);
  assert.equal(fullTimelineFetchCount, 1);
  assert.deepEqual(savedIds, ['0', '1', '2', '3']);
  assert.equal(pushCount, 0);
  assert.equal(monitor.hasCompletedInitialPoll, true);
  assert.deepEqual([...monitor.groupMessageIds.get(47)], ['3', '2']);
});
