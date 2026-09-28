/**
 * STOMP 帧载荷解析（纯函数零依赖，web B.1 utils 边界，bigscreen utils/iotMessage 同构移植
 * 并扩展告警载荷）：对 STOMP 帧体 JSON.parse 产物做 unknown 逐字段收窄守卫，替代脏载荷直投
 * 类型断言（web A.1-4 禁 any 口径）——任一字段不合法返回 null，由调用方 warn 留痕不中断订阅。
 */
import type { AlarmTriggeredPayload, TelemetrySummary, TelemetrySummaryItem } from '@/types/iot';

/**
 * 解析遥测摘要载荷：逐字段收窄校验（count 正整数、occurredAtUpperBound 非空字符串、items 为
 * 数组且元素 deviceId/metricCode 均为非空字符串；items 允许空数组——后端空批次不推送，防御
 * 口径仍收窄放行）。
 *
 * @param raw STOMP 帧体 JSON.parse 产物（unknown，来源不可信：网络帧可被篡改/截断）
 * @return 结构合法的 TelemetrySummary；任一字段不合法返回 null（调用方 warn 留痕并忽略本帧）
 */
export function parseTelemetrySummary(raw: unknown): TelemetrySummary | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { count, occurredAtUpperBound, items } = candidate;
  if (
    !isPositiveInteger(count) ||
    !isNonEmptyString(occurredAtUpperBound) ||
    !isUnknownArray(items)
  ) {
    return null;
  }
  const narrowedItems: TelemetrySummaryItem[] = [];
  for (const element of items) {
    // 元素逐个收窄：任一残缺元素即整帧拒绝（防半截数据进渲染层）
    if (!isSummaryItem(element)) {
      return null;
    }
    narrowedItems.push(element);
  }
  return { count, occurredAtUpperBound, items: narrowedItems };
}

/**
 * 解析告警触发载荷（后端 AlarmTriggeredPayload 十字段冻结契约）：必填九字段均须非空字符串
 * （wardId/patientId/ruleId 等后端 Long 已字符串化，前端 string 承载），patientId/visitId
 * 允许 null（无患者关联的公共区域告警）。
 *
 * @param raw STOMP 帧体 JSON.parse 产物（unknown，来源不可信）
 * @return 结构合法的 AlarmTriggeredPayload；任一必填字段不合法返回 null
 */
export function parseAlarmPayload(raw: unknown): AlarmTriggeredPayload | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const {
    alarmNo,
    deviceId,
    patientId,
    visitId,
    wardId,
    alarmLevel,
    metricCode,
    triggerValue,
    ruleId,
    occurredAt,
  } = candidate;
  if (
    !isNonEmptyString(alarmNo) ||
    !isNonEmptyString(deviceId) ||
    !isNullableString(patientId) ||
    !isNullableString(visitId) ||
    !isNonEmptyString(wardId) ||
    !isNonEmptyString(alarmLevel) ||
    !isNonEmptyString(metricCode) ||
    !isNonEmptyString(triggerValue) ||
    !isNonEmptyString(ruleId) ||
    !isNonEmptyString(occurredAt)
  ) {
    return null;
  }
  return {
    alarmNo,
    deviceId,
    patientId,
    visitId,
    wardId,
    alarmLevel,
    metricCode,
    triggerValue,
    ruleId,
    occurredAt,
  };
}

/** 非空字符串守卫 */
function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/** 可空字符串守卫（null 放行——后端可空字段经 Jackson 输出 null） */
function isNullableString(value: unknown): value is string | null {
  return value === null || isNonEmptyString(value);
}

/** 正整数守卫（后端 int count 恒整数；零值与负值均为脏帧） */
function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

/** 数组守卫（显式 unknown[] 元素类型，规避 Array.isArray 收窄到 any[] 的禁 any 冲突） */
function isUnknownArray(value: unknown): value is unknown[] {
  return Array.isArray(value);
}

/** 摘要明细元素守卫：deviceId 与 metricCode 均须为非空字符串 */
function isSummaryItem(value: unknown): value is TelemetrySummaryItem {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const item = value as Record<string, unknown>;
  return isNonEmptyString(item['deviceId']) && isNonEmptyString(item['metricCode']);
}
