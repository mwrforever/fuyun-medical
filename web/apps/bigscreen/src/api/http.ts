/**
 * bigscreen Axios 单例（web A.3-1）：全应用唯一出网口（当前仅队列 REST 快照首屏），
 * 禁组件直连 axios、禁组件自建实例。
 *
 * <p>大屏受控演示面口径：无 Authorization 注入、无 401 回调（REST 快照为匿名只读面，订阅级
 * 鉴权凭证走 STOMP CONNECT 帧，不落 HTTP 头）；保留 X-Trace-Id 注入（traceId 全链路贯穿）；
 * 错误归一化为 ScreenApiError 上抛（大屏无弹窗交互，失败态由页面横幅承载，拦截器不吞错）。
 */
import axios, { AxiosError } from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';

/** 全局唯一 Axios 实例：baseURL 复用 VITE_API_BASE_URL（dev 经 vite proxy、生产经 nginx 反代） */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15000,
});

/**
 * bigscreen 归一化业务错误：ProblemDetail 的页面投影（detail 文案 + HTTP 状态）。
 * 大屏失败呈现为「连接中断/加载失败」横幅，detail 供调试横幅与日志使用。
 */
export class ScreenApiError extends Error {
  /** 后端 ProblemDetail.detail 或网络层通用文案 */
  readonly detail: string;
  /** HTTP 状态码；网络层错误（无响应）为 0 */
  readonly status: number;

  constructor(detail: string, status: number) {
    super(detail);
    this.name = 'ScreenApiError';
    this.detail = detail;
    this.status = status;
  }
}

/** 从 Axios 错误提取 ProblemDetail.detail，缺失回退 undefined */
function extractErrorDetail(error: AxiosError): string | undefined {
  const data: unknown = error.response?.data;
  if (typeof data === 'object' && data !== null && 'detail' in data) {
    const detail = (data as { detail?: unknown }).detail;
    if (typeof detail === 'string' && detail.length > 0) {
      return detail;
    }
  }
  return undefined;
}

// 请求拦截器：仅注入 X-Trace-Id（大屏长时值守的排障锚点；禁 Authorization——匿名只读面）
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers['X-Trace-Id'] = crypto.randomUUID();
  return config;
});

// 响应拦截器：非 2xx 归一化为 ScreenApiError 上抛（横幅呈现归页面）；不吞错
http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (error instanceof AxiosError) {
      throw new ScreenApiError(
        extractErrorDetail(error) ?? '数据链路异常，自动重试中',
        error.response?.status ?? 0,
      );
    }
    throw error;
  },
);
