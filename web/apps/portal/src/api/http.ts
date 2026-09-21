/**
 * portal Axios 单例（web A.3-1）：全应用唯一出网口，禁组件直连 axios、禁组件自建实例。
 *
 * <p>免登录匿名通道口径（裁决 13，portal 无会话态）：无 Authorization 注入、无 401 回调注册
 * （401 恒不触发于匿名通道）；保留 X-Trace-Id 注入（traceId 全链路贯穿）与 ProblemDetail 错误
 * 出口——portal 无 Element Plus，失败呈现归页面级字段文案（§5.4），拦截器职责收敛为把非 2xx
 * 归一化为 PortalApiError（detail/errorCode/status 三锚点）后原样上抛，不吞错不弹窗。
 */
import axios, { AxiosError } from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';

/** 全局唯一 Axios 实例：baseURL 复用 VITE_API_BASE_URL（dev 经 vite proxy、生产经 nginx 反代） */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15000,
});

/**
 * portal 归一化业务错误：后端 RFC 9457 ProblemDetail 的前端投影。
 * detail 为后端中文文案（可直接呈现）；errorCode 为业务错误码（OP-1003 等，文案映射锚点）；
 * status 为 HTTP 状态（4xx/5xx 分流）。message 继承 AxiosError 便于日志排查。
 */
export class PortalApiError extends Error {
  /** 后端 ProblemDetail.detail 中文文案；网络层错误（无响应体）为通用文案 */
  readonly detail: string;
  /** 业务错误码（properties.errorCode），如 OP-1003；无则为 null */
  readonly errorCode: string | null;
  /** HTTP 状态码；网络层错误（无响应）为 0 */
  readonly status: number;

  constructor(detail: string, errorCode: string | null, status: number) {
    super(detail);
    this.name = 'PortalApiError';
    this.detail = detail;
    this.errorCode = errorCode;
    this.status = status;
  }
}

/**
 * 从 Axios 错误提取 ProblemDetail.detail 文案（后端全局渲染与 workstation 同构）。
 *
 * @param error Axios 错误对象；response.data 视为 unknown 逐层收窄，防传输层脏数据
 * @return detail 文案；缺失或非字符串返回 undefined（由调用方回退默认文案）
 */
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

/** 从 Axios 错误提取业务错误码（ProblemDetail.properties.errorCode），非字符串视为无 */
function extractErrorCode(error: AxiosError): string | null {
  const properties: unknown = (error.response?.data as { properties?: unknown } | undefined)
    ?.properties;
  if (typeof properties === 'object' && properties !== null && 'errorCode' in properties) {
    const code = (properties as { errorCode?: unknown }).errorCode;
    if (typeof code === 'string' && code.length > 0) {
      return code;
    }
  }
  return null;
}

// 请求拦截器：仅注入 X-Trace-Id（每请求唯一 uuid，后端 TraceIdFilter 复用为 MDC 锚点）；
// 免登录通道禁 Authorization 注入（匿名语义，见文件头）
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers['X-Trace-Id'] = crypto.randomUUID();
  return config;
});

// 响应拦截器：非 2xx 归一化为 PortalApiError 上抛（错误呈现归页面字段文案 §5.4）；不吞错
http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (error instanceof AxiosError) {
      // 网络层错误（无响应）：detail 用通用文案、status=0；业务错误取 ProblemDetail 三锚点
      throw new PortalApiError(
        extractErrorDetail(error) ?? '网络异常，请稍后重试',
        extractErrorCode(error),
        error.response?.status ?? 0,
      );
    }
    throw error;
  },
);
