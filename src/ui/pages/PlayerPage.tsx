import { TrialButtons } from './Youth';
import { recallPlayer, restPlayer } from '../../systems/breaks';
import { can } from '../../systems/career';
import { seasonTitle } from '../../systems/availability';
import { RichText } from '../components/RichText';
import { useMemo, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, Flag, Gate, Money, PageHeader, Rating, Stat, Tabs, TeamLink, TitleTag, age, CompLink, Provenance } from '../components/common';
import { getTitle, TITLES, titleSimilarity, similarityLabel } from '../../data/titles/catalogue';
import { COUNTRY_BY_CODE } from '../../data/countries';
import { ATTRS, ATTR_GROUPS, GROUP_INDICES, INPUTS, INPUT_LABEL, summaries, effectiveAttr, inputIndex, ratingBand } from '../../model/attributes';
import { navigate, withQuery, type Route } from '../router';
import { formatDate } from '../../core/date';
import { formatMoney } from '../../core/math';
import { roleName, seasonAgg, aggregate, isUserTeamId, userTeams, kda } from '../helpers';
import { potentialRange, scoutPlayer } from '../../systems/scouting';
import { effectivenessLight } from '../../sim/common/ratings';
import { expectedSalary } from '../../systems/contracts';
import { inTransferWindow, loanPlayer, offerExtension, offerFreeAgent, promotePlayer, releasePlayer, switchTitle } from '../../systems/transfers';
import { BuyPanel, SellPanel } from './TransferTalks';
import { legacy } from '../../systems/awards';
import { LineChart } from '../components/Charts';
import { displayName } from '../../gen/names';
import type { Player } from '../../model/types';

