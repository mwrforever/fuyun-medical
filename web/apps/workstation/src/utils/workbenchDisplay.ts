/**
 * 工作站工作台展示纯函数集（册 2 审查修复波 2 自 useWorkbenchFeed 迁入，web B.1 目录职责
 * 边界：无状态纯函数归 utils，composables 只留数据获取与副作用编排）：叫号帧载荷收窄、
 * 趋势周同比推导与计数展示格式化三件，全部供工作站首页消费。纯函数零业务状态、零 vue 依赖，
 * 行为与迁移前一致（本目录 spec 与 HomeView.spec 渲染断言共同承载回归）。
 */

/** 叫号帧载荷冻结契约（后端 QueueCalledNotice record 五字段）：患者姓名字段收窄后不渲染
 * （首页全院面脱敏从严，票号+诊室已可定位），doctorId 为路由内部标识亦不渲染 */
interface QueueCalledFrame {
  type: string;
  ticketNo: string;
  room: string;
}

/**
 * 叫号帧载荷收窄（unknown 逐字段守卫，禁 any 口径）：五字段冻结契约中取渲染所需三字段，
 * type/ticketNo 必填非空字符串、room 允许缺失；patientName/doctorId 存在即弃（不渲染）。
 *
 * @param raw STOMP 帧体 JSON.parse 产物（unknown，来源不可信）
 * @return 结构合法返回帧对象；任一必填字段不合法返回 null（调用方 warn 留痕忽略本帧）
 */
export function parseQueueCalledFrame(raw: unknown): QueueCalledFrame | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { type, ticketNo, room } = candidate;
  if (typeof type !== 'string' || type === '' || typeof ticketNo !== 'string' || ticketNo === '') {
    return null;
  }
  return { type, ticketNo, room: typeof room === 'string' ? room : '' };
}

/**
 * 趋势周同比推导（纯函数，零伪数据边界内的真实推导）：近 7 日=当期、前 7 日=上周同期，
 * 同比=两组真实和值之差除以上周和值（百分数一位小数，带符号）。
 *
 * @param current 当期 7 日接诊计数（COUNT 聚合小整数，展示级数值化）
 * @param previous 上周同期 7 日接诊计数
 * @return 形如 +4.8 / -2.1 的同比百分数；上周和值为 0 时返回 null（分母为零诚实缺示不造数）
 */
export function weekOverWeekPercent(current: number[], previous: number[]): number | null {
  const sum = (values: number[]): number => values.reduce((acc, value) => acc + value, 0);
  const previousSum = sum(previous);
  if (previousSum === 0) {
    return null;
  }
  return Math.round(((sum(current) - previousSum) / previousSum) * 1000) / 10;
}

/** 计数展示格式化（千分位，展示级）：非数字串（脏数据/缺失）返回 — 不造数 */
export function formatCount(value: string | undefined): string {
  if (value === undefined || value === '') {
    return '—';
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return '—';
  }
  return parsed.toLocaleString('zh-CN');
}
