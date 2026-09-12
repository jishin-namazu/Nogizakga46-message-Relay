import db from '../db/index.js';

class BlogService {
  async existingIds(ids) {
    if (!ids.length) return new Set();
    const rows = await db.queryAll('SELECT id FROM blog_posts WHERE id = ANY($1)', [ids]);
    return new Set(rows.map(row => String(row.id)));
  }

  async getState(key) {
    const row = await db.queryOne('SELECT state_value FROM blog_sync_state WHERE state_key = $1', [key]);
    return row?.state_value || null;
  }

  async setState(key, value) {
    await db.query(
      `INSERT INTO blog_sync_state (state_key, state_value, updated_at)
       VALUES ($1, $2, CURRENT_TIMESTAMP)
       ON CONFLICT (state_key) DO UPDATE SET
         state_value = EXCLUDED.state_value,
         updated_at = CURRENT_TIMESTAMP`,
      [key, value],
    );
  }

  async savePost(post, notificationSuppressed = false) {
    const inserted = await db.queryOne(
      `INSERT INTO blog_posts (
         id, member_id, member_name, member_avatar_url, title,
         image_url, published_at, post_url, notification_suppressed
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
       ON CONFLICT (id) DO NOTHING
       RETURNING *`,
      [
        post.id,
        post.member_id,
        post.member_name,
        post.member_avatar_url,
        post.title,
        post.image_url,
        post.published_at,
        post.post_url,
        notificationSuppressed,
      ],
    );
    return { post: inserted || post, isNew: Boolean(inserted) };
  }

  async pendingNotifications(limit = 100) {
    return await db.queryAll(
      `SELECT * FROM blog_posts
       WHERE notification_suppressed = false AND notification_attempted_at IS NULL
       ORDER BY published_at ASC, discovered_at ASC
       LIMIT $1`,
      [limit],
    );
  }

  async markNotificationAttempted(id) {
    await db.query(
      'UPDATE blog_posts SET notification_attempted_at = CURRENT_TIMESTAMP WHERE id = $1',
      [id],
    );
  }
}

export default new BlogService();
