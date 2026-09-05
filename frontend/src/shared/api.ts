let csrf: { token: string; headerName: string } | null = null;
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function refreshCsrf() {
  const r = await fetch("/api/auth/csrf", { credentials: "same-origin" });
  if (!r.ok) throw new ApiError("無法連線至服務，請稍後再試", r.status);
  csrf = await r.json();
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const method = (options.method || "GET").toUpperCase();
  if (!csrf && method !== "GET") await refreshCsrf();
  const headers = new Headers(options.headers);
  if (options.body) headers.set("Content-Type", "application/json");
  if (method !== "GET" && csrf) headers.set(csrf.headerName, csrf.token);
  let r: Response;
  try {
    r = await fetch("/api" + path, {
      ...options,
      headers,
      credentials: "same-origin",
    });
  } catch {
    throw new ApiError(
      "連線中斷，請確認網路後重試；訂單不會因重試而重複建立",
      0,
    );
  }
  const raw = await r.text();
  let data: any;
  try {
    data = raw ? JSON.parse(raw) : undefined;
  } catch {
    throw new ApiError("服務回應異常，請稍後再試", r.status);
  }
  if (!r.ok) {
    if (r.status === 401 && path !== "/auth/login" && path !== "/auth/me")
      window.dispatchEvent(new Event("session-expired"));
    throw new ApiError(data?.message || "操作失敗，請稍後再試", r.status);
  }
  return data as T;
}
export const send = <T>(
  path: string,
  data: unknown,
  method = "POST",
  headers: Record<string, string> = {},
) => api<T>(path, { method, headers, body: JSON.stringify(data) });
