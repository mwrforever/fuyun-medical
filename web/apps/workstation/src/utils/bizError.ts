/**
 * 业务失败兜底展示（web 宪法 B.2-4 同 app 多处复用下沉 utils，EX-43 收拢）：AxiosError 已由
 * 响应拦截器弹错，此处直接返回防双弹；其余形态（如 api 层直抛的 ProblemDetail 对象）展示
 * detail 原文（4xx detail 为后端中文业务口径）。调用方 catch 分支的统一出口，非 Axios 且无
 * detail 字段时静默（不打断在途标志复位）。函数体自原散落各视图的 16 处同构定义原样收拢，
 * 行为与收拢前逐字一致。
 */
import axios from 'axios';
import { ElMessage } from 'element-plus';

/**
 * 业务失败兜底展示（ElMessage 命令式调用，非组件模板依赖）。
 *
 * @param error catch 捕获的未知异常，允许为空；AxiosError 走拦截器弹错路径在此返回
 * @return 无返回值；合法 detail 经 ElMessage.error 弹出（void 前缀显式不追踪 Promise）
 */
export function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}
