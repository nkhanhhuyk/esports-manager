import type { ReactNode } from 'react';
import { can, denyReason, type Permission } from '../../systems/career';
import { Link } from '../router';
import type { Competition, Organisation, Player, Staff, Team, World } from '../../model/types';
import { flag, COUNTRY_BY_CODE } from '../../data/countries';
import { getTitle } from '../../data/titles/catalogue';
import { ageAt, formatDate } from '../../core/date';
import { formatMoney } from '../../core/math';
import { displayName } from '../../gen/names';

export function Flag({ cc }: { cc: string }) {
  return (
    <span className="flag" title={COUNTRY_BY_CODE[cc]?.name ?? cc}>
      {flag(cc)}
    </span>
  );
}

export function PlayerLink({ p, showName }: { p: Player | undefined; showName?: boolean }) {
  if (!p) return <span className="muted">—</span>;
  return (
    <Link to={`/player/${p.id}`} className="elink">
      {p.handle}
      {showName && <span className="muted"> {displayName(p.first, p.last, p.nat)}</span>}
    </Link>
  );
}

export function TeamLink({ t, short }: { t: Team | undefined; short?: boolean }) {
  if (!t) return <span className="muted">Free agent</span>;
  return (
    <Link to={`/team/${t.id}`} className="elink">
      {short ? t.short : t.name}
    </Link>
  );
}

export function OrgBadge({ org, size = 18 }: { org: Organisation | undefined; size?: number }) {
  if (!org) return null;
  return (
    <span className="orgbadge" style={{ width: size, height: size, background: `linear-gradient(135deg, ${org.colors[0]} 50%, ${org.colors[1]} 50%)` }} title={org.name} aria-hidden="true" />
  );
}

export function OrgLink({ org }: { org: Organisation | undefined }) {
  if (!org) return <span className="muted">—</span>;
  return (
    <Link to={`/org/${org.id}`} className="elink">
      <OrgBadge org={org} size={14} /> {org.name}
    </Link>
  );
}

export function CompLink({ c }: { c: Competition | undefined }) {
  if (!c) return <span className="muted">—</span>;
  return (
    <Link to={`/comp/${c.id}`} className="elink">
      {c.name}
    </Link>
  );
}

export function StaffLink({ s }: { s: Staff | undefined }) {
  if (!s) return <span className="muted">—</span>;
  return (
    <Link to={`/staff/${s.id}`} className="elink">
      {s.handle ?? `${s.first} ${s.last}`}
    </Link>
  );
}

export function TitleTag({ id, link = true }: { id: string; link?: boolean }) {
  const t = getTitle(id);
  const el = <span className={`ttag ${t.family === 'MOBA' ? 'moba' : 'shooter'}${t.archived ? ' archived' : ''}`} title={t.archived ? t.name : undefined}>{t.short}</span>;
  return link && !t.archived ? <Link to={`/title/${id}`}>{el}</Link> : el;
}

/** Colour-coded 0-100 value (rating interpretation bands). */
export function Rating({ v, digits = 0 }: { v: number; digits?: number }) {
  const tier = v >= 95 ? 6 : v >= 85 ? 5 : v >= 75 ? 4 : v >= 60 ? 3 : v >= 40 ? 2 : v >= 20 ? 1 : 0;
  return <span className={`rt rt${tier}`}>{v.toFixed(digits)}</span>;
}

export function Bar({ v, max = 100, color }: { v: number; max?: number; color?: string }) {
  return (
    <span className="bar" aria-hidden="true">
      <span style={{ width: `${Math.max(0, Math.min(100, (v / max) * 100))}%`, background: color }} />
    </span>
  );
}

export function Money({ v }: { v: number }) {
  return <span className={v < 0 ? 'neg' : ''}>{formatMoney(v)}</span>;
}

export function Card({ title, children, right, className }: { title?: ReactNode; children: ReactNode; right?: ReactNode; className?: string }) {
  return (
    <section className={`card ${className ?? ''}`}>
      {(title || right) && (
        <header>
          <h3>{title}</h3>
          {right && <div className="card-right">{right}</div>}
        </header>
      )}
      <div className="card-body">{children}</div>
    </section>
  );
}

export function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

export function Tabs({ tabs, active, onChange }: { tabs: Array<{ id: string; label: string }>; active: string; onChange: (id: string) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={active === t.id} className={active === t.id ? 'active' : ''} onClick={() => onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function PageHeader({ title, sub, right, badge }: { title: ReactNode; sub?: ReactNode; right?: ReactNode; badge?: ReactNode }) {
  return (
    <div className="page-header">
      {badge}
      <div className="ph-main">
        <h1>{title}</h1>
        {sub && <div className="ph-sub">{sub}</div>}
      </div>
      {right && <div className="ph-right">{right}</div>}
    </div>
  );
}

export function age(world: World, birth: number): number {
  return ageAt(birth, world.day);
}

export function DateLabel({ day }: { day: number }) {
  return <span>{formatDate(day)}</span>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/** Disables its contents (and explains why) when the user's career role lacks a permission. */
export function Gate({ world, perm, children }: { world: World; perm: Permission; children: ReactNode }) {
  if (can(world, perm)) return <>{children}</>;
  return (
    <fieldset disabled style={{ border: 'none', padding: 0, margin: 0, opacity: 0.6 }}>
      <div className="tag" style={{ marginBottom: 6 }}>{denyReason(world, perm)}</div>
      {children}
    </fieldset>
  );
}
