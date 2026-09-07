<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import { init, use, type ECharts } from "echarts/core";
import type { EChartsOption } from "echarts";
import { LineChart, BarChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  AriaComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  AriaComponent,
  CanvasRenderer,
]);
const props = defineProps<{
  option: EChartsOption;
  label: string;
  description?: string;
}>();
const root = ref<HTMLElement>();
let chart: ECharts | undefined;
let observer: ResizeObserver | undefined;
onMounted(() => {
  chart = init(root.value!);
  chart.setOption({
    ...props.option,
    aria: { enabled: true, description: props.description || props.label },
  });
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(root.value!);
});
watch(
  () => props.option,
  (v) => chart?.setOption(v, true),
  { deep: true },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div
    ref="root"
    class="chart"
    role="img"
    :aria-label="description || label"
  ></div>
</template>
