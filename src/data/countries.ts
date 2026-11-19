/**
 * Real-world countries grouped into esports regions. Scene strengths are fictional-universe
 * starting values (editable) and evolve during the simulation.
 */

export interface RegionDef {
  id: string;
  name: string;
  short: string;
  macro: MacroId;
  economy: number; // salary / revenue multiplier (NA = 1.0)
}

export type MacroId = 'AMER' | 'EMEA' | 'APN' | 'APS';

export const MACROS: Record<MacroId, { name: string; short: string }> = {
  AMER: { name: 'Americas', short: 'AMER' },
  EMEA: { name: 'EMEA', short: 'EMEA' },
  APN: { name: 'Pacific North', short: 'PACN' },
  APS: { name: 'Pacific South', short: 'PACS' },
};

export const REGIONS: RegionDef[] = [
  { id: 'NA', name: 'North America', short: 'NA', macro: 'AMER', economy: 1.0 },
  { id: 'LATN', name: 'Latin America North', short: 'LATN', macro: 'AMER', economy: 0.35 },
  { id: 'LATS', name: 'Latin America South', short: 'LATS', macro: 'AMER', economy: 0.35 },
  { id: 'BR', name: 'Brazil', short: 'BR', macro: 'AMER', economy: 0.45 },
  { id: 'EUW', name: 'Western Europe', short: 'EUW', macro: 'EMEA', economy: 0.85 },
  { id: 'EUE', name: 'Central & Eastern Europe', short: 'EUE', macro: 'EMEA', economy: 0.45 },
  { id: 'CIS', name: 'CIS & Mongolia', short: 'CIS', macro: 'EMEA', economy: 0.45 },
  { id: 'MENA', name: 'Middle East, North Africa & Türkiye', short: 'MENA', macro: 'EMEA', economy: 0.6 },
  { id: 'AFR', name: 'Sub-Saharan Africa', short: 'AFR', macro: 'EMEA', economy: 0.2 },
  { id: 'KR', name: 'Korea', short: 'KR', macro: 'APN', economy: 0.9 },
  { id: 'CN', name: 'China', short: 'CN', macro: 'APN', economy: 1.0 },
  { id: 'PCS', name: 'Taiwan, Hong Kong & Macau', short: 'PCS', macro: 'APN', economy: 0.5 },
  { id: 'JP', name: 'Japan', short: 'JP', macro: 'APN', economy: 0.75 },
  { id: 'VN', name: 'Vietnam', short: 'VN', macro: 'APS', economy: 0.3 },
  { id: 'TH', name: 'Thailand', short: 'TH', macro: 'APS', economy: 0.3 },
  { id: 'ID', name: 'Indonesia', short: 'ID', macro: 'APS', economy: 0.35 },
  { id: 'PH', name: 'Philippines', short: 'PH', macro: 'APS', economy: 0.3 },
  { id: 'MYSG', name: 'Malaysia, Singapore & Mekong', short: 'MYSG', macro: 'APS', economy: 0.45 },
  { id: 'SA', name: 'South Asia', short: 'SA', macro: 'APS', economy: 0.25 },
  { id: 'OCE', name: 'Oceania', short: 'OCE', macro: 'APS', economy: 0.6 },
];

export const REGION_IDS = REGIONS.map((r) => r.id);
export const REGION_BY_ID: Record<string, RegionDef> = Object.fromEntries(REGIONS.map((r) => [r.id, r]));

export interface CountryDef {
  code: string;
  name: string;
  region: string;
  culture: string;
  langs: string[];
  moba: number; // starting scene strength 0..100
  shooter: number;
  weight: number; // relative talent-pool size within region
}

