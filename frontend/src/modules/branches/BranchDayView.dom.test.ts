import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises } from "@vue/test-utils";
import App from "../../App.vue";
import BranchDayView from "./BranchDayView.vue";
import {
  branchStaffActor,
  globalActor,
} from "../../shared/testing/fixtures";
import {
  mountView,
  stubApi,
  type StubbedRequest,
} from "../../shared/testing/harness";
import type { Actor, BranchDayOverride } from "../../shared/types";
import { notice } from "../../shared/notice";

function managerActor(): Actor {
  return {
    ...branchStaffActor(),
    id: "manager",
    username: "manager@coffee.local",
    name: "測試店長",
    role: "MANAGER",
    permissions: ["BRANCH_HOURS_OVERRIDE", "REPORT_STORE"],
  };
}

function routes(overrides: BranchDayOverride[] = []) {
  return stubApi({
    "/api/branches/B1/hours": {
      branchId: "B1",
      openNow: true,
      hours: [{ dayOfWeek: 1, openMinute: 540, closeMinute: 1020 }],
      lastOrderMinutes: 20,
      orderableNow: true,
      minutesUntilLastOrder: null,
    },
    "/api/branches/B1/hour-overrides": {
      branchId: "B1",
      from: 20261003,
      to: 20261017,
      overrides,
    },
    "/api/branches/B1/hour-overrides/20261003": (request: StubbedRequest) => ({
      onDate: 20261003,
      dayOfWeek: 6,
      closed: (request.body as { closed: boolean }).closed,
      note: (request.body as { note: string }).note,
      hours: (request.body as { hours: BranchDayOverride["hours"] }).hours,
      lastOrderMinutes: (
        request.body as { lastOrderMinutes: number | null }
      ).lastOrderMinutes,
    }),
  });
}

beforeEach(() => {
  notice.value = "";
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-02T16:30:00Z"));
});

afterEach(() => {
  vi.useRealTimers();
});

describe("本店營業設定頁", () => {
  it("只讀取登入店長的營業資料，不呼叫總部分店列表", async () => {
    const { fetch, requests } = routes();
    const wrapper = await mountView(BranchDayView, {
      actor: managerActor(),
      fetch,
    });

    expect(wrapper.text()).toContain("本店營業設定");
    expect(requests.map((request) => request.path)).toEqual([
      "/api/branches/B1/hours",
      "/api/branches/B1/hour-overrides",
    ]);
    expect(requests.some((request) => request.path === "/api/branches")).toBe(
      false,
    );
    expect(
      requests.some((request) => request.path.includes("manage=true")),
    ).toBe(false);
  });

  it("日期限制為今日至 14 天，並可送出只覆寫最後點餐", async () => {
    const { fetch, requests } = routes();
    const wrapper = await mountView(BranchDayView, {
      actor: managerActor(),
      fetch,
    });

    await wrapper
      .findAll("button")
      .find((button) => button.text().includes("新增單日設定"))!
      .trigger("click");
    const date = wrapper.get<HTMLInputElement>('input[type="date"]');
    expect(date.attributes("min")).toBe("2026-10-03");
    expect(date.attributes("max")).toBe("2026-10-17");

    const weekly = wrapper
      .findAll("label")
      .find((label) => label.text().includes("沿用每週時段"));
    await weekly!.get("input").setValue(true);
    await wrapper
      .get<HTMLInputElement>('input[type="number"]')
      .setValue("40");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const saved = requests.find(
      (request) =>
        request.path === "/api/branches/B1/hour-overrides/20261003" &&
        request.method === "PUT",
    );
    expect(saved?.body).toEqual({
      closed: false,
      note: "",
      hours: [],
      lastOrderMinutes: 40,
    });
  });
});

describe("側欄權限可見性", () => {
  it("店長只看到本店營業設定", async () => {
    const { fetch } = stubApi({});
    const wrapper = await mountView(App, { actor: managerActor(), fetch });
    expect(wrapper.text()).toContain("本店營業設定");
    expect(wrapper.text()).not.toContain("分店管理");
  });

  it("總部只看到分店管理", async () => {
    const { fetch } = stubApi({});
    const wrapper = await mountView(App, { actor: globalActor(), fetch });
    expect(wrapper.text()).toContain("分店管理");
    expect(wrapper.text()).not.toContain("本店營業設定");
  });

  it("收銀員看不到兩個入口，路由仍由 permission meta 保護", async () => {
    const { fetch } = stubApi({});
    const wrapper = await mountView(App, {
      actor: branchStaffActor(),
      fetch,
    });
    expect(wrapper.text()).not.toContain("分店管理");
    expect(wrapper.text()).not.toContain("本店營業設定");

    const source = import.meta.glob("../../main.ts", {
      query: "?raw",
      import: "default",
      eager: true,
    })["../../main.ts"] as string;
    expect(source).toContain('path: "/branch-day"');
    expect(source).toContain('permissions: ["BRANCH_HOURS_OVERRIDE"]');
    expect(source).toContain('notify("你的帳號沒有這個功能的權限")');
    expect(source).toContain('return "/"');
  });
});
