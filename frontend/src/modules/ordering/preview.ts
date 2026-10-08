import type { OrderQuote } from "../../shared/types";

export interface QuoteState {
  sent: number;
  settled: number;
  quote: OrderQuote | null;
  failed: boolean;
}

export function emptyQuoteState(): QuoteState {
  return { sent: 0, settled: 0, quote: null, failed: false };
}

export function beginQuote(state: QuoteState): { state: QuoteState; seq: number } {
  const seq = state.sent + 1;
  return { state: { ...state, sent: seq }, seq };
}

export function settleQuote(
  state: QuoteState,
  seq: number,
  result: { ok: true; quote: OrderQuote } | { ok: false },
): QuoteState {
  if (seq <= state.settled) return state;
  return result.ok
    ? { ...state, settled: seq, quote: result.quote, failed: false }
    : { ...state, settled: seq, quote: null, failed: true };
}

export function resetQuote(state: QuoteState): QuoteState {
  return { sent: state.sent, settled: state.sent, quote: null, failed: false };
}

export function showsQuote(state: QuoteState): boolean {
  return state.quote !== null;
}