// [code, name, region, culture, langs, moba, shooter, weight]
const C: Array<[string, string, string, string, string, number, number, number]> = [
  ['US', 'United States', 'NA', 'english', 'en', 70, 85, 10],
  ['CA', 'Canada', 'NA', 'english', 'en,fr', 55, 70, 3],
  ['MX', 'Mexico', 'LATN', 'spanish', 'es', 50, 55, 6],
  ['CO', 'Colombia', 'LATN', 'spanish', 'es', 40, 40, 3],
  ['PE', 'Peru', 'LATN', 'spanish', 'es', 55, 35, 3],
  ['VE', 'Venezuela', 'LATN', 'spanish', 'es', 35, 30, 2],
  ['EC', 'Ecuador', 'LATN', 'spanish', 'es', 30, 25, 1],
  ['GT', 'Guatemala', 'LATN', 'spanish', 'es', 25, 20, 1],
  ['CR', 'Costa Rica', 'LATN', 'spanish', 'es', 25, 25, 1],
  ['DO', 'Dominican Republic', 'LATN', 'spanish', 'es', 20, 25, 1],
  ['AR', 'Argentina', 'LATS', 'spanish', 'es', 50, 60, 5],
  ['CL', 'Chile', 'LATS', 'spanish', 'es', 50, 50, 3],
  ['UY', 'Uruguay', 'LATS', 'spanish', 'es', 35, 35, 1],
  ['PY', 'Paraguay', 'LATS', 'spanish', 'es', 30, 25, 1],
  ['BO', 'Bolivia', 'LATS', 'spanish', 'es', 40, 20, 1],
  ['BR', 'Brazil', 'BR', 'portuguese', 'pt', 65, 85, 10],
  ['GB', 'United Kingdom', 'EUW', 'english', 'en', 60, 70, 5],
  ['IE', 'Ireland', 'EUW', 'english', 'en', 30, 35, 1],
  ['FR', 'France', 'EUW', 'french', 'fr', 70, 75, 5],
  ['DE', 'Germany', 'EUW', 'german', 'de', 70, 70, 5],
  ['AT', 'Austria', 'EUW', 'german', 'de', 40, 40, 1],
  ['CH', 'Switzerland', 'EUW', 'german', 'de,fr,it', 35, 40, 1],
  ['ES', 'Spain', 'EUW', 'spanish', 'es', 70, 65, 4],
  ['PT', 'Portugal', 'EUW', 'portuguese', 'pt', 45, 55, 2],
  ['IT', 'Italy', 'EUW', 'italian', 'it', 45, 50, 3],
  ['NL', 'Netherlands', 'EUW', 'dutch', 'nl,en', 50, 55, 2],
  ['BE', 'Belgium', 'EUW', 'dutch', 'nl,fr', 50, 50, 2],
  ['DK', 'Denmark', 'EUW', 'nordic', 'da,en', 80, 85, 2],
  ['SE', 'Sweden', 'EUW', 'nordic', 'sv,en', 70, 85, 2],
  ['NO', 'Norway', 'EUW', 'nordic', 'no,en', 55, 60, 1],
  ['FI', 'Finland', 'EUW', 'nordic', 'fi,en', 60, 75, 1],
  ['PL', 'Poland', 'EUE', 'polish', 'pl', 70, 80, 5],
  ['CZ', 'Czechia', 'EUE', 'czech', 'cs', 60, 60, 2],
  ['SK', 'Slovakia', 'EUE', 'czech', 'sk', 45, 55, 1],
  ['HU', 'Hungary', 'EUE', 'hungarian', 'hu', 50, 50, 1],
  ['RO', 'Romania', 'EUE', 'romanian', 'ro', 60, 55, 2],
  ['BG', 'Bulgaria', 'EUE', 'slavic_south', 'bg', 55, 55, 1],
  ['RS', 'Serbia', 'EUE', 'slavic_south', 'sr', 45, 65, 1],
  ['HR', 'Croatia', 'EUE', 'slavic_south', 'hr', 45, 50, 1],
  ['SI', 'Slovenia', 'EUE', 'slavic_south', 'sl', 40, 45, 1],
  ['BA', 'Bosnia & Herzegovina', 'EUE', 'slavic_south', 'bs', 40, 50, 1],
  ['GR', 'Greece', 'EUE', 'greek', 'el', 50, 45, 1],
  ['LT', 'Lithuania', 'EUE', 'baltic', 'lt', 45, 55, 1],
  ['LV', 'Latvia', 'EUE', 'baltic', 'lv', 45, 55, 1],
  ['EE', 'Estonia', 'EUE', 'baltic', 'et', 50, 60, 1],
  ['RU', 'Russia', 'CIS', 'russian', 'ru', 85, 90, 8],
  ['UA', 'Ukraine', 'CIS', 'russian', 'uk,ru', 80, 85, 4],
  ['BY', 'Belarus', 'CIS', 'russian', 'be,ru', 50, 55, 1],
  ['KZ', 'Kazakhstan', 'CIS', 'central_asian', 'kk,ru', 55, 65, 2],
  ['UZ', 'Uzbekistan', 'CIS', 'central_asian', 'uz,ru', 45, 50, 2],
  ['KG', 'Kyrgyzstan', 'CIS', 'central_asian', 'ky,ru', 40, 40, 1],
  ['GE', 'Georgia', 'CIS', 'caucasus', 'ka', 40, 40, 1],
  ['AM', 'Armenia', 'CIS', 'caucasus', 'hy', 40, 40, 1],
  ['AZ', 'Azerbaijan', 'CIS', 'caucasus', 'az', 40, 40, 1],
  ['MN', 'Mongolia', 'CIS', 'mongolian', 'mn', 55, 65, 1],
  ['TR', 'Türkiye', 'MENA', 'turkish', 'tr', 70, 75, 5],
  ['SA', 'Saudi Arabia', 'MENA', 'arabic', 'ar', 60, 65, 4],
  ['AE', 'United Arab Emirates', 'MENA', 'arabic', 'ar,en', 45, 50, 1],
  ['EG', 'Egypt', 'MENA', 'arabic', 'ar', 50, 45, 3],
  ['MA', 'Morocco', 'MENA', 'arabic', 'ar,fr', 45, 45, 2],
  ['JO', 'Jordan', 'MENA', 'arabic', 'ar', 40, 40, 1],
  ['KW', 'Kuwait', 'MENA', 'arabic', 'ar', 45, 45, 1],
  ['QA', 'Qatar', 'MENA', 'arabic', 'ar', 35, 35, 1],
  ['TN', 'Tunisia', 'MENA', 'arabic', 'ar,fr', 40, 40, 1],
  ['DZ', 'Algeria', 'MENA', 'arabic', 'ar,fr', 40, 40, 1],
  ['IQ', 'Iraq', 'MENA', 'arabic', 'ar', 40, 40, 2],
  ['IR', 'Iran', 'MENA', 'persian', 'fa', 45, 50, 2],
  ['ZA', 'South Africa', 'AFR', 'african_south', 'en,zu', 45, 50, 3],
  ['NG', 'Nigeria', 'AFR', 'african_west', 'en', 35, 35, 3],
  ['KE', 'Kenya', 'AFR', 'african_east', 'en,sw', 30, 30, 2],
  ['GH', 'Ghana', 'AFR', 'african_west', 'en', 30, 30, 1],
  ['ET', 'Ethiopia', 'AFR', 'african_east', 'am', 25, 25, 1],
  ['SN', 'Senegal', 'AFR', 'african_west', 'fr', 25, 25, 1],
  ['KR', 'South Korea', 'KR', 'korean', 'ko', 100, 85, 10],
  ['CN', 'China', 'CN', 'chinese', 'zh', 95, 85, 10],
  ['TW', 'Taiwan', 'PCS', 'taiwanese', 'zh', 70, 60, 4],
  ['HK', 'Hong Kong', 'PCS', 'cantonese', 'zh,en', 55, 50, 2],
  ['MO', 'Macau', 'PCS', 'cantonese', 'zh,pt', 35, 30, 1],
  ['JP', 'Japan', 'JP', 'japanese', 'ja', 65, 75, 10],
  ['VN', 'Vietnam', 'VN', 'vietnamese', 'vi', 75, 50, 10],
  ['TH', 'Thailand', 'TH', 'thai', 'th', 70, 65, 10],
  ['ID', 'Indonesia', 'ID', 'indonesian', 'id', 85, 60, 10],
  ['PH', 'Philippines', 'PH', 'filipino', 'fil,en', 85, 50, 10],
  ['MY', 'Malaysia', 'MYSG', 'malay', 'ms,en', 65, 55, 4],
  ['SG', 'Singapore', 'MYSG', 'chinese', 'en,zh,ms', 55, 50, 2],
  ['KH', 'Cambodia', 'MYSG', 'khmer', 'km', 55, 35, 2],
  ['MM', 'Myanmar', 'MYSG', 'burmese', 'my', 60, 40, 2],
  ['LA', 'Laos', 'MYSG', 'thai', 'lo', 35, 25, 1],
  ['BN', 'Brunei', 'MYSG', 'malay', 'ms', 30, 25, 1],
  ['IN', 'India', 'SA', 'indian', 'hi,en', 55, 60, 8],
  ['PK', 'Pakistan', 'SA', 'pakistani', 'ur,en', 50, 45, 3],
  ['BD', 'Bangladesh', 'SA', 'bengali', 'bn', 45, 40, 2],
  ['NP', 'Nepal', 'SA', 'indian', 'ne', 50, 45, 2],
  ['LK', 'Sri Lanka', 'SA', 'indian', 'si,ta', 35, 30, 1],
  ['AU', 'Australia', 'OCE', 'english', 'en', 55, 65, 5],
  ['NZ', 'New Zealand', 'OCE', 'english', 'en', 45, 50, 2],
];

