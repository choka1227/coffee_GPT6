import { expect, it } from "vitest";
import AuditView from "./AuditView.vue";
import { globalActor } from "../../shared/testing/fixtures";
import { mountView, stubApi } from "../../shared/testing/harness";

it("稽核篩選包含退款", async () => {
  const { fetch } = stubApi({ "/api/audit": { items: [], nextCursor: null }, "/api/branches": [] });
  const wrapper = await mountView(AuditView, { actor: globalActor(), fetch });
  expect(wrapper.get("option[value='ORDER_REFUNDED']").text()).toBe("退款");
});
