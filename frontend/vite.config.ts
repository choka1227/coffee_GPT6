import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: { proxy: { "/api": "http://localhost:8080" } },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("/echarts/") || id.includes("/zrender/"))
            return "charts";
        },
      },
    },
  },
});
