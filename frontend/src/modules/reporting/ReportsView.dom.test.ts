import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import ReportsView from "./ReportsView.vue";
import Chart from "../../shared/Chart.vue";
import {
  globalActor,
  reportFixture,
} from "../../shared/testing/fixtures";
import {
  mountView,
  stubApi,
} from "../../shared/testing/harness";
import type { Report } from "../../shared/types";

async function mountReports(report: Report): Promise<VueWrapper> {
  const { fetch } = stubApi({
    "/api/reports": report,
    "/api/branches": [],
  });
  const wrapper = await mountView(ReportsView, {
    actor: globalActor(),
    fetch,
  });
  await flushPromises();
  return wrapper;
}

function panelByTitle(wrapper: VueWrapper, title: string) {
  const panel = wrapper
    .findAll("section.panel")
    .find((section) => section.find("h2").text() === title);
  expect(panel).toBeDefined();
  return panel!;
}

describe("ReportsView 淨營收口徑", () => {
  it("商品明細顯示淨營收、促銷折抵與淨額毛利，零折抵顯示破折號", async () => {
    const report = reportFixture({
      products: [
        {
          id: "P1",
          name: "促銷拿鐵",
          category: "經典咖啡",
          quantity: 2,
          revenue: 280,
          item_discount: 28,
          net_revenue: 252,
          cost: 110,
        },
        {
          id: "P2",
          name: "原價美式",
          category: "經典咖啡",
          quantity: 1,
          revenue: 100,
          item_discount: 0,
          net_revenue: 100,
          cost: 35,
        },
      ],
    });
    const wrapper = await mountReports(report);
    const panel = panelByTitle(wrapper, "本月商品銷售明細");

    expect(panel.text()).toContain("商品淨營收");
    expect(panel.text()).toContain("促銷折抵");
    expect(panel.text()).toContain("商品毛利（淨額基礎）");

    const promoRow = panel.findAll("tbody tr").find((row) => row.text().includes("促銷拿鐵"));
    const regularRow = panel.findAll("tbody tr").find((row) => row.text().includes("原價美式"));
    expect(promoRow?.text()).toContain("28");
    expect(promoRow?.text()).toContain("142");
    expect(regularRow?.text()).toContain("—");
    expect(regularRow?.text()).toContain("65");
  });

  it("摘要區拆出品項促銷與優惠碼折抵並保留總折抵", async () => {
    const wrapper = await mountReports(
      reportFixture({ discount: 42, itemDiscount: 28, codeDiscount: 14 }),
    );

    expect(wrapper.text()).toContain("總折抵");
    expect(wrapper.text()).toContain("品項促銷折抵");
    expect(wrapper.text()).toContain("優惠碼折抵");
  });

  it("分類圓餅圖使用 categoriesNet 並清楚標示淨營收", async () => {
    const wrapper = await mountReports(
      reportFixture({
        categories: { 經典咖啡: 999 },
        categoriesNet: { 經典咖啡: 252 },
      }),
    );
    const chart = wrapper
      .findAllComponents(Chart)
      .find((component) => component.props("label") === "餐點分類商品淨營收圓環圖");

    expect(chart).toBeDefined();
    expect(wrapper.text()).toContain("餐點分類淨營收佔比");
    expect(wrapper.text()).toContain("依所選月份商品淨營收計算");
    const option = chart!.props("option") as {
      series: { data: { name: string; value: number }[] }[];
    };
    expect(option.series[0].data).toEqual([
      { name: "經典咖啡", value: 252 },
    ]);
  });

  it("今日前五名顯示商品淨營收", async () => {
    const wrapper = await mountReports(
      reportFixture({
        topToday: [
          {
            id: "P1",
            name: "經典拿鐵",
            quantity: 2,
            revenue: 280,
            net_revenue: 252,
          },
        ],
      }),
    );

    expect(panelByTitle(wrapper, "今日人氣 TOP 5").text()).toContain("252");
  });
});