export const COUNTRIES: CountryDef[] = C.map(([code, name, region, culture, langs, moba, shooter, weight]) => ({
  code,
  name,
  region,
  culture,
  langs: langs.split(','),
  moba,
  shooter,
  weight,
}));

export const COUNTRY_BY_CODE: Record<string, CountryDef> = Object.fromEntries(COUNTRIES.map((c) => [c.code, c]));

export const COUNTRIES_BY_REGION: Record<string, CountryDef[]> = COUNTRIES.reduce(
  (acc, c) => {
    (acc[c.region] ||= []).push(c);
    return acc;
  },
  {} as Record<string, CountryDef[]>,
);

/** Expand a league region id (single region or macro) into member region ids. */
export function expandRegion(id: string): string[] {
  if (REGION_BY_ID[id]) return [id];
  const macro = id.replace(/-.*$/, '') as MacroId;
  if (MACROS[macro]) {
    const members = id.includes('-') ? id.split('-')[1].split('.') : REGIONS.filter((r) => r.macro === macro).map((r) => r.id);
    return members;
  }
  return [];
}

export function regionName(id: string): string {
  if (REGION_BY_ID[id]) return REGION_BY_ID[id].name;
  const macro = id.replace(/-.*$/, '') as MacroId;
  return MACROS[macro]?.name ?? id;
}

export function regionShort(id: string): string {
  if (REGION_BY_ID[id]) return REGION_BY_ID[id].short;
  const macro = id.replace(/-.*$/, '') as MacroId;
  return MACROS[macro]?.short ?? id;
}

export function regionEconomy(id: string): number {
  const regs = expandRegion(id);
  if (!regs.length) return 0.5;
  return regs.reduce((s, r) => s + REGION_BY_ID[r].economy, 0) / regs.length;
}

/** ISO code -> regional indicator flag emoji (purely decorative, falls back to code). */
export function flag(code: string): string {
  if (!code || code.length !== 2) return '';
  const A = 0x1f1e6;
  return String.fromCodePoint(A + code.charCodeAt(0) - 65, A + code.charCodeAt(1) - 65);
}
