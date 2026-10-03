/**
 * 护士站大屏 board 帧语义解析（纯函数零依赖，web B.1 utils 边界，镜像 iotMessage 范式）：
 * 对 /topic/nursing/board/{wardId} STOMP 帧体 JSON.parse 产物做 unknown 逐字段收窄守卫，
 * 替代脏载荷直投类型断言（web A.1-4 禁 any 口径）——信封或载荷任一字段不合法返回 null，
 * 由调用方 warn 留痕不中断订阅。
 *
 * <p>契约来源（Task 11 冻结面，后端 NurseBoardPushFrame record 逐字对齐）：统一信封
 * {type, payload, occurredAt}，type 五值冻结词表禁自增值；Long 字段（patientId/bedId/wardId）
 * 后端经 Jackson 全局字符串化（backend A.3-8）前端一律 string 承载（web A.3-6）；
 * occurredAt/planTime/triggeredAt 为日期时间 ISO-8601 字符串线格式原样承载。STOMP 载荷无
 * openapi 生成来源，手写为唯一路径（iotMessage 同先例，漂移依赖后端测试与前端同步维护）。
 *
 * <p>双形态防御注记（Task 11 minor①）：BedPatientPayload.bedNo javadoc 写「入科空占位为
 * 空串」但实现传 null——本解析对 null/空串双形态放行，非字符串非 null 归一为 null。
 */
import type { components } from '@fuyun/shared/api';

/** 输液看板契约类型别名（生成物唯一来源 A.3-3）：余量倒计时组合计算入参（页面消费） */
type InfusionBoardDeviceVO = components['schemas']['InfusionBoardDeviceVO'];

/** 床位患者动态载荷（投影行变更定位键：入科/转科/出院/床位变更四路同构承载） */
export interface BedPatientPayload {
  /** 住院就诊号（I 型 14 位），非空 */
  visitId: string;
  /** 患者主索引，字符串化 Long，非空 */
  patientId: string;
  /** 床位号文本（变更后态；入科空占位 null/空串双形态放行），可空 */
  bedNo: string | null;
  /** 变更归属病区编码（路由 topic 同源），非空 */
  wardId: string;
}

/** 任务逾期载荷（首标与升级档统一承载，escalationCount 区分档位：1 责任护士/2 护士长封顶） */
export interface OverdueTaskPayload {
  /** 任务业务号（TK+yyyyMMdd+5 位流水），非空 */
  taskNo: string;
  /** 任务类型 code（TaskType 词表），非空 */
  taskType: string;
  /** 计划时间（逾期判定基准，ISO-8601 字符串），非空 */
  planTime: string;
  /** 递增后档位（1=责任护士档、2=护士长档封顶），正整数 */
  escalationCount: number;
  /** 任务归属病区编码（路由 topic 同源），非空 */
  wardId: string;
}

/** 输注升级强提醒载荷（告警挂单升级动作——不新建任务纪律） */
export interface InfusionEscalationPayload {
  /** 告警业务号（挂单锚），非空 */
  alarmNo: string;
  /** 患者维执行单号清单（可跨患者/多病区各帧重复携带——消费侧按执行单号幂等去重） */
  executionNos: string[];
  /** 升级命中行数，非负整数 */
  escalatedCount: number;
  /** 挂接任务优先级上调行数（0=无挂接任务），非负整数 */
  taskEscalatedCount: number;
}

/** 不良事件 I/II 级上报时限超时提醒载荷（按病区聚合，非惩罚只提醒） */
export interface AdverseEventRemindPayload {
  /** 超时行归属病区编码（路由 topic 同源），非空 */
  wardId: string;
  /** 该病区 I/II 级超时行数，非负整数 */
  overdueCount: number;
  /** 样例事件号清单（后端有界 5 条防刷屏，防御口径任意长度放行） */
  sampleEventNos: string[];
}

/** 设备呼叫转发载荷（iot.call.triggered 冻结子集镜像；触发通知非全状态同步——M16 降级注记） */
export interface CallTriggeredPayload {
  /** 呼叫业务号（iot 侧触发引用），非空 */
  callNo: string;
  /** 触发设备标识，非空 */
  deviceId: string;
  /** 呼叫类型 code，非空 */
  callType: string;
  /** 床位 id（iot 域数字标识字符串化；IoTDA 直发路径可缺——null 归一），可空 */
  bedId: string | null;
  /** 病区 id（iot 域数字标识字符串化——非护理病区编码，Task 11 裁决勿映射），非空 */
  wardId: string;
  /** 触发时刻（Instant ISO-8601 字符串），非空 */
  triggeredAt: string;
}

/**
 * 护理 board 帧载荷联合（type 与 payload 一一对应，判别式收窄供页面分发）：
 * 帧信封本身即为判别式联合——消费方 switch frame.type 时 payload 自动收窄为对应载荷类型。
 */
