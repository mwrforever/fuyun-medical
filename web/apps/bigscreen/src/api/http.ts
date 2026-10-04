/**
 * bigscreen Axios 单例（web A.3-1）：全应用唯一出网口（队列 REST 快照首屏 + nursing board
 * 三端点），禁组件直连 axios、禁组件自建实例。
 *
 * <p>拦截器职责收敛（A.3-2）：请求侧按缓存状态注入大屏令牌 Authorization（W-68 附调——
 * 持有令牌时 board 三端点等受保护面携哨兵令牌；未持有保持匿名，白名单面候诊榜快照照常）
 * 与每请求唯一 X-Trace-Id（traceId 全链路贯穿）；无 401 回调（令牌失效由 STOMP 侧按次
 * 重签承载，不落 HTTP 头刷新语义）；错误归一化为 ScreenApiError 上抛（大屏无弹窗交互，
 * 失败态由页面横幅承载，拦截器不吞错）。
 */
import axios, { AxiosError } from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';
import { getCachedBigscreenToken } from './bigscreenToken';

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

/**
 * traceId 生成（D-5 降级）：非安全上下文（HTTP 部署无 TLS）crypto.randomUUID 为
 * undefined 时降级时间戳+随机串（STOMP 侧 useNursingStomp 先例同款；每 app 内嵌一份，
 * 勿抽 shared——运行时导出超出顺带体量）。
 */
function generateTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 请求拦截器（导出供单测直调，挂接形态不变）：持有大屏令牌时注入 Authorization（W-68
 * 附调主路径——board 三端点等受保护面），未持有保持匿名（候诊榜白名单面零令牌照常出网）；
 * 无论是否持令牌均注入每请求唯一 X-Trace-Id（大屏长时值守的排障锚点）。
 *
 * @param config 请求配置（headers 必为 AxiosHeaders 实例——axios 拦截链保证）
 * @return 注入凭证与 traceId 头后的同一配置对象
 */
export function requestInterceptor(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = getCachedBigscreenToken();
  if (token !== '') {
    config.headers.Authorization = `Bearer ${token}`;
  }
  config.headers['X-Trace-Id'] = generateTraceId();
  return config;
}

http.interceptors.request.use(requestInterceptor);

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