function OfferPanel({ p }: { p: Player }) {
  const world = useWorld();
  const { act, notify } = useGame();
  const mine = userTeams(world);
  const sameTitle = mine.filter((t) => t.titleId === p.titleId);
  const [teamId, setTeamId] = useState((sameTitle[0] ?? mine[0])?.id ?? '');
  const team = world.teams[teamId];
  const exp = team ? expectedSalary(world, p, team.titleId, team.region) : 0;
  const [salary, setSalary] = useState(Math.round(exp));
  const [years, setYears] = useState(2);
  const owned = isUserTeamId(world, p.teamId);
  if (!mine.length || p.retired) return null;
  const run = (fn: () => { ok: boolean; message: string; counterSalary?: number; counterFee?: number } | undefined) => {
    const r = act(() => fn(), { save: true });
    if (!r) return;
    notify(r.message, r.ok ? 'ok' : 'err');
    if (r.counterSalary) setSalary(r.counterSalary);
  };
  if (owned && p.contract) {
    const others = mine.filter((t) => t.id !== p.teamId);
    const aiTeams = Object.values(world.teams).filter((t) => t.active && t.titleId === p.titleId && !isUserTeamId(world, t.id)).sort((a, b) => b.elo - a.elo).slice(0, 30);
    return (
      <Gate world={world} perm="contracts">
      <Card title="Contract actions">
        <div className="row" style={{ alignItems: 'flex-end' }}>
          <label className="field">
            <span>Extension salary / season (expects ~{formatMoney(expectedSalary(world, p, p.titleId, world.teams[p.teamId!].region))})</span>
            <input type="number" value={salary} onChange={(e) => setSalary(Number(e.target.value))} />
          </label>
          <label className="field">
            <span>Seasons</span>
            <select value={years} onChange={(e) => setYears(Number(e.target.value))}>
              {[1, 2, 3, 4, 5].map((y) => (
                <option key={y}>{y}</option>
              ))}
            </select>
          </label>
          <button className="btn" onClick={() => run(() => offerExtension(world, p.id, salary, years))}>
            Offer extension
          </button>
        </div>
        <div className="row" style={{ marginTop: 10 }}>
          {others.map((t) => (
            <button key={t.id} className="btn ghost sm" onClick={() => run(() => promotePlayer(world, p.id, t.id))}>
              Move to {t.name} ({getTitle(t.titleId).short})
            </button>
          ))}
          <button className="btn ghost sm red" onClick={() => confirm(`Release ${p.handle}? A termination fee is paid.`) && run(() => (releasePlayer(world, p.id) ? { ok: true, message: `${p.handle} released.` } : { ok: false, message: 'Could not release.' }))}>
            Release
          </button>
        </div>
        <Gate world={world} perm="transfers">
          <SellPanel p={p} />
        </Gate>
        <div className="row" style={{ marginTop: 10 }}>
          <span className="muted small">Loan out (60 days, 50% wages):</span>
          <select onChange={(e) => e.target.value && run(() => loanPlayer(world, p.id, e.target.value, 60, 0.5))} defaultValue="">
            <option value="">Choose club…</option>
            {aiTeams.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </div>
      </Card>
      </Gate>
    );
  }
  return (
    <Gate world={world} perm="transfers">
    <Card title={p.teamId ? 'Transfer negotiation' : 'Offer a contract'} right={<span className="muted small">{inTransferWindow(world) ? 'Transfer window OPEN' : 'Window closed (free agents & listed players only)'}</span>}>
      <div className="row" style={{ alignItems: 'flex-end' }}>
        <label className="field">
          <span>For team</span>
          <select value={teamId} onChange={(e) => setTeamId(e.target.value)}>
            {mine.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} ({getTitle(t.titleId).short})
              </option>
            ))}
          </select>
        </label>
      </div>
      {p.teamId && team && (
        <div style={{ marginTop: 8 }}>
          <BuyPanel p={p} team={team} />
        </div>
      )}
      {!p.teamId && (
      <div className="row" style={{ alignItems: 'flex-end' }}>
        <label className="field">
          <span>Salary / season (expects ~{formatMoney(exp)})</span>
          <input type="number" value={salary} onChange={(e) => setSalary(Number(e.target.value))} />
        </label>
        <label className="field">
          <span>Seasons</span>
          <select value={years} onChange={(e) => setYears(Number(e.target.value))}>
            {[1, 2, 3, 4, 5].map((y) => (
              <option key={y}>{y}</option>
            ))}
          </select>
        </label>
        <button className="btn" onClick={() => run(() => offerFreeAgent(world, teamId, p.id, salary, years))}>
          Offer contract
        </button>
        {team && <TrialButtons world={world} p={p} team={team} />}
      </div>
      )}
      {team && team.titleId !== p.titleId && (
        <div className="muted small" style={{ marginTop: 6 }}>
          Cross-title signing: {getTitle(p.titleId).short} → {getTitle(team.titleId).short} ({similarityLabel(titleSimilarity(p.titleId, team.titleId))}, {Math.round(titleSimilarity(p.titleId, team.titleId) * 100)}%). Attributes are kept; title/role/character familiarity must be rebuilt.
        </div>
      )}
    </Card>
    </Gate>
  );
}

