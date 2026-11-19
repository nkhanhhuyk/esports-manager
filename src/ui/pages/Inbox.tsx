import { useEffect, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, Gate, PageHeader } from '../components/common';
import { formatDate } from '../../core/date';
import { respondToOffer } from '../../systems/transfers';
import { useRoute, navigate } from '../router';

export function Inbox() {
  const world = useWorld();
  const act = useGame((s) => s.act);
  const notify = useGame((s) => s.notify);
  const route = useRoute();
  const [sel, setSel] = useState<string | undefined>(route.query.m);
  const msgs = [...world.inbox].reverse();
  const m = msgs.find((x) => x.id === sel) ?? msgs[0];
  useEffect(() => {
    if (m && !m.read)
      act((w) => {
        const mm = w.inbox.find((x) => x.id === m.id);
        if (mm) mm.read = true;
      });
  }, [m?.id, m?.read, act]);
  const doAction = (actionId: string) => {
    if (!m) return;
    const offerId = m.payload?.offerId as string | undefined;
    const r = act((w) => {
      const res = offerId ? respondToOffer(w, offerId, actionId === 'accept') : { ok: true, message: 'Done' };
      const mm = w.inbox.find((x) => x.id === m.id);
      if (mm) mm.resolved = actionId;
      return res;
    }, { save: true });
    if (r) notify(r.message, r.ok ? 'ok' : 'err');
  };
  return (
    <div>
      <PageHeader title="Inbox" sub={`${msgs.filter((x) => !x.read).length} unread`} right={<button className="btn ghost sm" onClick={() => act((w) => w.inbox.forEach((x) => (x.read = true)))}>Mark all read</button>} />
      <div className="grid2">
        <Card title="Messages">
          <div style={{ maxHeight: 600, overflowY: 'auto' }}>
            {msgs.map((x) => (
              <div key={x.id} className={`inbox-item ${x.read ? '' : 'unread'}`} onClick={() => setSel(x.id)} style={{ background: x.id === m?.id ? 'var(--hl)' : undefined }}>
                <div>{x.subject}</div>
                <div className="muted small">
                  {x.from} · {formatDate(x.day)}
                  {x.actions && !x.resolved ? ' · action required' : ''}
                </div>
              </div>
            ))}
            {!msgs.length && <div className="muted">Empty.</div>}
          </div>
        </Card>
        {m && (
          <Card title={m.subject}>
            <div className="muted small" style={{ marginBottom: 8 }}>
              From {m.from} · {formatDate(m.day)}
            </div>
            <p style={{ whiteSpace: 'pre-wrap' }}>{m.body}</p>
            {m.payload?.fixtureId && (
              <button className="btn ghost sm" onClick={() => navigate(`/match/${m.payload!.fixtureId}`)}>
                View match
              </button>
            )}
            {m.actions && !m.resolved && (
              <Gate world={world} perm="transfers">
              <div className="row" style={{ marginTop: 10 }}>
                {m.actions.map((a) => (
                  <button key={a.id} className={`btn ${a.id === 'reject' ? 'ghost' : ''}`} onClick={() => doAction(a.id)}>
                    {a.label}
                  </button>
                ))}
              </div>
              </Gate>
            )}
            {m.resolved && <div className="tag">Resolved: {m.resolved}</div>}
          </Card>
        )}
      </div>
    </div>
  );
}
