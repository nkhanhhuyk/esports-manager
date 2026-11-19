import { useWorld } from '../store';
import { Card, CompLink, PageHeader, TitleTag } from '../components/common';
import { formatDate, formatShort } from '../../core/date';
import { navigate, withQuery, type Route } from '../router';
import { fixtureLabel, isUserTeamId, scoreLabel, userTeams } from '../helpers';

export function SchedulePage({ route }: { route: Route }) {
  const world = useWorld();
  const mine = userTeams(world).map((t) => t.id);
  const view = route.query.view ?? 'mine';
  const day = Number(route.query.day ?? world.day + 1);
  const fixtures = Object.values(world.fixtures)
    .filter((f) => (view === 'mine' ? f.teams.some((t) => mine.includes(t)) : f.day === day))
    .sort((a, b) => a.day - b.day);
  return (
    <div>
      <PageHeader
        title="Schedule"
        sub="One shared calendar for all 30 titles"
        right={
          <>
            <button className={`btn sm ${view === 'mine' ? '' : 'ghost'}`} onClick={() => navigate(withQuery(route, { view: 'mine' }))}>
              My teams
            </button>
            <button className={`btn sm ${view === 'day' ? '' : 'ghost'}`} onClick={() => navigate(withQuery(route, { view: 'day' }))}>
              World by day
            </button>
          </>
        }
      />
      {view === 'day' && (
        <div className="row" style={{ marginBottom: 8 }}>
          <button className="btn ghost sm" onClick={() => navigate(withQuery(route, { day: String(day - 1) }))}>
            ‹
          </button>
          <b>{formatDate(day, true)}</b>
          <button className="btn ghost sm" onClick={() => navigate(withQuery(route, { day: String(day + 1) }))}>
            ›
          </button>
          <span className="muted small">{fixtures.length} fixtures</span>
        </div>
      )}
      <Card>
        <table className="table">
          <tbody>
            {fixtures.map((f) => (
              <tr key={f.id} className={`clickable ${f.teams.some((t) => isUserTeamId(world, t)) ? 'hl' : ''}`} onClick={() => navigate(`/match/${f.id}`)}>
                <td>{formatShort(f.day)}</td>
                <td>
                  <TitleTag id={f.titleId} link={false} />
                </td>
                <td>
                  <CompLink c={world.comps[f.compId]} />
                </td>
                <td>{fixtureLabel(world, f)}</td>
                <td>
                  <b>{scoreLabel(f)}</b>
                </td>
                <td>{f.detail ? <span className="tag">full detail</span> : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!fixtures.length && <div className="muted">No fixtures.</div>}
      </Card>
    </div>
  );
}
