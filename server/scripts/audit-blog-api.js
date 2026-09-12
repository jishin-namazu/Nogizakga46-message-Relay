import { parseJsonp } from '../src/monitor/blog-monitor.js';

const BLOG_API = 'https://www.nogizaka46.com/s/n46/api/list/blog';
const MEMBER_API = 'https://www.nogizaka46.com/s/n46/api/list/member';
const PAGE_SIZE = Math.max(Number.parseInt(process.env.NOGI_BLOG_AUDIT_PAGE_SIZE || '500', 10), 1);
const CONCURRENCY = Math.min(
  Math.max(Number.parseInt(process.env.NOGI_BLOG_AUDIT_CONCURRENCY || '3', 10), 1),
  6,
);

async function requestJsonp(endpoint, params) {
  const url = new URL(endpoint);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
  const response = await fetch(url, {
    headers: {
      Accept: 'application/json',
      Referer: 'https://www.nogizaka46.com/s/n46/diary/MEMBER',
      'User-Agent': 'Nogi Relay blog completeness audit',
    },
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error(`${url.pathname} returned ${response.status}`);
  return parseJsonp(await response.text());
}

async function mapConcurrent(values, mapper) {
  const results = new Array(values.length);
  let cursor = 0;
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, values.length) }, async () => {
    while (cursor < values.length) {
      const index = cursor;
      cursor += 1;
      results[index] = await mapper(values[index], index);
    }
  }));
  return results;
}

const initialCountPayload = await requestJsonp(BLOG_API, { rw: 0, st: 0 });
const initialCount = Number.parseInt(initialCountPayload.count || '0', 10);
if (!Number.isFinite(initialCount) || initialCount < 0) throw new Error('Invalid initial blog count');
const offsets = Array.from({ length: Math.ceil(initialCount / PAGE_SIZE) }, (_, index) => index * PAGE_SIZE);

console.log(`Auditing ${initialCount} BLOG rows in ${offsets.length} pages (size=${PAGE_SIZE}, concurrency=${CONCURRENCY})`);
const pages = await mapConcurrent(offsets, async (offset, index) => {
  const payload = await requestJsonp(BLOG_API, { rw: PAGE_SIZE, st: offset });
  const data = Array.isArray(payload.data) ? payload.data : [];
  console.log(`BLOG page ${index + 1}/${offsets.length}: offset=${offset}, rows=${data.length}`);
  return { count: Number.parseInt(payload.count || '0', 10), data };
});

const finalCountPayload = await requestJsonp(BLOG_API, { rw: 0, st: 0 });
const finalCount = Number.parseInt(finalCountPayload.count || '0', 10);
if (initialCount !== finalCount || pages.some(page => page.count !== finalCount)) {
  throw new Error(`BLOG source changed during audit (${initialCount} -> ${finalCount}); rerun for a stable snapshot`);
}

const rows = pages.flatMap(page => page.data).slice(0, finalCount);
const ids = rows.map(row => String(row.code || '').trim());
const uniqueIds = new Set(ids);
const duplicateIds = [...new Set(ids.filter((id, index) => !id || ids.indexOf(id) !== index))];
const missingRequired = rows.filter(row => !row.code || !row.arti_code || !row.name || !row.date || !row.link);
const missingBody = rows.filter(row => typeof row.text !== 'string' || !row.text.trim());
const commentFields = rows.filter(row => Object.keys(row).some(key => /comment/i.test(key)));
const dates = rows.map(row => String(row.date || ''));
const outOfOrder = dates.filter((date, index) => index > 0 && date > dates[index - 1]);

const memberPayload = await requestJsonp(MEMBER_API, { rw: 500, st: 0 });
const members = (Array.isArray(memberPayload.data) ? memberPayload.data : [])
  .filter(member => String(member.code) !== '10001');
const globalMemberCounts = new Map();
for (const row of rows) {
  const code = String(row.arti_code || '').trim();
  globalMemberCounts.set(code, (globalMemberCounts.get(code) || 0) + 1);
}
const memberCodes = [...new Set([
  ...members.map(member => String(member.code || '').trim()),
  ...globalMemberCounts.keys(),
].filter(Boolean))];
const filteredCounts = await mapConcurrent(memberCodes, async code => {
  const payload = await requestJsonp(BLOG_API, { rw: 0, st: 0, ct: code });
  return [code, Number.parseInt(payload.count || '0', 10) || 0];
});
const memberMismatches = filteredCounts.filter(([code, count]) => count !== (globalMemberCounts.get(code) || 0));
const filteredTotal = filteredCounts.reduce((sum, [, count]) => sum + count, 0);

const summary = {
  declaredCount: finalCount,
  returnedRows: rows.length,
  uniqueIds: uniqueIds.size,
  duplicateIds,
  missingRequired: missingRequired.length,
  missingBody: missingBody.length,
  rowsWithCommentFields: commentFields.length,
  outOfOrder: outOfOrder.length,
  memberDirectoryRows: members.length,
  memberCodesWithBlogs: globalMemberCounts.size,
  filteredMemberTotal: filteredTotal,
  memberMismatches,
  newest: rows[0] ? { id: rows[0].code, date: rows[0].date, member: rows[0].name } : null,
  oldest: rows.at(-1) ? { id: rows.at(-1).code, date: rows.at(-1).date, member: rows.at(-1).name } : null,
};
console.log(JSON.stringify(summary, null, 2));

if (
  rows.length !== finalCount
  || uniqueIds.size !== finalCount
  || duplicateIds.length
  || missingRequired.length
  || missingBody.length
  || commentFields.length
  || outOfOrder.length
  || filteredTotal !== finalCount
  || memberMismatches.length
) {
  process.exitCode = 1;
}
