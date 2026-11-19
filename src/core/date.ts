/**
 * Game calendar. All game dates are stored as integer day numbers (days since 1970-01-01 UTC)
 * which makes arithmetic, sorting and serialisation trivial and timezone-safe.
 */

const MS_PER_DAY = 86400000;

export type DayNum = number;

export function dayFromYMD(y: number, m: number, d: number): DayNum {
  return Math.floor(Date.UTC(y, m - 1, d) / MS_PER_DAY);
}

export function ymd(day: DayNum): { y: number; m: number; d: number; dow: number } {
  const dt = new Date(day * MS_PER_DAY);
  return { y: dt.getUTCFullYear(), m: dt.getUTCMonth() + 1, d: dt.getUTCDate(), dow: dt.getUTCDay() };
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

export function formatDate(day: DayNum, withDow = false): string {
  const { y, m, d, dow } = ymd(day);
  return `${withDow ? DOW[dow] + ' ' : ''}${d} ${MONTHS[m - 1]} ${y}`;
}

export function formatShort(day: DayNum): string {
  const { m, d } = ymd(day);
  return `${d} ${MONTHS[m - 1]}`;
}

export function monthKey(day: DayNum): number {
  const { y, m } = ymd(day);
  return y * 12 + (m - 1);
}

export function isFirstOfMonth(day: DayNum): boolean {
  return ymd(day).d === 1;
}

export function dayOfWeek(day: DayNum): number {
  return ymd(day).dow;
}

/** Age in whole years at a given day. */
export function ageAt(birthDay: DayNum, day: DayNum): number {
  const b = ymd(birthDay);
  const n = ymd(day);
  let age = n.y - b.y;
  if (n.m < b.m || (n.m === b.m && n.d < b.d)) age--;
  return age;
}

/** Fractional age, for development curves. */
export function ageFloat(birthDay: DayNum, day: DayNum): number {
  return (day - birthDay) / 365.25;
}

/** Next day >= `from` that falls on the given weekday (0=Sun). */
export function nextWeekday(from: DayNum, dow: number): DayNum {
  const cur = dayOfWeek(from);
  return from + ((dow - cur + 7) % 7);
}

/** Real-world present day used as the default new-game start. */
export function todayDayNum(): DayNum {
  return Math.floor(Date.now() / MS_PER_DAY);
}