export type NursingBoardPayload =
  | BedPatientPayload
  | OverdueTaskPayload
  | InfusionEscalationPayload
  | AdverseEventRemindPayload
  | CallTriggeredPayload;

/** 护理大屏统一信封帧（判别式联合：type 字面量与 payload 载荷一一捆绑，parseNursingBoardFrame
 * 收窄产物——消费方按 frame.type 分发时获得精确载荷类型，禁 as 断言二次收窄） */
export type NursingBoardFrame =
  | { type: 'BED_PATIENT'; payload: BedPatientPayload; occurredAt: string }
  | { type: 'TASK_OVERDUE'; payload: OverdueTaskPayload; occurredAt: string }
  | { type: 'INFUSION_ESCALATION'; payload: InfusionEscalationPayload; occurredAt: string }
  | { type: 'ADVERSE_EVENT_REMIND'; payload: AdverseEventRemindPayload; occurredAt: string }
  | { type: 'CALL_TRIGGERED'; payload: CallTriggeredPayload; occurredAt: string };

/** 非空字符串守卫 */
function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/** 可空字符串守卫（null 归一：缺失/非字符串形态归一为 null——bedNo/bedId 双形态防御） */
function nullableString(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/** 非负整数守卫（后端 int 计数；负值/小数/字符串均为脏帧） */
function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

/** 正整数守卫（档位计数从 1 起；零值与负值均为脏帧） */
function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

/** 字符串数组守卫（清单内任一残缺元素即整帧拒绝，防半截数据进渲染层） */
function isNonEmptyStringArray(value: unknown): value is string[] {
  if (!Array.isArray(value)) {
    return false;
  }
  return value.every((element) => isNonEmptyString(element));
}

/**
 * 收窄床位患者动态载荷（BED_PATIENT）：visitId/patientId/wardId 均须为非空字符串；
 * bedNo null/空串双形态放行（Task 11 minor① javadoc 措辞失准防御）、非字符串非 null 归一 null。
 *
 * @param raw 信封 payload 字段（unknown，来源不可信：网络帧可被篡改/截断）
 * @return 结构合法的 BedPatientPayload；任一必填字段不合法返回 null
 */
function parseBedPatientPayload(raw: unknown): BedPatientPayload | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { visitId, patientId, bedNo, wardId } = candidate;
  if (!isNonEmptyString(visitId) || !isNonEmptyString(patientId) || !isNonEmptyString(wardId)) {
    return null;
  }
  return { visitId, patientId, bedNo: nullableString(bedNo), wardId };
}

/**
 * 收窄任务逾期载荷（TASK_OVERDUE）：taskNo/taskType/planTime/wardId 均须为非空字符串、
 * escalationCount 正整数（档位从 1 起）。
 *
 * @param raw 信封 payload 字段（unknown，来源不可信）
 * @return 结构合法的 OverdueTaskPayload；任一必填字段不合法返回 null
 */
function parseOverdueTaskPayload(raw: unknown): OverdueTaskPayload | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { taskNo, taskType, planTime, escalationCount, wardId } = candidate;
  if (
    !isNonEmptyString(taskNo) ||
    !isNonEmptyString(taskType) ||
    !isNonEmptyString(planTime) ||
    !isPositiveInteger(escalationCount) ||
    !isNonEmptyString(wardId)
  ) {
    return null;
  }
  return { taskNo, taskType, planTime, escalationCount, wardId };
}

/**
 * 收窄输注升级载荷（INFUSION_ESCALATION）：alarmNo 非空字符串、executionNos 非空字符串数组
 * （Task 11 minor③：清单可跨患者/多病区各帧重复携带——去重幂等归消费侧承载）。
 *
 * @param raw 信封 payload 字段（unknown，来源不可信）
 * @return 结构合法的 InfusionEscalationPayload；任一必填字段不合法返回 null
 */
function parseInfusionEscalationPayload(raw: unknown): InfusionEscalationPayload | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { alarmNo, executionNos, escalatedCount, taskEscalatedCount } = candidate;
  if (
    !isNonEmptyString(alarmNo) ||
    !isNonEmptyStringArray(executionNos) ||
    !isNonNegativeInteger(escalatedCount) ||
    !isNonNegativeInteger(taskEscalatedCount)
  ) {
    return null;
  }
  return { alarmNo, executionNos, escalatedCount, taskEscalatedCount };
}

/**
 * 收窄不良事件超时提醒载荷（ADVERSE_EVENT_REMIND）：wardId 非空字符串、overdueCount 非负
 * 整数、sampleEventNos 非空字符串数组（空清单防御放行）。
 *
 * @param raw 信封 payload 字段（unknown，来源不可信）
 * @return 结构合法的 AdverseEventRemindPayload；任一必填字段不合法返回 null
 */
