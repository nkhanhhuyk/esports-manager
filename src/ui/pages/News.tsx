import { RichText } from '../components/RichText';
import { useState } from 'react';
import { useWorld } from '../store';
import { Card, PageHeader, TitleTag } from '../components/common';
import { formatDate } from '../../core/date';
import { Link } from '../router';

const KINDS = ['transfer', 'result', 'upset', 'champion', 'retirement', 'prospect', 'patch', 'finance', 'org', 'award', 'world'];

export function NewsPage() {
  const world = useWorld();
  const [kind, setKind] = useState('');
  const [title, setTitle] = useState('');
  const items = [...world.news].filter((n) => (!kind || n.kind === kind) && (!title || n.titleId === title)).sort((a, b) => b.day - a.day || b.importance - a.importance);
  return (
    <div>
      <PageHeader title="News & media" sub="Transfers, upsets, championships, retirements, prospects and patch notes from across the universe." />
      <div className="row" style={{ marginBottom: 10 }}>
        <select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind">
          <option value="">All stories</option>
          {KINDS.map((k) => (
            <option key={k}>{k}</option>
          ))}
        </select>
        <select value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Title">
          <option value="">All titles</option>
          {world.meta.titlesEnabled.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>
      <Card>
        {items.slice(0, 300).map((n) => (
          <div key={n.id} style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
            <div className="row">
              {n.titleId && <TitleTag id={n.titleId} />}
              <span className="tag">{n.kind}</span>
              <b>
                <RichText world={world} text={n.title} refs={n.refs} />
              </b>
              <span className="muted small">{formatDate(n.day)}</span>
            </div>
            <div className="muted">
              <RichText world={world} text={n.body} refs={n.refs} />
            </div>
            <div className="row small">
              {n.refs.map((r) => (
                <Link key={r.type + r.id} to={`/${r.type === 'comp' ? 'comp' : r.type}/${r.id}`}>
                  {r.type === 'player' ? world.players[r.id]?.handle : r.type === 'team' ? world.teams[r.id]?.name : r.type === 'org' ? world.orgs[r.id]?.name : r.type === 'comp' ? world.comps[r.id]?.short : r.id}
                </Link>
              ))}
            </div>
          </div>
        ))}
        {!items.length && <div className="muted">No news yet.</div>}
      </Card>
    </div>
  );
}
