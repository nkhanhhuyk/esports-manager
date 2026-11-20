/**
 * Difficulty & sandbox options (Settings → Difficulty; chosen at a new career, changeable any time).
 *
 *  income      — your organisation's commercial income (sponsors, media, merchandise, league share)
 *  board       — how hard the board takes bad news (negative confidence changes are multiplied)
 *  injuries    — how often players get injured (whole world)
 *  breaks      — how often players ask for breaks (whole world)
 *  bargaining  — how hard AI clubs and agents bargain with you (asking prices, trade demands,
 *                players' wage demands)
 */
import type { World } from '../model/types';

export type DifficultyPreset = 'relaxed' | 'standard' | 'hard';

export interface Difficulty {
  preset: DifficultyPreset | 'custom';
  income: number;
  board: number;
  injuries: number;
  breaks: number;
  bargaining: number;
}

export const PRESETS: Record<DifficultyPreset, Difficulty> = {
  relaxed: { preset: 'relaxed', income: 1.15, board: 0.6, injuries: 0.6, breaks: 0.5, bargaining: 0.9 },
  standard: { preset: 'standard', income: 1, board: 1, injuries: 1, breaks: 1, bargaining: 1 },
  hard: { preset: 'hard', income: 0.88, board: 1.35, injuries: 1.4, breaks: 1.5, bargaining: 1.12 },
};

export const DIFFICULTY_LABEL: Record<keyof Omit<Difficulty, 'preset'>, { label: string; min: number; max: number; step: number; desc: string }> = {
  income: { label: 'Club income', min: 0.7, max: 1.4, step: 0.05, desc: 'Your sponsors, media, merchandise and league share.' },
  board: { label: 'Board strictness', min: 0.4, max: 1.8, step: 0.1, desc: 'How strongly bad results and finances cost board confidence.' },
  injuries: { label: 'Injury frequency', min: 0, max: 2, step: 0.1, desc: 'For every club.' },
  breaks: { label: 'Break frequency', min: 0, max: 2, step: 0.1, desc: 'How often players ask for breaks (every club).' },
  bargaining: { label: 'AI bargaining', min: 0.8, max: 1.3, step: 0.02, desc: 'Asking prices, trade demands and wage demands you face.' },
};

export function difficulty(world: World): Difficulty {
  return world.meta.difficulty ?? PRESETS.standard;
}

/** Board confidence change after difficulty (only losses are scaled). */
export function boardDelta(world: World, delta: number): number {
  return delta < 0 ? delta * difficulty(world).board : delta;
}