function AttributesTab({ p }: { p: Player }) {
  const world = useWorld();
  const [adjusted, setAdjusted] = useState(false);
  const t = getTitle(p.titleId);
  const ip = p.input[inputIndex(t.input)];
  const rel = (i: number) => {
    const a = ATTRS[i];
    if (a.group !== 'MOBA' && a.group !== 'Shooter') return 1;
    const r = t.specRelevance[a.key];
    return r === undefined ? ((t.family === 'MOBA') === (a.group === 'MOBA') ? 1 : 0) : r;
  };
  const sums = summaries(p.attrs);
  void world;
  return (
    <>
      <div className="row" style={{ marginBottom: 8 }}>
        <label>
          <input type="checkbox" checked={adjusted} onChange={(e) => setAdjusted(e.target.checked)} /> Show input-adjusted values for {t.short} ({INPUT_LABEL[t.input]} proficiency {ip.toFixed(0)})
        </label>
        <span className="muted small">Faded specialist attributes have little or no relevance in {t.short}.</span>
      </div>
      <div className="stats">
        {Object.entries(sums).map(([k, v]) => (
          <div key={k} className="stat">
            <div className="stat-label">{k}</div>
            <div className="stat-value">
              <Rating v={v} />
            </div>
          </div>
        ))}
      </div>
      <div className="attr-groups">
        {ATTR_GROUPS.map((g) => (
          <div key={g} className="attr-group card" style={{ padding: 8 }}>
            <h4>{g}</h4>
            {GROUP_INDICES[g].map((i) => {
              const v = adjusted ? effectiveAttr(p.attrs, i, ip) : p.attrs[i];
              return (
                <div key={i} className={`attr-row ${rel(i) < 0.3 ? 'irrelevant' : ''}`} title={`${ATTRS[i].name}: ${ratingBand(v).label}${ATTRS[i].inputSensitivity ? ` · input sensitivity ${ATTRS[i].inputSensitivity}` : ''}`}>
                  <span>{ATTRS[i].name}</span>
                  <Rating v={v} />
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </>
  );
}

function ProficiencyTab({ p }: { p: Player }) {
  const world = useWorld();
  const t = getTitle(p.titleId);
  const patch = world.titles[t.id]?.patches[world.titles[t.id].patches.length - 1];
  const chars = Object.entries(p.prof.chars[t.id] ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 20);
  const projections = useMemo(
    () =>
      TITLES.filter((x) => world.meta.titlesEnabled.includes(x.id) && x.id !== t.id)
        .map((x) => {
          const clone: Player = JSON.parse(JSON.stringify(p));
          switchTitle({ ...world, season: world.season, day: world.day } as never, clone, x.id);
          const role = x.roles.find((r) => r.id === clone.roles[0])!;
          return { x, e: effectivenessLight(clone, x, role), sim: titleSimilarity(t.id, x.id), role: role.name };
        })
        .sort((a, b) => b.e - a.e)
        .slice(0, 10),
    [p, world, t.id],
  );
  return (
    <div className="grid2">
      <div>
        <Card title="Input proficiencies (separate from attributes)">
          {INPUTS.map((k, i) => (
            <div key={k} className="attr-row">
              <span>
                {INPUT_LABEL[k]} {t.input === k && <span className="tag">required by {t.short}</span>}
              </span>
              <Rating v={p.input[i]} />
            </div>
          ))}
          <div className="muted small" style={{ marginTop: 6 }}>
            Effective Mechanics = M × (0.65 + 0.35 × P/100). Mental attributes are not penalised.
          </div>
        </Card>
        <Card title="Title familiarity">
          {Object.entries(p.prof.titles).sort((a, b) => b[1] - a[1]).map(([tid, v]) => (
            <div key={tid} className="attr-row">
              <span>
                <TitleTag id={tid} />
              </span>
              <Rating v={v} />
            </div>
          ))}
        </Card>
        <Card title={`Role proficiency & effectiveness (${t.short})`}>
          <table className="table">
            <thead>
              <tr>
                <th>Role</th>
                <th>Class</th>
                <th>Proficiency</th>
                <th>E</th>
              </tr>
            </thead>
            <tbody>
              {t.roles.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  <td className="muted small">{r.fclass.join(', ')}</td>
                  <td>
                    <Rating v={p.prof.roles[`${t.id}:${r.id}`] ?? 0} />
                  </td>
                  <td>
                    <Rating v={effectivenessLight(p, t, r)} digits={1} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
      <div>
        {t.characters.length > 0 && (
          <Card title={`${t.charLabel} proficiency`}>
            <table className="table">
              <tbody>
                {chars.map(([cid, v]) => {
                  const c = t.characters.find((x) => x.id === cid);
                  const meta = patch?.charMeta[cid] ?? 0;
                  return (
                    <tr key={cid}>
                      <td>{c?.name}</td>
                      <td className="muted small">{c?.cls}</td>
                      <td>
                        <Rating v={v} />
                      </td>
                      <td className={meta >= 0 ? 'pos small' : 'neg small'}>meta {meta > 0 ? '+' : ''}{meta.toFixed(1)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
        )}
        {Object.keys(p.prof.weapons[t.id] ?? {}).length > 0 && (
          <Card title="Weapons & equipment">
            {Object.entries(p.prof.weapons[t.id]).map(([w, v]) => (
              <div key={w} className="attr-row">
                <span>{w}</span>
                <Rating v={v} />
              </div>
            ))}
          </Card>
        )}
        {Object.keys(p.prof.maps[t.id] ?? {}).length > 0 && (
          <Card title="Maps & modes">
            {Object.entries(p.prof.maps[t.id]).map(([m, v]) => (
              <div key={m} className="attr-row">
                <span>{t.maps.find((x) => x.id === m)?.name ?? m}</span>
                <Rating v={v} />
              </div>
            ))}
          </Card>
        )}
        <Card title="Cross-title projection (if switched today)">
          <table className="table">
            <tbody>
              {projections.map((r) => (
                <tr key={r.x.id}>
                  <td>
                    <TitleTag id={r.x.id} />
                  </td>
                  <td className="muted small">{r.role}</td>
                  <td className="small">{Math.round(r.sim * 100)}% similar</td>
                  <td>
                    E <Rating v={r.e} digits={1} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="muted small">Projection keeps all permanent attributes; title, role and character familiarity transfer according to title similarity and must be rebuilt through training and play.</div>
        </Card>
      </div>
    </div>
  );
}

export function PlayerPage({ id, route }: { id: string; route: Route }) {
  const world = useWorld();
  const { act, notify } = useGame();
  const p = world.players[id];
  const tab = route.query.tab ?? 'overview';
  if (!p) {
    const r = world.retired.find((x) => x.id === id);
    if (r) return <Card title={`${r.handle} (archived)`}>{r.name} · retired {r.retiredSeason} · legacy {r.legacy.toFixed(0)} · {r.trophies} trophies · titles: {r.titles.join(', ')}</Card>;
    return <div>Player not found.</div>;
  }
  const t = getTitle(p.titleId);
  const team = p.teamId ? world.teams[p.teamId] : undefined;
  const [lo, hi] = potentialRange(world, p);
  const season = seasonAgg(p, world.season);
  const lg = legacy(p);
  const titles = [...new Set(p.stats.map((s) => s.titleId))];
  return (
    <div>
      <PageHeader
        title={
          <span>
            <Flag cc={p.nat} />
            {p.handle} <span className="muted" style={{ fontWeight: 400 }}>{displayName(p.first, p.last, p.nat)}</span>
          </span>
        }
        sub={
          <span>
            <TitleTag id={p.titleId} /> {t.roles.find((r) => r.id === p.roles[0])?.name} / {roleName(p.titleId, p.roles[1])} · <TeamLink t={team} /> · age {age(world, p.birth)} ({formatDate(p.birth)}) · {COUNTRY_BY_CODE[p.nat]?.name}
            {p.nat2 && ` / ${COUNTRY_BY_CODE[p.nat2]?.name}`} · speaks {p.langs.join(', ')}
            {p.retired && ` · RETIRED ${p.retired.season}`}
            <Provenance world={world} real={p.real} kind="player" />
            {p.injury && ` · injured (${p.injury.label}) until ${formatDate(p.injury.untilDay)}`}
            {p.leave && p.leave.untilDay > world.day && <span className="warn"> · {p.leave.kind === 'hiatus' ? 'stepped away for the rest of the season' : `on a break until ${formatDate(p.leave.untilDay)}`} ({p.leave.reason.toLowerCase()})</span>}
            {(() => {
              const st = seasonTitle(world, p);
              return st && st !== p.titleId ? ` · played ${getTitle(st).short} this season` : st ? ` · committed to ${getTitle(st).short} this season` : '';
            })()}
          </span>
        }
        right={
          <>
            {isUserTeamId(world, p.teamId) && can(world, 'tactics') && !p.retired && (
              p.leave && p.leave.untilDay > world.day ? (
                p.leave.byClub && (
                  <button className="btn ghost sm" onClick={() => { const r = act((w) => recallPlayer(w, p.id)); if (r) notify(r.message, r.ok ? 'ok' : 'err'); }}>
                    Bring back
                  </button>
                )
              ) : (
                <select
                  className="sm"
                  aria-label="Rest player"
                  value=""
                  onChange={(e) => {
                    const n = Number(e.target.value);
                    if (!n) return;
                    const r = act((w) => restPlayer(w, p.id, n));
                    if (r) notify(r.message, r.ok ? 'ok' : 'err');
                  }}
                >
                  <option value="">Rest…</option>
                  <option value="1">Rest 1 week</option>
                  <option value="2">Rest 2 weeks</option>
                  <option value="4">Rest 4 weeks</option>
                </select>
              )
            )}
            {!isUserTeamId(world, p.teamId) && (
              <button className="btn ghost sm" onClick={() => { act((w) => scoutPlayer(w, p.id)); notify(`${p.handle} added to scouting focus.`, 'ok'); }}>
                Scout
              </button>
            )}
            <button className="btn ghost sm" onClick={() => act((w) => { if (!w.shortlist.includes(p.id)) w.shortlist.push(p.id); else w.shortlist = w.shortlist.filter((x) => x !== p.id); })}>
              {world.shortlist.includes(p.id) ? '★ Shortlisted' : '☆ Shortlist'}
            </button>
          </>
        }
      />
      <div className="stats">
        <Stat label="Current Ability" value={<Rating v={p.ca} digits={1} />} sub={ratingBand(p.ca).label} />
        <Stat label="Potential (scouted)" value={lo === hi ? lo : `${lo}–${hi}`} sub={`knowledge ${Math.round(world.scouting[p.id] ?? 0)}%`} />
        <Stat label="Market value" value={formatMoney(p.value)} />
        <Stat label="Salary" value={p.contract ? <Money v={p.contract.salary} /> : 'Free agent'} sub={p.contract ? `until end of ${p.contract.endSeason}` : undefined} />
        <Stat label="Form / Morale" value={`${p.form.toFixed(1)} / ${p.morale.toFixed(0)}`} sub={`fatigue ${p.fatigue.toFixed(0)}`} />
        <Stat label={`${world.season} season`} value={season.gp ? season.rating.toFixed(2) : '—'} sub={`${season.gp} games, ${season.mvps} MVPs`} />
        <Stat label="Legacy" value={lg.total.toFixed(0)} sub={`${p.awards.length} honours`} />
      </div>
      <Tabs
        tabs={[
          { id: 'overview', label: 'Overview' },
          { id: 'attributes', label: 'Attributes' },
          { id: 'proficiency', label: 'Proficiencies' },
          { id: 'career', label: 'Career' },
          { id: 'stats', label: 'Statistics' },
          { id: 'contract', label: 'Contract' },
          { id: 'development', label: 'Development' },
          { id: 'history', label: 'History' },
          { id: 'awards', label: 'Awards' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'overview' && (
        <div className="grid2">
          <Card title="Profile">
            <div className="attr-row"><span>Preferred input</span><b>{INPUT_LABEL[p.prefInput]}</b></div>
            <div className="attr-row"><span>Fame</span><b>{p.rep.toFixed(0)}</b></div>
            <div className="attr-row"><span>Personality</span><b>{personalityLabel(p, world.scouting[p.id] ?? 0)}</b></div>
            <div className="attr-row"><span>Development profile</span><b>{(world.scouting[p.id] ?? 0) >= 70 ? `${p.dev.trajectory} developer` : 'Scout further to assess'}</b></div>
            <div className="attr-row"><span>Unhappiness</span><b>{p.unhappy.toFixed(0)}</b></div>
            <div className="attr-row"><span>Transfer listed</span><b>{p.listed ? 'Yes' : 'No'}</b></div>
            {p.dualTitleId && <div className="attr-row"><span>Dual-title</span><b>{getTitle(p.dualTitleId).short}</b></div>}
          </Card>
          <div>
            <OfferPanel p={p} />
            <Card title="Derived summaries">
              {Object.entries(summaries(p.attrs)).map(([k, v]) => (
                <div key={k} className="attr-row">
                  <span>{k}</span>
                  <Rating v={v} />
                </div>
              ))}
            </Card>
          </div>
        </div>
      )}
      {tab === 'attributes' && <AttributesTab p={p} />}
      {tab === 'proficiency' && <ProficiencyTab p={p} />}
      {tab === 'career' && (
        <Card title="Career timeline">
          <table className="table">
            <tbody>
              {[...p.career].reverse().map((c, i) => (
                <tr key={i}>
                  <td>{formatDate(c.day)}</td>
                  <td>
                    <TitleTag id={c.titleId} />
                  </td>
                  <td>
                    <span className="tag">{c.type}</span>
                  </td>
                  <td>{c.teamId ? <TeamLink t={world.teams[c.teamId]} /> : '—'}</td>
                  <td>{c.fee ? formatMoney(c.fee) : ''}</td>
                  <td className="muted small">{c.note ? <RichText world={world} text={c.note} /> : null}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      {tab === 'stats' && (
        <>
          {titles.map((tid) => {
            const tt = getTitle(tid);
            const lines = p.stats.filter((s) => s.titleId === tid).sort((a, b) => a.season - b.season);
            const tot = aggregate(lines);
            return (
              <Card key={tid} title={<span>{tt.name} — achievements stay with the title they were earned in</span>}>
                <div className="dt-scroll">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>Season</th>
                        <th>Team</th>
                        <th>GP</th>
                        <th>W-L</th>
                        <th>Rating</th>
                        <th>KDA</th>
                        {tt.stats.map((s) => (
                          <th key={s.key}>{s.label}/g</th>
                        ))}
                        <th>MVP</th>
                      </tr>
                    </thead>
                    <tbody>
                      {lines.map((l, i) => (
                        <tr key={i}>
                          <td>{l.season}</td>
                          <td>{l.teamId ? <TeamLink t={world.teams[l.teamId]} short /> : '—'}</td>
                          <td>{l.gp}</td>
                          <td>
                            {l.w}-{l.l}
                          </td>
                          <td>{(l.ratingSum / Math.max(1, l.gp)).toFixed(2)}</td>
                          <td>{kda(l.s)}</td>
                          {tt.stats.map((s) => (
                            <td key={s.key}>{((l.s[s.key] ?? 0) / Math.max(1, l.gp)).toFixed(1)}</td>
                          ))}
                          <td>{l.mvps}</td>
                        </tr>
                      ))}
                      <tr style={{ fontWeight: 700 }}>
                        <td>Career</td>
                        <td></td>
                        <td>{tot.gp}</td>
                        <td>
                          {tot.w}-{tot.l}
                        </td>
                        <td>{tot.rating.toFixed(2)}</td>
                        <td>{kda(tot.s)}</td>
                        {tt.stats.map((s) => (
                          <td key={s.key}>{((tot.s[s.key] ?? 0) / Math.max(1, tot.gp)).toFixed(1)}</td>
                        ))}
                        <td>{tot.mvps}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </Card>
            );
          })}
          {!titles.length && <Card>No competitive games played yet.</Card>}
        </>
      )}
      {tab === 'contract' && (
        <div className="grid2">
          <Card title="Current contract">
            {p.contract ? (
              <>
                <div className="attr-row"><span>Club</span><TeamLink t={world.teams[p.contract.teamId]} /></div>
                <div className="attr-row"><span>Salary</span><Money v={p.contract.salary} /></div>
                <div className="attr-row"><span>Expires</span><b>end of {p.contract.endSeason}</b></div>
                <div className="attr-row"><span>Signing bonus</span><Money v={p.contract.signingBonus} /></div>
                <div className="attr-row"><span>Trophy bonus</span><Money v={p.contract.winBonus} /></div>
                <div className="attr-row"><span>Prize-money share</span><b>{Math.round(p.contract.prizeShare * 100)}%</b></div>
                <div className="attr-row"><span>Buyout clause</span><b>{p.contract.buyout ? formatMoney(p.contract.buyout) : 'None'}</b></div>
                <div className="attr-row"><span>Option year</span><b>{p.contract.option}</b></div>
                <div className="attr-row"><span>Termination fee</span><Money v={p.contract.terminationFee} /></div>
                {p.contract.loan && <div className="attr-row"><span>On loan from</span><TeamLink t={world.teams[p.contract.loan.parentTeamId]} /> until {formatDate(p.contract.loan.untilDay)}</div>}
              </>
            ) : (
              <div className="muted">Free agent.</div>
            )}
          </Card>
          <OfferPanel p={p} />
        </div>
      )}
      {tab === 'development' && (
        <Card title="Current Ability by season">
          <LineChart series={[{ name: 'CA', color: 'var(--accent)', points: p.caHistory.map((h, i) => ({ x: h.season + i * 0.001, y: h.ca })) }]} yMin={30} yMax={100} xLabel={(x) => String(Math.floor(x))} />
          <div className="muted small">Training focus, coaching, facilities, work ethic, learning speed, age and hidden trajectory all shape development. Proficiencies grow faster than permanent attributes.</div>
        </Card>
      )}
      {tab === 'history' && (
        <Card title="Transfer history">
          <table className="table">
            <tbody>
              {world.transfers.filter((x) => x.playerId === p.id).reverse().map((x) => (
                <tr key={x.id}>
                  <td>{formatDate(x.day)}</td>
                  <td>
                    <span className="tag">{x.type}</span>
                  </td>
                  <td>{x.fromTeamId ? <TeamLink t={world.teams[x.fromTeamId]} /> : '—'}</td>
                  <td>→</td>
                  <td>{x.toTeamId ? <TeamLink t={world.teams[x.toTeamId]} /> : '—'}</td>
                  <td>{x.fee ? formatMoney(x.fee) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      {tab === 'awards' && (
        <div className="grid2">
          <Card title="Honours">
            {[...p.awards].reverse().map((a, i) => (
              <div key={i} className="attr-row">
                <span>
                  {a.season} · {a.titleId && <TitleTag id={a.titleId} />} {a.compId && world.comps[a.compId] ? <CompLink c={world.comps[a.compId]} /> : a.label}
                </span>
                <span className="muted small">+{(a.pts ?? 0).toFixed(1)}</span>
              </div>
            ))}
            {!p.awards.length && <div className="muted">No honours yet.</div>}
          </Card>
          <Card title="Legacy (GOAT score)">
            <div className="attr-row"><span>Achievements (prestige-weighted)</span><b>{lg.achievements.toFixed(1)}</b></div>
            <div className="attr-row"><span>Peak ability</span><b>{lg.peak.toFixed(1)}</b></div>
            <div className="attr-row"><span>Longevity</span><b>{lg.longevity.toFixed(1)}</b></div>
            <div className="attr-row"><span>Volume</span><b>{lg.volume.toFixed(1)}</b></div>
            <div className="attr-row"><span>Total</span><b>{lg.total.toFixed(1)}</b></div>
            {Object.entries(lg.byTitle).map(([tid, v]) => (
              <div key={tid} className="attr-row">
                <TitleTag id={tid} />
                <span>{v.toFixed(1)} achievement pts</span>
              </div>
            ))}
          </Card>
        </div>
      )}
    </div>
  );
}

function personalityLabel(p: Player, knowledge: number): string {
  if (knowledge < 40) return 'Unknown (scout to reveal)';
  const traits: string[] = [];
  if (p.pers.ambition > 70) traits.push('ambitious');
  if (p.pers.loyalty > 70) traits.push('loyal');
  if (p.pers.temperament < 30) traits.push('volatile');
  if (p.pers.ego > 75) traits.push('big ego');
  if (p.pers.greed > 75) traits.push('money-motivated');
  if (p.pers.pressure > 72) traits.push('big-game player');
  if (p.pers.pressure < 28) traits.push('nervous under pressure');
  if (p.pers.controversy > 65) traits.push('controversial');
  return traits.length ? traits.join(', ') : 'balanced';
}
