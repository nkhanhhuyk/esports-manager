import type { InboxMessage, NewsItem, World } from '../model/types';
import { nextId } from '../model/ids';

const MAX_NEWS = 600;
const MAX_INBOX = 300;

export function addNews(world: World, item: Omit<NewsItem, 'id' | 'day'>): NewsItem {
  const n: NewsItem = { id: nextId(world, 'n'), day: world.day, ...item };
  world.news.push(n);
  if (world.news.length > MAX_NEWS) {
    // Drop the least important old items first.
    world.news.sort((a, b) => b.day - a.day || b.importance - a.importance);
    world.news.length = MAX_NEWS;
    world.news.reverse();
  }
  return n;
}

export function addInbox(world: World, msg: Omit<InboxMessage, 'id' | 'day' | 'read'>): InboxMessage {
  const m: InboxMessage = { id: nextId(world, 'm'), day: world.day, read: false, ...msg };
  world.inbox.push(m);
  if (world.inbox.length > MAX_INBOX) {
    const unresolvedActionable = (x: InboxMessage) => x.actions && !x.resolved;
    const keep = world.inbox.filter(unresolvedActionable);
    const rest = world.inbox.filter((x) => !unresolvedActionable(x)).slice(-(MAX_INBOX - keep.length));
    world.inbox = [...keep, ...rest].sort((a, b) => a.day - b.day);
  }
  return m;
}

/** Is this team controlled by the user (club divisions + national-team job)? */
export function isUserTeam(world: World, teamId: string | null | undefined): boolean {
  if (!teamId) return false;
  const t = world.teams[teamId];
  if (!t) return false;
  if (t.orgId === world.meta.userOrgId) return true;
  const job = world.meta.nationalJob;
  return !!job && !!t.national && t.national === job.country && t.titleId === job.titleId;
}
