import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: "node",
          environment: "node",
          include: ["src/**/*.spec.ts"],
          globals: false,
        },
      },
      {
        plugins: [vue()],
        test: {
          name: "dom",
          environment: "jsdom",
          include: ["src/**/*.dom.test.ts"],
          setupFiles: ["src/shared/testing/setup.dom.ts"],
          globals: false,
        },
      },
    ],
  },
});
