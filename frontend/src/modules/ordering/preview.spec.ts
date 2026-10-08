import { describe, expect, it } from "vitest";
import type { OrderQuote } from "../../shared/types";
import {
  beginQuote,
  emptyQuoteState,
  resetQuote,
  settleQuote,
  showsQuote,
} from "./preview";

function quote(total: number): OrderQuote {
  return {
    subtotal: total,
    itemDiscountAmount: 0,
    codeDiscountAmount: 0,
    discountAmount: 0,
    total,
    itemPromotion: null,
    discount: null,
    items: [],
  };
}

describe("order preview state", () => {
  it("allocates monotonically increasing request sequences", () => {
    let state = emptyQuoteState();
    const sequences: number[] = [];
    for (let i = 0; i < 3; i += 1) {
      const begun = beginQuote(state);
      state = begun.state;
      sequences.push(begun.seq);
    }
    expect(sequences).toEqual([1, 2, 3]);
  });

  it("keeps a newer response when an older response arrives later", () => {
    let state = beginQuote(beginQuote(emptyQuoteState()).state).state;
    state = settleQuote(state, 2, { ok: true, quote: quote(200) });
    state = settleQuote(state, 1, { ok: true, quote: quote(100) });
    expect(state.settled).toBe(2);
    expect(state.quote?.total).toBe(200);
  });

  it("keeps a newer failure from being replaced by an older success", () => {
    let state = beginQuote(beginQuote(emptyQuoteState()).state).state;
    state = settleQuote(state, 2, { ok: false });
    state = settleQuote(state, 1, { ok: true, quote: quote(100) });
    expect(state.quote).toBeNull();
    expect(state.failed).toBe(true);
  });

  it("invalidates an in-flight response after reset", () => {
    const begun = beginQuote(emptyQuoteState());
    const reset = resetQuote(begun.state);
    expect(settleQuote(reset, begun.seq, { ok: true, quote: quote(100) })).toEqual(reset);
  });

  it("continues showing the last quote while a newer request is pending", () => {
    let state = beginQuote(emptyQuoteState()).state;
    state = settleQuote(state, 1, { ok: true, quote: quote(100) });
    state = beginQuote(state).state;
    expect(showsQuote(state)).toBe(true);
    expect(state.quote?.total).toBe(100);
  });
});
