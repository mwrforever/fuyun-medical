/**
 * 病区看板（WardBoardView）各作业面 composable 共用工具：本地日期串与病情标记拆解（均本病区
 * 看板专属语义）。自 WardBoardView 巨型脚本原样随迁（EX-47 拆分）；原同文件的 formatTime/
 * surfaceBizError 属全 app 通用工具，EX-43 已收拢至 src/utils/timeFormat.ts 与
 * src/utils/bizError.ts（消费方直连 app 级共享点，本文件不再转发）。
 */

/** 本地日期串（yyyy-MM-dd，任务/交接班当日过滤共用） */
export function todayString(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** 病情标记逗号串拆解（condition_tags 存储「CRITICAL,SEVERE」形态） */
export function splitTags(raw: string | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0);
}
