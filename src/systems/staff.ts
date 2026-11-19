/**
 * Staff hiring / release for the user (AI orgs use rollover logic).
 */
import type { World } from '../model/types';
import { expectedStaffSalary, makeContract } from './contracts';
import { ledger } from './finance';

export function hireStaff(world: World, staffId: string, teamId: string, salary: number, years: number): { ok: boolean; message: string } {
  const s = world.staff[staffId];
  const team = world.teams[teamId];
  if (!s || !team || s.retired) return { ok: false, message: 'Invalid staff or team.' };
  if (s.teamId || (s.orgId && s.contract)) return { ok: false, message: `${s.first} ${s.last} is under contract elsewhere.` };
  const expected = expectedStaffSalary(world, s, team.titleId, team.region);
  if (salary < expected * 0.85) return { ok: false, message: `${s.first} ${s.last} wants at least ${Math.round(expected * 0.9).toLocaleString()} per season.` };
  const orgLevel = s.role === 'scout' || s.role === 'gm' || s.role === 'director';
  if (!orgLevel && (s.role === 'head-coach' || s.role === 'academy-coach')) {
    const existing = team.staff.map((id) => world.staff[id]).find((x) => x && (x.role === 'head-coach' || x.role === 'academy-coach'));
    if (existing) return { ok: false, message: `${team.name} already has a head coach — release them first.` };
  }
  s.contract = makeContract(world, team, salary, years, { buyout: null });
  s.orgId = team.orgId;
  if (!orgLevel) {
    s.teamId = team.id;
    team.staff.push(s.id);
    if (s.titleId !== team.titleId) {
      s.titleId = team.titleId;
      s.titleFam[team.titleId] = Math.max(s.titleFam[team.titleId] ?? 0, 15);
    }
  }
  s.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: orgLevel ? null : team.id, orgId: team.orgId, type: 'signed' });
  return { ok: true, message: `${s.first} ${s.last} joins as ${s.role}.` };
}

export function releaseStaff(world: World, staffId: string): { ok: boolean; message: string } {
  const s = world.staff[staffId];
  if (!s) return { ok: false, message: 'Invalid staff.' };
  const org = s.orgId ? world.orgs[s.orgId] : null;
  if (org && s.contract) ledger(world, org, 'termination', -Math.round(s.contract.salary * 0.5), s.teamId ?? undefined, `${s.first} ${s.last} released`);
  if (s.teamId) {
    const t = world.teams[s.teamId];
    if (t) t.staff = t.staff.filter((x) => x !== s.id);
  }
  s.teamId = null;
  s.orgId = null;
  s.contract = null;
  s.career.push({ season: world.season, day: world.day, titleId: s.titleId ?? '', teamId: null, orgId: null, type: 'released' });
  return { ok: true, message: 'Staff member released.' };
}
