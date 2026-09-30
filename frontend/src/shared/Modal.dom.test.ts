import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import Modal from "./Modal.vue";

describe("Modal", () => {
  it("顯示傳入的標題", () => {
    const wrapper = mount(Modal, { props: { title: "測試標題" } });

    expect(wrapper.get("h2").text()).toBe("測試標題");
  });

  it("顯示插槽內容", () => {
    const wrapper = mount(Modal, {
      props: { title: "測試標題" },
      slots: { default: "插槽內容" },
    });

    expect(wrapper.text()).toContain("插槽內容");
  });

  it("點擊關閉按鈕會送出 close 事件", async () => {
    const wrapper = mount(Modal, { props: { title: "測試標題" } });

    await wrapper.get('button[aria-label="關閉"]').trigger("click");

    expect(wrapper.emitted("close")).toHaveLength(1);
  });
});
