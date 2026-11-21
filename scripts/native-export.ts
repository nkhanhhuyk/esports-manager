/**
 * Exports the web version's static game tables (countries, regions, name pools) as one JSON asset
 * for the native Android app, so the data has a single source:
 *
 *   npx esbuild scripts/native-export.ts --bundle --platform=node --outfile=/tmp/ne.cjs && node /tmp/ne.cjs
 *     → native-android/app/src/main/assets/gamedata.json
 */
import fs from 'node:fs';
import { COUNTRIES, MACROS, REGIONS } from '../src/data/countries';
import { COMPANY_TYPES, COMPANY_WORDS, CULTURES, HANDLE_PREFIX, HANDLE_ROOTS, HANDLE_SUFFIX, ORG_SUFFIX, ORG_WORDS_A, ORG_WORDS_B } from '../src/data/names';

const out = {
  regions: REGIONS,
  macros: MACROS,
  countries: COUNTRIES,
  cultures: CULTURES,
  handles: { roots: HANDLE_ROOTS, prefix: HANDLE_PREFIX, suffix: HANDLE_SUFFIX },
  orgWords: { a: ORG_WORDS_A, b: ORG_WORDS_B, suffix: ORG_SUFFIX },
  company: { words: COMPANY_WORDS, types: COMPANY_TYPES },
};
fs.writeFileSync('native-android/app/src/main/assets/gamedata.json', JSON.stringify(out));
console.log('countries', COUNTRIES.length, 'cultures', Object.keys(CULTURES).length);
