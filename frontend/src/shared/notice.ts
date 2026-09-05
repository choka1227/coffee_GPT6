import { ref } from "vue";
export const notice = ref("");
let timer: ReturnType<typeof setTimeout>;
export function notify(message: string) {
  notice.value = message;
  clearTimeout(timer);
  timer = setTimeout(() => (notice.value = ""), 5500);
}
