/**
 * 病区看板（WardBoardView）各作业面 composable 共用工具：日期/时点格式化、标记串拆解与
 * 业务失败兜底展示。自 WardBoardView 巨型脚本原样随迁（EX-47 拆分），行为与拆分前逐字
 * 一致；与 tempChart.ts 同为视图旁纯函数/消息工具模块（非 use 前缀，不进 src/composables
 * 全局复用目录——仅本病区看板消费）。
 */
import axios from 'axios';
import { ElMessage } from 'element-plus';

/** 本地日期串（yyyy-MM-dd，任务/交接班当日过滤共用） */
export function todayString(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** 时点展示串（MM-dd HH:mm，表格列与确认回显共用） */
export function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 病情标记逗号串拆解（condition_tags 存储「CRITICAL,SEVERE」形态） */
export function splitTags(raw: string | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);
}

/**
 * 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（如 api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文——NS 域 4xx detail 已是中文业务口径（§3.7）。
 * 调用方 catch 分支的统一出口，非 Axios 且无 detail 字段时静默（不打断在途标志复位）。
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
