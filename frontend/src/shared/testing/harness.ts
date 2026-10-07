import { mount, flushPromises, type DOMWrapper, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { Component } from "vue";
import {
  createMemoryHistory,
  createRouter,
  type RouteRecordRaw,
} from "vue-router";
import { expect, vi } from "vitest";
import { useAuth } from "../../modules/identity/store";
import type { Actor } from "../types";

/**
 * `Chart.vue` 的測試替身。
 *
 * jsdom 沒有 ECharts 所需的 canvas context；報表測試只需要保留傳入的
 * option，讓測試能核對圖表資料，不需要真的繪圖。
 */
export const chartStub: Component = {
  name: "Chart",
  props: ["option", "label", "description"],
  template:
    '<div class="chart-stub" role="img" :aria-label="description || label"></div>',
};

export interface StubbedRequest {
  path: string;
  method: string;
  body: unknown;
  headers: Headers;
}

type RouteValue = unknown | ((request: StubbedRequest) => unknown);

export function stubApi(routes: Record<string, RouteValue>): {
  requests: StubbedRequest[];
  fetch: ReturnType<typeof vi.fn>;
} {
  const requests: StubbedRequest[] = [];
  const fetch = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const rawUrl = typeof input === "string" ? input : input.toString();
    const path = new URL(rawUrl, "http://localhost").pathname;
    const method = (init.method ?? "GET").toUpperCase();
    const headers = new Headers(init.headers);
    let body: unknown;
    if (typeof init.body === "string" && init.body.length) {
      try {
        body = JSON.parse(init.body);
      } catch {
        body = init.body;
      }
    }
    const request = { path, method, body, headers };
    requests.push(request);

    const csrf = {
      token: "test-token",
      headerName: "X-CSRF-TOKEN",
    };
    const configured =
      path === "/api/auth/csrf" ? csrf : routes[path];
    const status = configured === undefined ? 404 : 200;
    const payload =
      configured === undefined
        ? { message: `測試未設定此路由：${path}` }
        : typeof configured === "function"
          ? configured(request)
          : configured;
    const text = payload === undefined ? "" : JSON.stringify(payload);

    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => text,
      json: async () => payload,
    };
  });
  return { requests, fetch };
}

export async function mountView(
  component: Component,
  options: {
    actor: Actor | null;
    routes?: RouteRecordRaw[];
    fetch: ReturnType<typeof vi.fn>;
  },
): Promise<VueWrapper> {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuth();
  auth.user = options.actor;
  auth.loaded = true;

  const router = createRouter({
    history: createMemoryHistory(),
    routes: options.routes ?? [
      {
        path: "/:pathMatch(.*)*",
        component: { template: "<div />" },
      },
    ],
  });
  await router.push("/");
  await router.isReady();
  globalThis.fetch = options.fetch as typeof globalThis.fetch;

  const wrapper = mount(component, { global: { plugins: [pinia, router] } });
  await flushPromises();
  await flushPromises();
  expect(wrapper.text()).not.toContain("載入");
  return wrapper;
}

export function labelByText(
  wrapper: VueWrapper,
  text: string,
): DOMWrapper<HTMLLabelElement> | null {
  return (
    wrapper
      .findAll("label")
      .find((label) => label.text().startsWith(text)) ?? null
  ) as DOMWrapper<HTMLLabelElement> | null;
}

export function rowByLabel(
  wrapper: VueWrapper,
  text: string,
): DOMWrapper<Element> | null {
  return (
    wrapper
      .findAll(".change-row, .cart-total")
      .find((row) => row.find("span").text().startsWith(text)) ?? null
  );
}

export function checkoutButton(
  wrapper: VueWrapper,
): DOMWrapper<HTMLButtonElement> {
  return wrapper.get<HTMLButtonElement>(
    "button.checkout",
  ) as DOMWrapper<HTMLButtonElement>;
}
