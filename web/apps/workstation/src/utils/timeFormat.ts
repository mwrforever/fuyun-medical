/**
 * 时点展示格式化（web 宪法 B.2-4 同 app 多处复用下沉 utils，EX-43 收拢）：后端 ISO 时点串 →
 * 中文表格列惯用的 MM-dd HH:mm 展示串。函数体自原散落各视图的 15 处同构定义原样收拢，行为与
 * 收拢前逐字一致（nursing/tempChart.ts 的同名函数为体温单小时整点刻度专用、语义不同，未收拢）。
 */

/**
 * 时点展示串（MM-dd HH:mm，表格列与确认回显共用）。
 *
 * @param raw 后端输出的 ISO 时点串，允许为空（空串/undefined 渲染占位符「—」）
 * @return MM-dd HH:mm 展示串；入参为空时返回「—」
 */
export function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
