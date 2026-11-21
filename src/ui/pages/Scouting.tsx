import { useMemo } from 'react';
import { useGame, useWorld } from '../store';
import { Card, Flag, PageHeader, PlayerLink, Rating, StaffLink, TeamLink, TitleTag, age } from '../components/common';
import { DataTable } from '../components/DataTable';
import { potentialRange, userScouts } from '../../systems/scouting';
import { navigate } from '../router';
import { userTeams, roleName } from '../helpers';

export function ScoutingPage() {
  const world = useWorld();
  const { act } = useGame.getState();
  const scouts = userScouts(world);
  const titles = new Set(userTeams(world).map((t) => t.titleId));
  const prospects = useMemo(
    () => Object.values(world.players).filter((p) => !p.retired && age(world, p.birth) <= 19 && titles.has(p.titleId) && (world.scouting[p.id] ?? 0) >= 30),
    [world, titles],
  );
  return (
    <div>
      <PageHeader title="Scouting" sub="Potential is hidden — knowledge narrows the estimate. Better scouts judge faster and more accurately." />
      <div className="grid2">
        <Card title="Your scouts">
          {scouts.map((s) => (
            <div key={s.id} className="attr-row">
              <StaffLink s={s} />
              <span>
                ability <Rating v={s.attrs.judgingAbility} /> potential <Rating v={s.attrs.judgingPotential} />
              </span>
            </div>
          ))}
          {!scouts.length && <div className="muted">No scouts employed — hire one from the Staff directory.</div>}
        </Card>
        <Card title="Scouting focus list">
          {world.scoutFocus.map((pid) => {
            const p = world.players[pid];
            if (!p) return null;
            return (
              <div key={pid} className="attr-row">
                <PlayerLink p={p} />
                <span>
                  knowledge {Math.round(world.scouting[pid] ?? 0)}%{' '}
                  <button className="btn ghost sm" onClick={() => act((w) => (w.scoutFocus = w.scoutFocus.filter((x) => x !== pid)))}>
                    ✕
                  </button>
                </span>
              </div>
            );
          })}
          {!world.scoutFocus.length && <div className="muted">Use “Scout” on any player profile to add them here.</div>}
        </Card>
      </div>
      <Card title="Scouted prospects (age ≤ 19) in your titles">
        <DataTable
          id="prospects"
          rows={prospects}
          rowKey={(p) => p.id}
          initialSort={{ key: 'pa', desc: true }}
          onRowClick={(p) => navigate(`/player/${p.id}`)}
          columns={[
            { key: 'h', label: 'Prospect', value: (p) => p.handle, render: (p) => (<span><Flag cc={p.nat} /><PlayerLink p={p} /></span>) },
            { key: 'age', label: 'Age', align: 'right', value: (p) => age(world, p.birth) },
            { key: 't', label: 'Title', value: (p) => p.titleId, render: (p) => <TitleTag id={p.titleId} link={false} /> },
            { key: 'r', label: 'Role', value: (p) => roleName(p.titleId, p.roles[0]) },
            { key: 'club', label: 'Club', value: (p) => (p.teamId ? world.teams[p.teamId]?.name ?? '' : ''), render: (p) => <TeamLink t={p.teamId ? world.teams[p.teamId] : undefined} /> },
            { key: 'ca', label: 'CA', align: 'right', value: (p) => p.ca, render: (p) => <Rating v={p.ca} /> },
            { key: 'pa', label: 'PA est.', align: 'right', value: (p) => potentialRange(world, p)[1], render: (p) => potentialRange(world, p).join('–') },
            { key: 'k', label: 'Knowledge', align: 'right', value: (p) => world.scouting[p.id] ?? 0, render: (p) => `${Math.round(world.scouting[p.id] ?? 0)}%` },
          ]}
        />
      </Card>
    </div>
  );
}
