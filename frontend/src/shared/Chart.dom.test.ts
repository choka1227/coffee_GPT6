import { mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
  disconnect: vi.fn(),
  init: vi.fn(),
}));

vi.mock("echarts/core", () => ({
  init: mocks.init,
  use: vi.fn(),
}));
vi.mock("echarts/charts", () => ({
  LineChart: {},
  BarChart: {},
  PieChart: {},
}));
vi.mock("echarts/components", () => ({
  GridComponent: {},
  TooltipComponent: {},
  LegendComponent: {},
  AriaComponent: {},
}));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

import Chart from "./Chart.vue";

describe("Chart.vue", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.init.mockReturnValue({
      setOption: mocks.setOption,
      resize: mocks.resize,
      dispose: mocks.dispose,
    });
    globalThis.ResizeObserver = class {
      observe = vi.fn();
      disconnect = mocks.disconnect;
    } as never;
  });

  it("掛載時把 option 連同 aria 說明傳給 setOption", () => {
    const fallback = mount(Chart, {
      props: { option: { series: [] }, label: "每小時訂單" },
    });

    expect(mocks.init).toHaveBeenCalledOnce();
    expect(mocks.setOption).toHaveBeenLastCalledWith({
      series: [],
      aria: { enabled: true, description: "每小時訂單" },
    });
    fallback.unmount();

    vi.clearAllMocks();
    const described = mount(Chart, {
      props: {
        option: { xAxis: { data: ["01"] } },
        label: "每日營業額",
        description: "本月營業額 100 元",
      },
    });

    expect(mocks.setOption).toHaveBeenLastCalledWith({
      xAxis: { data: ["01"] },
      aria: { enabled: true, description: "本月營業額 100 元" },
    });
    described.unmount();
  });

  it("option 變更時以 notMerge 重設，不與舊 series 合併", async () => {
    const wrapper = mount(Chart, {
      props: { option: { series: [{ data: [1] }] }, label: "圖表" },
    });
    mocks.setOption.mockClear();
    const next = { series: [{ data: [2, 3] }] };

    await wrapper.setProps({ option: next });

    expect(mocks.setOption).toHaveBeenLastCalledWith(next, true);
    wrapper.unmount();
  });

  it("卸載時釋放 observer 與 chart 實例", () => {
    const wrapper = mount(Chart, {
      props: { option: { series: [] }, label: "圖表" },
    });

    wrapper.unmount();

    expect(mocks.disconnect).toHaveBeenCalledOnce();
    expect(mocks.dispose).toHaveBeenCalledOnce();
  });

  it("容器有 role=img 與可讀的 aria-label", async () => {
    const wrapper = mount(Chart, {
      props: {
        option: { series: [] },
        label: "備援標籤",
        description: "完整說明",
      },
    });

    expect(wrapper.get(".chart").attributes("role")).toBe("img");
    expect(wrapper.get(".chart").attributes("aria-label")).toBe("完整說明");
    await wrapper.setProps({ description: undefined });
    expect(wrapper.get(".chart").attributes("aria-label")).toBe("備援標籤");
    wrapper.unmount();
  });
});
