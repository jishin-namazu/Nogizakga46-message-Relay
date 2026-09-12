import blogService from '../services/blog.js';
import pushService from '../services/push.js';
import { recordError } from '../services/error-log.js';

const BLOG_API_URL = 'https://www.nogizaka46.com/s/n46/api/list/blog';
const DEFAULT_PAGE_SIZE = 100;

export function parseJsonp(body) {
  const trimmed = String(body || '').trim();
  const match = /^res\((.*)\);?$/s.exec(trimmed);
  if (!match) throw new Error('Unexpected blog API response');
  return JSON.parse(match[1]);
}

export function normalizeBlogPost(value) {
  const id = String(value?.code || '').trim();
  if (!id) throw new Error('Blog post is missing code');
  const publishedAt = String(value.date || '').trim();
  return {
    id,
    member_id: String(value.arti_code || '').trim(),
    member_name: String(value.name || '乃木坂46').trim(),
    member_avatar_url: String(value.artist_img || '').trim() || null,
    title: String(value.title || '').trim(),
    image_url: String(value.img || '').trim() || null,
    published_at: publishedAt ? publishedAt.replaceAll('/', '-').replace(' ', 'T') + '+09:00' : null,
    post_url: String(value.link || `https://www.nogizaka46.com/s/n46/diary/detail/${id}`).trim(),
  };
}

export async function fetchBlogPage({ offset = 0, limit = DEFAULT_PAGE_SIZE, fetchImpl = fetch } = {}) {
  const url = new URL(BLOG_API_URL);
  url.searchParams.set('rw', String(limit));
  url.searchParams.set('st', String(offset));
  const response = await fetchImpl(url, {
    headers: {
      Accept: 'application/json',
      Referer: 'https://www.nogizaka46.com/s/n46/diary/MEMBER',
      'User-Agent': 'Nogi Relay blog monitor',
    },
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`Blog API returned ${response.status}`);
  const payload = parseJsonp(await response.text());
  return {
    total: Number.parseInt(payload.count || '0', 10) || 0,
    posts: Array.isArray(payload.data) ? payload.data.map(normalizeBlogPost) : [],
  };
}

export class BlogMonitor {
  constructor({ store = blogService, pusher = pushService, fetchPage = fetchBlogPage } = {}) {
    this.store = store;
    this.pusher = pusher;
    this.fetchPage = fetchPage;
    this.pageSize = Math.max(Number.parseInt(process.env.NOGI_BLOG_PAGE_SIZE || '5', 10), 5);
    this.pollIntervalMs = Math.max(
      Number.parseInt(process.env.NOGI_BLOG_POLL_INTERVAL_SECONDS || '60', 10) * 1000,
      15_000,
    );
    this.running = false;
    this.timer = null;
  }

  async poll() {
    const boundaryId = await this.store.getState('head_id_v1');
    const baseline = !boundaryId;
    let offset = 0;
    let fetched = 0;
    let stored = 0;
    let pushed = 0;
    let newestId = null;
    let expectedCount = null;
    let completed = false;

    while (true) {
      const page = await this.fetchPage({ offset, limit: this.pageSize });
      if (expectedCount === null) expectedCount = page.total;
      if (!page.posts.length) {
        if (offset >= page.total) completed = true;
        else throw new Error('Blog API returned an empty page before the declared end');
        break;
      }
      if (!newestId) newestId = page.posts[0]?.id || null;
      fetched += page.posts.length;
      const boundaryReached = !baseline && page.posts.some(post => post.id === boundaryId);
      const existing = await this.store.existingIds(page.posts.map(post => post.id));

      for (const post of [...page.posts].reverse()) {
        if (existing.has(post.id)) continue;
        const result = await this.store.savePost(post, baseline);
        if (!result.isNew) continue;
        stored += 1;
      }

      if ((!baseline && boundaryReached) || offset + page.posts.length >= page.total) {
        completed = true;
        break;
      }
      offset += page.posts.length;
    }

    if (!completed) throw new Error('Blog synchronization did not reach its persisted boundary');
    const finalHead = await this.fetchPage({ offset: 0, limit: 1 });
    if (finalHead.total !== expectedCount || finalHead.posts[0]?.id !== newestId) {
      throw new Error('Blog source changed during synchronization; persisted boundary was not advanced');
    }
    if (newestId) await this.store.setState('head_id_v1', newestId);

    if (!baseline) {
      const pending = await this.store.pendingNotifications();
      for (const post of pending) {
        await this.pusher.pushBlogPost(post);
        await this.store.markNotificationAttempted(post.id);
        pushed += 1;
      }
    }

    console.log(`Nogi blog monitor poll complete: fetched=${fetched}, stored=${stored}, pushed=${pushed}, baseline=${baseline}`);
    return { fetched, stored, pushed, baseline };
  }

  async start() {
    if (this.running) return;
    this.running = true;
    while (this.running) {
      try {
        await this.poll();
      } catch (error) {
        await recordError('blog_monitor.poll', error);
      }
      if (!this.running) break;
      await new Promise(resolve => {
        this.timer = setTimeout(resolve, this.pollIntervalMs);
      });
      this.timer = null;
    }
  }

  async stop() {
    this.running = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
}

export default new BlogMonitor();
