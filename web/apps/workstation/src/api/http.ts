/**
 * Axios 单例（web A.3-1）：全应用唯一出网口，禁组件直连 axios、禁组件自建实例。
 *
 * <p>拦截器职责收敛（A.3-2 每实例 1+1）：请求侧注入 Bearer 令牌与每请求唯一 X-Trace-Id；
 * 响应侧统一错误出口（非 2xx 经 ElMessage 统一提示，401 触发注册的未授权回调），
 * 拦截器内不落业务逻辑。与 auth store 经回调解耦（本模块禁止 import router，防循环依赖）。
 */
import axios, { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用时按需样式需手动引入（unplugin 解析器只覆盖源码标识符场景）
import 'element-plus/es/components/message/style/css';
import { useAuthStore } from '@/stores/auth';

/** 全局唯一 Axios 实例：baseURL 复用 VITE_API_BASE_URL（dev 经 vite proxy、生产经 nginx 反代） */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15000,
});

/** 401 未授权回调（由 auth store 构造时注册：清会话 + 回登录页）；null = 尚未注册 */
let unauthorizedHandler: (() => void) | null = null;

/**
 * 注册 401 统一处理回调。
 *
 * @param handler 回调函数（401 时触发一次无参调用）；传 null 可解除注册（测试隔离用）
 */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

/**
 * 从错误响应体提取 ProblemDetail.detail 文案（后端全局渲染与 401 拦截器同构）。
 *
 * @param error Axios 错误对象；response.data 视为 unknown 逐层收窄，防存储/网络层脏数据
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

// 请求拦截器：注入 Authorization 与 X-Trace-Id；useAuthStore 延迟到回调运行时调用
// （此时 pinia 已安装，符合 web B.3-1 组件外使用口径），避免模块加载期循环依赖触雷
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore();
  const token = auth.token;
  if (token !== null) {
    // 后端 AuthTokenInterceptor 按 Bearer 方案解析；令牌禁止落日志（web A.6 红线）
    config.headers.Authorization = `Bearer ${token}`;
  }
  // 每请求新生成 uuid，后端 TraceIdFilter 复用为 MDC 锚点并回写响应头（traceId 全链路贯穿）
  config.headers['X-Trace-Id'] = crypto.randomUUID();
  return config;
});

// 响应拦截器：统一错误出口——非 2xx 弹错（文案优先取 ProblemDetail.detail）；
// 401 触发未授权回调（清会话 + 回登录页，含登录口令错误场景：登录页内重复导航被 store 侧跳过）
http.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: unknown) => {
    if (error instanceof AxiosError) {
      ElMessage.error(extractErrorDetail(error) ?? '请求失败');
      if (error.response?.status === 401) {
        unauthorizedHandler?.();
      }
    }
    // 错误原样上抛（rethrow 保持原始拒绝原因）：调用方（api 层/store）据需忽略或终止流程，拦截器不吞错
    throw error;
  },
);
