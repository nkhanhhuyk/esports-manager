/**
 * Supporter segments (organisation page) and rivalries (team page, match screens).
 */
import { RichText } from './RichText';
import type { Organisation, Team, World } from '../../model/types';
import { regionEconomy } from '../../data/countries';
import { formatMoney } from '../../core/math';
import { formatShort } from '../../core/date';
import { FAN_SEGMENTS, merchandiseRevenue, overallMood, payingOf, payingSupporters, SEGMENT_BY_ID } from '../../systems/fans';
import { getRivalry, rivalryLabel } from '../../systems/rivalries';
import { Bar, Card, Stat, TeamLink } from './common';
import { LineChart } from './Charts';

function moodWord(m: number): string {
  return m >= 75 ? 'delighted' : m >= 60 ? 'happy' : m >= 42 ? 'content' : m >= 28 ? 'unhappy' : 'angry';
}

function moodColor(m: number): string {
  return m >= 60 ? 'var(--good)' : m >= 42 ? 'var(--accent)' : m >= 28 ? 'var(--warn)' : 'var(--bad)';
}

export function FansCard({ world, org }: { world: World; org: Organisation }) {
  const fb = org.fanbase;
  if (!fb) return null;
  const paying = payingSupporters(fb);
  const mood = overallMood(fb);
  const merch = merchandiseRevenue(org, regionEconomy(org.region));
  const log = [...fb.log].reverse().slice(0, 12);
  const hist = fb.history ?? [];
  return (
    <Card title="Supporters" right={<span className="muted small">followers = reach · paying supporters = conversion</span>}>
      <div className="stats">
        <Stat label="Followers" value={org.fans.toLocaleString()} />
        <Stat label="Paying supporters" value={paying.toLocaleString()} sub={`${((paying / Math.max(1, org.fans)) * 100).toFixed(1)}% conversion`} />
        <Stat label="Overall mood" value={Math.round(mood)} sub={moodWord(mood)} />
        <Stat label="Buzz" value={Math.round(fb.buzz)} sub="recent attention" />
        <Stat label="Merchandise" value={`${formatMoney(merch)}/mo`} />
      </div>
      <div className="dt-scroll">
        <table className="table small">
          <thead>
            <tr>
              <th>Segment</th>
              <th>Cares about</th>
              <th className="num">Followers</th>
              <th className="num">Share</th>
              <th style={{ minWidth: 120 }}>Mood</th>
              <th className="num">Paying</th>
            </tr>
          </thead>
          <tbody>
            {FAN_SEGMENTS.map((s) => {
              const seg = fb.seg[s.id];
              return (
                <tr key={s.id}>
                  <td>
                    <b>{s.label}</b>
                  </td>
                  <td className="muted">{s.cares}</td>
                  <td className="num">{seg.size.toLocaleString()}</td>
                  <td className="num">{((seg.size / Math.max(1, org.fans)) * 100).toFixed(0)}%</td>
                  <td>
                    <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                      <div className="grow">
                        <Bar v={seg.mood} color={moodColor(seg.mood)} />
                      </div>
                      <span title={moodWord(seg.mood)}>{Math.round(seg.mood)}</span>
                    </div>
                  </td>
                  <td className="num">{Math.round(payingOf(fb, s.id)).toLocaleString()}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {hist.length > 1 && (
        <div style={{ marginTop: 8 }}>
          <div className="muted small">Followers over the last {hist.length} months</div>
          <LineChart series={[{ name: 'Followers', color: 'var(--accent)', points: hist.map((h, i) => ({ x: i, y: h.followers })) }]} height={120} xLabel={(x) => { const m = hist[x]?.month; return m === undefined ? '' : `${Math.floor(m / 12)}-${String((m % 12) + 1).padStart(2, '0')}`; }} yFormat={(y) => (y >= 1e6 ? `${(y / 1e6).toFixed(1)}M` : `${Math.round(y / 1000)}K`)} />
        </div>
      )}
      <div style={{ marginTop: 8 }}>
        <b className="small">Recent reactions</b>
        {!log.length && <div className="muted small">No notable reactions yet — supporters respond to results, signings, departures, academy debuts and rivalry meetings.</div>}
        {log.map((l, i) => (
          <div key={i} className="small" style={{ padding: '2px 0', borderBottom: '1px solid var(--border)' }}>
            <span className="muted">{formatShort(l.day)}</span> · {SEGMENT_BY_ID[l.seg].label}{' '}
            <b className={l.d > 0 ? 'pos' : 'neg'}>
              {l.d > 0 ? '+' : ''}
              {l.d}
            </b>{' '}
            · <RichText world={world} text={l.note} />
          </div>
        ))}
      </div>
      <div className="muted small" style={{ marginTop: 6 }}>
        Supporters affect merchandise, sponsorship value, audiences and reputation. They never change a player's ability.
      </div>
    </Card>
  );
}

/** Inline badge for a fixture between rivals. */
export function RivalryBadge({ world, a, b }: { world: World; a: string; b: string }) {
  const r = getRivalry(world, a, b);
  const label = r ? rivalryLabel(r.intensity) : null;
  if (!r || !label) return null;
  const ai = r.teams[0] === a ? 0 : 1;
  return (
    <span className="tag rivalry" title={`Intensity ${Math.round(r.intensity)}/100 · ${r.meetings} tracked meetings since ${r.firstSeason}`}>
      {label === 'Emerging' ? 'Emerging rivalry' : label === 'Rivalry' ? 'Rivalry' : `${label} rivalry`} · H2H {r.wins[ai]}–{r.wins[1 - ai]}
    </span>
  );
}

export function RivalriesCard({ world, team }: { world: World; team: Team }) {
  const list = Object.values(world.rivalries ?? {})
    .filter((r) => (r.teams[0] === team.id || r.teams[1] === team.id) && (r.intensity >= 15 || r.since !== undefined))
    .sort((x, y) => y.intensity - x.intensity);
  return (
    <Card title="Rivalries" right={<span className="muted small">built from real meetings — finals, playoffs, deciders, upsets, star transfers, derbies; fade without contact</span>}>
      {!list.length && <div className="muted">No rivalries yet. A single ordinary defeat never starts one; repeated high-stakes meetings do.</div>}
      {!!list.length && (
        <div className="dt-scroll">
          <table className="table small">
            <thead>
              <tr>
                <th>Opponent</th>
                <th style={{ minWidth: 110 }}>Intensity</th>
                <th className="num">H2H</th>
                <th className="num">Meetings</th>
                <th className="num">Finals</th>
                <th className="num">Playoffs</th>
                <th>Since</th>
                <th>Latest moment</th>
              </tr>
            </thead>
            <tbody>
              {list.map((r) => {
                const me = r.teams[0] === team.id ? 0 : 1;
                const opp = world.teams[r.teams[1 - me]];
                const label = rivalryLabel(r.intensity) ?? 'Dormant';
                const last = r.moments[r.moments.length - 1];
                return (
                  <tr key={r.id}>
                    <td>
                      <TeamLink t={opp} />
                      {r.derby && <span className="tag" style={{ marginLeft: 4 }}>derby</span>}
                    </td>
                    <td>
                      <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                        <div className="grow">
                          <Bar v={r.intensity} color={r.intensity >= 50 ? 'var(--bad)' : r.intensity >= 30 ? 'var(--warn)' : 'var(--accent)'} />
                        </div>
                        <span>{label}</span>
                      </div>
                    </td>
                    <td className="num">
                      {r.wins[me]}–{r.wins[1 - me]}
                    </td>
                    <td className="num">{r.meetings}</td>
                    <td className="num">{r.finals}</td>
                    <td className="num">{r.playoffs}</td>
                    <td>{r.since ?? r.firstSeason}</td>
                    <td className="muted">{last ? <>{last.season}: <RichText world={world} text={last.text} /></> : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