function parseAdverseEventRemindPayload(raw: unknown): AdverseEventRemindPayload | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { wardId, overdueCount, sampleEventNos } = candidate;
  if (
    !isNonEmptyString(wardId) ||
    !isNonNegativeInteger(overdueCount) ||
    !isNonEmptyStringArray(sampleEventNos)
  ) {
    return null;
  }
  return { wardId, overdueCount, sampleEventNos };
}

/**
 * 收窄设备呼叫转发载荷（CALL_TRIGGERED）：callNo/deviceId/callType/wardId/triggeredAt 均
 * 须为非空字符串；bedId null/缺失归一为 null（IoTDA 直发路径床号可缺）。
 *
 * @param raw 信封 payload 字段（unknown，来源不可信）
 * @return 结构合法的 CallTriggeredPayload；任一必填字段不合法返回 null
 */
function parseCallTriggeredPayload(raw: unknown): CallTriggeredPayload | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { callNo, deviceId, callType, bedId, wardId, triggeredAt } = candidate;
  if (
    !isNonEmptyString(callNo) ||
    !isNonEmptyString(deviceId) ||
    !isNonEmptyString(callType) ||
    !isNonEmptyString(wardId) ||
    !isNonEmptyString(triggeredAt)
  ) {
    return null;
  }
  return { callNo, deviceId, callType, bedId: nullableString(bedId), wardId, triggeredAt };
}

/**
 * 解析护士站大屏统一信封帧（/topic/nursing/board/{wardId}）：type 五值词表校验 + 按 type
 * 分发载荷收窄 + occurredAt 非空字符串校验——任一环节不合法整帧拒绝返回 null。
 *
 * @param raw STOMP 帧体 JSON.parse 产物（unknown，来源不可信：网络帧可被篡改/截断）
 * @return 结构合法的 NursingBoardFrame（type 与 payload 一一对应，消费方判别式分发）；
 *         不合法返回 null（调用方 warn 留痕并忽略本帧）
 */
export function parseNursingBoardFrame(raw: unknown): NursingBoardFrame | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const candidate = raw as Record<string, unknown>;
  const { type, payload, occurredAt } = candidate;
  if (!isNonEmptyString(type) || !isNonEmptyString(occurredAt)) {
    return null;
  }
  // 按词表分发载荷收窄：case 分支内 type 收窄为字面量、payload 收窄为对应载荷，组装判别式
  // 联合帧；任一环节不合法整帧拒绝返回 null
  switch (type) {
    case 'BED_PATIENT': {
      const bedPatient = parseBedPatientPayload(payload);
      return bedPatient === null ? null : { type, payload: bedPatient, occurredAt };
    }
    case 'TASK_OVERDUE': {
      const overdueTask = parseOverdueTaskPayload(payload);
      return overdueTask === null ? null : { type, payload: overdueTask, occurredAt };
    }
    case 'INFUSION_ESCALATION': {
      const escalation = parseInfusionEscalationPayload(payload);
      return escalation === null ? null : { type, payload: escalation, occurredAt };
    }
    case 'ADVERSE_EVENT_REMIND': {
      const remind = parseAdverseEventRemindPayload(payload);
      return remind === null ? null : { type, payload: remind, occurredAt };
    }
    case 'CALL_TRIGGERED': {
      const call = parseCallTriggeredPayload(payload);
      return call === null ? null : { type, payload: call, occurredAt };
    }
    default:
      // 词表外 type（五值冻结词表禁自增值）：整帧拒绝
      return null;
  }
}

/**
 * 输液余量倒计时组合计算（页面输液动态条消费，纯函数）：infusion-board 无 WS 推送主题
 * （REST 快照唯一来源），余量预计耗尽时长由余量与滴速组合推算——remainLatest（ml）除以
 * dropRateLatest（ml/h，workstation InfusionBoardView 展示同单位口径）折算分钟。
 *
 * @param device 输液看板设备行（REST InfusionBoardDeviceVO；余量/滴速无遥测数据为 null）
 * @return 预计剩余分钟数（向下取整）；余量或滴速缺失、滴速非正值（无法推算）返回 null
 */
export function infusionRemainMinutes(device: InfusionBoardDeviceVO): number | null {
  // 生成物字段形态为 number|undefined，线格式余量/滴速无数据为显式 null——经 unknown 守卫
  // 统一防御（null/undefined/非数值形态一律视为无数据）
  const remain: unknown = device.remainLatest;
  const rate: unknown = device.dropRateLatest;
  if (typeof remain !== 'number' || typeof rate !== 'number') {
    return null;
  }
  if (rate <= 0) {
    // 滴速为零或负值（停滴/脏数据）：无法推算耗尽时刻
    return null;
  }
  return Math.floor((remain / rate) * 60);
}
