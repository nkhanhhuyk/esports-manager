/**
 * Youth intake (1 December each year) and trials.
 */
import { useGame, useWorld } from '../store';
import { Card, Flag, Gate, PageHeader, PlayerLink, Rating, TeamLink, TitleTag, age } from '../components/common';
import { formatDate } from '../../core/date';
import { formatMoney } from '../../core/math';
import { getTitle } from '../../data/titles/catalogue';
import { potentialRange } from '../../systems/scouting';
import { releaseProspect, signProspect, startTrial, trialCost } from '../../systems/youth';
import { roleName } from '../helpers';
import type { Player, Team, World } from '../../model/types';

function abilityLabel(world: World, p: Player): string {
  const k = world.scouting[p.id] ?? 0;
  if (k >= 100) return `${Math.round(p.ca)}`;
  const w = Math.round((100 - k) * 0.12);
  return `${Math.max(1, Math.round(p.ca) - w)}–${Math.round(p.ca) + w}`;
}

/** Trial controls for a free agent or prospect (used here and on player pages). */
export function TrialButtons({ world, p, team }: { world: World; p: Player; team: Team }) {
  const { act, notify } = useGame.getState();
  const onTrial = p.trial && p.trial.untilDay > world.day;
  if (p.teamId || p.retired) return null;
  if (onTrial) return <span className="tag">on trial {p.trial!.teamId === team.id ? 'with you' : `at ${world.teams[p.trial!.teamId]?.short}`} until {formatDate(p.trial!.untilDay)}</span>;
  return (
    <>
      {[1, 2].map((w) => (
        <button
          key={w}
          className="btn ghost sm"
          title={`Costs ${formatMoney(trialCost(world, team, w))}; reveals his ability fully at the end`}
          onClick={() => {
            const r = act((x) => startTrial(x, team.id, p.id, w));
            if (r) notify(r.message, r.ok ? 'ok' : 'err');
          }}
        >
          Trial {w} wk
        </button>
      ))}
    </>
  );
}

export function YouthPage() {
  const world = useWorld();
  const { act, notify } = useGame.getState();
  const classes = [...(world.youth?.classes ?? [])].reverse();
  const myTeams = new Set(world.meta.userOrgId ? world.orgs[world.meta.userOrgId].teams : []);
  const trials = Object.values(world.players).filter((p) => p.trial && p.trial.untilDay > world.day && myTeams.has(p.trial.teamId));
  return (
    <div>
      <PageHeader title="Youth intake & trials" sub="Every 1 December each academy receives a class of 15–17-year-old prospects. Sign, trial or release them within 21 days; unsigned prospects become free agents." />
      <Gate world={world} perm="transfers">
        {!classes.length && (
          <Card>
            <div className="muted">No intake yet this career — the next one arrives on 1 December. Better academy facilities and an academy coach with a good eye for youth bring bigger, stronger classes.</div>
          </Card>
        )}
        {classes.map((c) => {
          const team = world.teams[c.teamId];
          if (!team) return null;
          const t = getTitle(team.titleId);
          return (
            <Card
              key={`${c.teamId}-${c.season}`}
              title={
                <span>
                  <TitleTag id={team.titleId} /> {c.season} intake — <TeamLink t={team} />
                </span>
              }
              right={<span className="muted small">{c.closed ? 'closed' : `decide by ${formatDate(c.deadline)}`}</span>}
            >
              <div className="dt-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Prospect</th>
                      <th>Age</th>
                      <th>Role</th>
                      <th className="num">Ability</th>
                      <th className="num">Potential</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {c.prospects.map((id) => {
                      const p = world.players[id];
                      if (!p) return null;
                      const [lo, hi] = potentialRange(world, p);
                      const status = c.decisions[id] === 'signed' || p.teamId === c.teamId ? 'signed' : p.teamId ? `joined ${world.teams[p.teamId]?.short}` : c.decisions[id] === 'released' || c.closed ? 'released' : p.trial && p.trial.untilDay > world.day ? `trial until ${formatDate(p.trial.untilDay)}` : 'awaiting decision';
                      const open = !c.closed && !p.teamId && !c.decisions[id];
                      return (
                        <tr key={id}>
                          <td>
                            <Flag cc={p.nat} />
                            <PlayerLink p={p} />
                          </td>
                          <td>{age(world, p.birth)}</td>
                          <td>{roleName(t.id, p.roles[0])}</td>
                          <td className="num">{abilityLabel(world, p)}</td>
                          <td className="num">
                            <Rating v={(lo + hi) / 2} /> <span className="muted small">{lo === hi ? '' : `${lo}–${hi}`}</span>
                          </td>
                          <td className="small">{status}</td>
                          <td>
                            {open && (
                              <span className="row">
                                <button
                                  className="btn sm"
                                  onClick={() => {
                                    const r = act((w) => signProspect(w, id, c.teamId));
                                    if (r) notify(r.message, r.ok ? 'ok' : 'err');
                                  }}
                                >
                                  Sign
                                </button>
                                <TrialButtons world={world} p={p} team={team} />
                                <button className="btn ghost sm" onClick={() => act((w) => releaseProspect(w, id))}>
                                  Release
                                </button>
                              </span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </Card>
          );
        })}
        <Card title="Players on trial">
          {!trials.length && <div className="muted">Nobody is on trial. Invite free agents from their profile (Trial 1 wk / 2 wk) — they are reserved for you and fully scouted by the end.</div>}
          {trials.map((p) => (
            <div key={p.id} className="attr-row">
              <span>
                <PlayerLink p={p} /> <TitleTag id={p.titleId} /> with <TeamLink t={world.teams[p.trial!.teamId]} />
              </span>
              <span className="muted">until {formatDate(p.trial!.untilDay)}</span>
            </div>
          ))}
        </Card>
      </Gate>
    </div>
  );
}
