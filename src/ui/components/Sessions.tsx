/**
 * Training timetable (this week as it happened), the manager's weekly template, and scrim intel.
 */
import { RichText } from './RichText';
import type { SessionType, Team, World } from '../../model/types';
import { formatShort } from '../../core/date';
import { DAY_LABEL, defaultTemplate, practiceRooms, SESSION_TYPES, SESSIONS, slotLabel, staffCapacity } from '../../systems/sessions';
import { useGame } from '../store';
import { Card, Stat, TeamLink } from './common';

function cellClass(s: string): string {
  return `tt-cell ${s}`;
}

export function WeekTimetable({ world, team }: { world: World; team: Team }) {
  const wk = team.training.week;
  const self = !team.prefs.delegateTraining && !!team.training.template;
  if (!wk) {
    return (
      <Card title="This week's sessions">
        <div className="muted">The timetable is set every Monday ({self ? 'from your template' : 'by the coaching staff'}). Advance to the next Monday to see it.</div>
      </Card>
    );
  }
  const cancelled = wk.slots.map((s, i) => ({ s, i })).filter((x) => x.s.s === 'cancelled');
  const scrims = wk.slots.filter((s) => s.t === 'scrim' && s.s !== 'cancelled' && s.p);
  return (
    <Card
      title={`This week's sessions (from ${formatShort(wk.start)})`}
      right={
        <span className="muted small">
          planned by {wk.plannedBy === 'manager' ? 'you' : 'the coaches'}
          {wk.taper ? ' · taper before a big match' : ''}
        </span>
      }
    >
      <div className="stats">
        <Stat label="Development" value={`×${wk.devFactor.toFixed(2)}`} sub="from sessions held" />
        <Stat label="Coaching" value={`${wk.coached}/${wk.staffCap}`} sub="coached sessions / capacity" />
        <Stat label="Practice rooms" value={wk.rooms} sub="shared by the organisation" />
        <Stat label="Training load" value={wk.fatigue > 0 ? `+${wk.fatigue}` : wk.fatigue} sub="fatigue this week" />
        <Stat label="Scrims" value={`${scrims.filter((x) => x.w === 1).length}–${scrims.filter((x) => x.w === 0).length}`} sub={`${scrims.length} played · ${wk.slots.filter((x) => x.t === 'scrim' && x.s === 'cancelled').length} cancelled`} />
      </div>
      <div className="dt-scroll">
        <table className="table timetable">
          <thead>
            <tr>
              <th />
              {DAY_LABEL.map((d) => (
                <th key={d}>{d}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {['AM', 'PM'].map((half, h) => (
              <tr key={half}>
                <th>{half}</th>
                {DAY_LABEL.map((_, d) => {
                  const sl = wk.slots[d * 2 + h];
                  const partner = sl.p ? world.teams[sl.p] : undefined;
                  return (
                    <td key={d} className={cellClass(sl.s)} title={sl.r ?? (sl.t === 'match' ? 'Match' : SESSIONS[sl.t].desc)}>
                      <div className="tt-type">{sl.t === 'match' ? 'Match' : SESSIONS[sl.t].short}</div>
                      {partner && (
                        <div className="tt-sub">
                          {sl.internal ? 'internal · ' : 'vs '}
                          <TeamLink t={partner} short />
                          {sl.w !== undefined && <b className={sl.w ? 'pos' : 'neg'}> {sl.w ? 'W' : 'L'}</b>}
                        </div>
                      )}
                      {sl.s === 'cancelled' && <div className="tt-sub neg">cancelled</div>}
                      {sl.s === 'unsupervised' && <div className="tt-sub warn">no coach</div>}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!!cancelled.length && (
        <div className="small" style={{ marginTop: 6 }}>
          <b>Cancelled (no progress from these):</b>
          {cancelled.map(({ s, i }) => (
            <div key={i} className="muted">
              {slotLabel(i)} {s.t === 'match' ? '' : SESSIONS[s.t].label}: <RichText world={world} text={s.r ?? ''} />
            </div>
          ))}
        </div>
      )}
      {wk.slots.some((s) => s.s === 'unsupervised') && <div className="small warn" style={{ marginTop: 4 }}>Some sessions ran without a coach (half value): hire assistant coaches or plan fewer coached sessions.</div>}
    </Card>
  );
}

export function TemplateEditor({ world, team }: { world: World; team: Team }) {
  const { act } = useGame.getState();
  const self = !team.prefs.delegateTraining && !!team.training.template;
  const tpl = team.training.template;
  const rooms = practiceRooms(world.orgs[team.orgId]);
  const cap = staffCapacity(world, team);
  const coached = tpl ? tpl.filter((t) => SESSIONS[t].coached).length : 0;
  return (
    <Card
      title="Weekly timetable"
      right={
        <label className="small">
          <input
            type="checkbox"
            checked={self}
            onChange={(e) =>
              act((w) => {
                const t = w.teams[team.id];
                if (e.target.checked) {
                  t.prefs.delegateTraining = false;
                  t.training.template ??= defaultTemplate(w, t);
                } else t.prefs.delegateTraining = true;
              })
            }
          />{' '}
          I plan the sessions myself
        </label>
      }
    >
      {!self && (
        <div className="muted small">
          The coaching staff plan every week around the development objective, match days (light sessions before a match, review after), the next opponent, fatigue, the patch and big events (taper). Tick the box to set your own weekly timetable instead.
        </div>
      )}
      {self && tpl && (
        <>
          <div className="muted small" style={{ marginBottom: 6 }}>
            Applies from next Monday. On match days the afternoon is the match and only rest, preparation, review or mental sessions can run that morning. Sessions needing a practice room are cancelled when the organisation&apos;s {rooms} room{rooms > 1 ? 's are' : ' is'} full (main teams first). Coached sessions: {coached}/{cap} capacity
            {coached > cap ? ' — the rest run unsupervised at half value' : ''}.
          </div>
          <div className="dt-scroll">
            <table className="table timetable">
              <thead>
                <tr>
                  <th />
                  {DAY_LABEL.map((d) => (
                    <th key={d}>{d}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {['AM', 'PM'].map((half, h) => (
                  <tr key={half}>
                    <th>{half}</th>
                    {DAY_LABEL.map((_, d) => {
                      const i = d * 2 + h;
                      return (
                        <td key={d}>
                          <select
                            aria-label={`${slotLabel(i)} session`}
                            value={tpl[i]}
                            onChange={(e) => act((w) => (w.teams[team.id].training.template![i] = e.target.value as SessionType))}
                          >
                            {SESSION_TYPES.map((s) => (
                              <option key={s} value={s}>
                                {SESSIONS[s].short}
                              </option>
                            ))}
                          </select>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="row" style={{ marginTop: 6 }}>
            <button className="btn ghost sm" onClick={() => act((w) => (w.teams[team.id].training.template = defaultTemplate(w, w.teams[team.id])))}>
              Reset to the coaches&apos; plan
            </button>
          </div>
          <details style={{ marginTop: 6 }}>
            <summary className="small">What each session does</summary>
            {SESSION_TYPES.map((s) => (
              <div key={s} className="small" style={{ padding: '2px 0' }}>
                <b>{SESSIONS[s].label}</b> — {SESSIONS[s].desc}
                {SESSIONS[s].room ? ' Needs a practice room.' : ''}
                {SESSIONS[s].coached ? ' Uses coaching capacity.' : ''}
              </div>
            ))}
          </details>
        </>
      )}
    </Card>
  );
}

export function ScrimIntel({ world, team }: { world: World; team: Team }) {
  const ours = Object.entries(team.training.intel ?? {}).filter(([, until]) => until >= world.day);
  const theirs = Object.values(world.teams).filter((t) => t.titleId === team.titleId && t.id !== team.id && (t.training.intel?.[team.id] ?? -1) >= world.day);
  if (!ours.length && !theirs.length) return null;
  return (
    <Card title="Scrim intel" right={<span className="muted small">scrims teach both sides — a small preparation edge for 35 days if you meet officially</span>}>
      <div className="grid2">
        <div>
          <b className="small">We know about</b>
          {ours.map(([id, until]) => (
            <div key={id} className="attr-row small">
              <TeamLink t={world.teams[id]} />
              <span className="muted">until {formatShort(until)}</span>
            </div>
          ))}
        </div>
        <div>
          <b className="small">They know about us</b>
          {theirs.map((t) => (
            <div key={t.id} className="attr-row small">
              <TeamLink t={t} />
              <span className="muted">until {formatShort(t.training.intel![team.id])}</span>
            </div>
          ))}
        </div>
      </div>
    </Card>
  );
}
