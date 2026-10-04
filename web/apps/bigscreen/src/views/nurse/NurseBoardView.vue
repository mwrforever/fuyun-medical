<script setup lang="ts">
// 护士站大屏（FU-M05-08 前端面，Task 17）：暗色五区布局——①床位总览墙（护理级别色阶/
// 责任护士/风险标记）②未确认告警列（iot.alarm 直订 + 呼叫转发行，alarmNo/callNo 去重截断）
// ③输液动态条（infusion-board REST 组合余量倒计时 + 输注升级执行单号幂等行）④任务逾期
// 看板（board overdueTasks 段 + TASK_OVERDUE 帧前插，护士长档闪烁）⑤出入院动态滚动条
// （board admissions 段近 24h 时间线）。危急值段（M07 预留固定空数组）不渲染。
//
// 双端点订阅（web B.3-3）：/ws/nursing 经 useNursingStomp 单例（匿名短期令牌运行期签发——
// useQueueStomp 先例）订 board 主题；/ws/iot 经 useIotStomp 单例（sessionStorage 令牌，与
// 运营大屏同键面）订 alarm/telemetry/device-status 三主题。WS 断连 REST 10s 轮询降级
// （护理/设备双通道独立门控）+ 页面隐藏暂停消费（EX-41：轮询与信号帧零出网，恢复可见立刷）。
// 本组件只做组装与编排（DashboardView 同款先例），帧收窄在 utils/nursingMessage。
// WS 派生行 TTL 退役（D-4）：快照无「解除」帧，长时值守大屏的 WS 前插行按首见时间戳
// 在快照/帧入口统一清理——逾期 wsOnly 行 10 分钟宽限窗/呼叫行 5 分钟/升级行 30 分钟，
// 防过期行永久驻留误导值守（快照行自带生命周期不入册，不受 TTL 管）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { alarms } from '@/api/iot';
import type { AlarmVO } from '@/api/iot';
import { nursing, ward } from '@/api/nursing';
import type { AdmissionRow, BedRow, InfusionBoardDeviceRow, OverdueTaskRow } from '@/api/nursing';
import {
  IOT_TOKEN_STORAGE_KEY,
  alarmTopicPath,
  connect as iotConnect,
  connectionState as iotConnectionState,
  deviceStatusTopicPath,
  disconnect as iotDisconnect,
  subscribeIotTopic,
  subscribeTelemetrySummary,
} from '@/composables/useIotStomp';
import {
  connect as nursingConnect,
  connectionState as nursingConnectionState,
  disconnect as nursingDisconnect,
  subscribeBoard,
  tokenFailed,
} from '@/composables/useNursingStomp';
import type { StompSubscription } from '@stomp/stompjs';
import { parseIotAlarmFrame } from '@/utils/iotMessage';
import { infusionRemainMinutes } from '@/utils/nursingMessage';
import type {
  AdverseEventRemindPayload,
  CallTriggeredPayload,
  InfusionEscalationPayload,
  NursingBoardFrame,
  OverdueTaskPayload,
} from '@/utils/nursingMessage';
import type { IotAlarmFrame } from '@/types/iot';

/** 病区 ID 合法形态：纯数字字符串——CALL_TRIGGERED 帧按 iot 数字病区 id 路由、其余四类按护理编码路由；依赖 org_code 与 sys_org id 数字串同值部署约定（演示 '1001' 同值成立）；失配时 CALL_TRIGGERED 帧静默丢失——映射面收口归 M16 联调冻结（工单 W-74） */
const WARD_ID_PATTERN = /^\d+$/;

/** 路由 query 缺省病区（演示病区，与运营大屏示例值一致；书签化部署按 query 覆盖） */
const DEFAULT_WARD_ID = '1001';

/** REST 轮询降级周期（毫秒，brief 冻结值 10s；双通道各自 WS 就绪时跳过） */
const REST_POLL_INTERVAL_MS = 10000;

/** 告警列容量（iot 告警帧 + 呼叫行共用，前插 + 截断防长时值守内存无界增长——运营大屏同款） */
const ALARM_LIST_CAPACITY = 20;

/** 输注升级行容量（执行单号幂等清单截断，同告警列口径） */
const ESCALATION_LIST_CAPACITY = 20;

/** 帧驱动刷新最小间隔（毫秒）：信号帧 2s 窗口合并，短窗内多帧只触发一次拉取（工作站先例） */
const FRAME_REFRESH_MIN_INTERVAL_MS = 2000;

/** D-4：逾期看板 WS 前插行宽限窗（毫秒，裁决固化 10 分钟）——快照持续未确认的 wsOnly 行首见后超窗退役 */
const WS_ROW_GRACE_MS = 10 * 60 * 1000;

/** D-4：WS 呼叫行 TTL（毫秒，裁决固化 5 分钟）——呼叫无确认回执帧，超时自动退役防驻留 */
const CALL_ROW_TTL_MS = 5 * 60 * 1000;

/** D-4：输注升级行 TTL（毫秒，裁决固化 30 分钟）——升级无解除帧，超时自动退役防驻留 */
const ESCALATION_TTL_MS = 30 * 60 * 1000;

/* ---------- 词表（code → 展示词 + 语义 tone；词表外 code 原样展示不炸渲染） ---------- */

/** 护理级别（SPECIAL 特级红/CRITICAL 危重橙/NORMAL 普通默认） */
const NURSING_LEVEL_META: Record<string, { text: string; tone: string }> = {
  SPECIAL: { text: '特级护理', tone: 'is-alert' },
  CRITICAL: { text: '危重护理', tone: 'is-warn' },
  NORMAL: { text: '普通护理', tone: '' },
};

/** 风险标记（投影 risk_flags 逗号分隔镜像：跌倒/压疮） */
const RISK_FLAG_META: Record<string, string> = {
  FALL: '跌倒',
  PRESSURE: '压疮',
};

/** 任务类型（后端 TaskType 词表常用面；词表外 code 原样） */
const TASK_TYPE_META: Record<string, string> = {
  MEDICATION: '给药',
  INFUSION_CARE: '输液护理',
  TURN: '翻身',
  PATROL: '巡视',
  SPECIMEN: '标本采集',
  IO_MONITOR: '出入量监测',
  IOT_LINKAGE: '联动任务',
  ASSESS_REMIND: '评估提醒',
  MANUAL: '手工任务',
  PREVENTION: '预防措施',
};

/** 逾期升级档位（1=责任护士档、2=护士长档封顶） */
const ESCALATION_META: Record<number, string> = {
  1: '责任护士档',
  2: '护士长档',
};

/** 输液告警档位（后端 mapAlertLevel 判定：黄 ≤15ml/橙 ≤10ml/红 ≤5ml） */
const INFUSION_LEVEL_META: Record<string, { text: string; tone: string }> = {
  NONE: { text: '无告警', tone: 'is-muted' },
  YELLOW: { text: '黄档', tone: 'is-warn' },
  ORANGE: { text: '橙档', tone: 'is-warn' },
  RED: { text: '红档', tone: 'is-alert' },
};

/** iot 告警级别（危急红/警告橙/提示蓝——运营大屏同款词表） */
const ALARM_LEVEL_META: Record<string, { text: string; tone: string }> = {
  CRITICAL: { text: '危急', tone: 'is-alert' },
  WARNING: { text: '警告', tone: 'is-warn' },
  INFO: { text: '提示', tone: 'is-info' },
};

/** 出入院动态行类型（brief 冻结词表 ADMIT/DISCHARGE） */
const ADMISSION_TYPE_META: Record<string, { text: string; tone: string }> = {
  ADMIT: { text: '入科', tone: 'is-ok' },
  DISCHARGE: { text: '出院', tone: 'is-muted' },
};

/** 路由注入（wardId query 书签化读取） */
const route = useRoute();

/** 病区 ID（路由 query 书签化；非法/缺失回退演示病区） */
const wardId = ref(readWardFromRoute());

function readWardFromRoute(): string {
  const raw = route.query['wardId'];
  return typeof raw === 'string' && WARD_ID_PATTERN.test(raw) ? raw : DEFAULT_WARD_ID;
}

/* ---------- ① 床位总览墙（board beds 段 + BED_PATIENT 帧信号节流刷新） ---------- */
const bedRows = ref<BedRow[]>([]);

/** 床位号展示（bedNo null/空串双形态防御——入科空占位显示「待排床」） */
function bedLabel(bedNo: string | null | undefined): string {
  return bedNo === null || bedNo === undefined || bedNo === '' ? '待排床' : `${bedNo} 床`;
}

function levelMeta(code: string | undefined): { text: string; tone: string } {
  return NURSING_LEVEL_META[code ?? ''] ?? { text: code ?? '-', tone: '' };
}

/** 风险标记 chips（逗号分隔镜像拆分；空值/空串渲染空清单） */
function riskChips(riskFlags: string | null | undefined): string[] {
  if (riskFlags === null || riskFlags === undefined || riskFlags === '') {
    return [];
  }
  return riskFlags
    .split(',')
    .map((flag) => flag.trim())
    .filter((flag) => flag !== '')
    .map((flag) => RISK_FLAG_META[flag] ?? flag);
}

/* ---------- ② 未确认告警列（iot alarm 直订 + CALL_TRIGGERED 行 + REST 兜底） ---------- */
/** 告警行视图模型（iot 告警/呼叫转发/REST 兜底三源公共展示投影） */
interface AlertRowVM {
  /** 去重键：iot alarmNo / 呼叫 callNo */
  key: string;
  /** 来源类别（IOT 设备告警 / CALL 呼叫转发） */
  kind: 'IOT' | 'CALL';
  /** 级别词 + 语义 tone（呼叫行以 callType 承载） */
  levelText: string;
  levelTone: string;
  /** 业务号（告警号/呼叫号） */
  title: string;
  /** 描述（指标+触发值 / 呼叫类型+设备） */
  desc: string;
  /** 发生时刻（ISO 原样，展示侧 HH:mm 格式化） */
  time: string;
}

const alertRows = ref<AlertRowVM[]>([]);

function levelMetaOf(level: string | undefined): { text: string; tone: string } {
  return ALARM_LEVEL_META[level ?? ''] ?? { text: level ?? '-', tone: 'is-muted' };
}

/** 告警行前插（按 key 去重：重复帧先移除旧行再置顶，容量截断防内存无界） */
function prependAlertRow(row: AlertRowVM): void {
  // D-4：WS 呼叫行入首见册（TTL 退役锚点，首见后不随重复呼叫帧重置）；IOT 告警行走快照生命周期不入册
  if (row.kind === 'CALL' && !callRowFirstSeen.has(row.key)) {
    callRowFirstSeen.set(row.key, Date.now());
  }
  alertRows.value = [row, ...alertRows.value.filter((existing) => existing.key !== row.key)].slice(
    0,
    ALARM_LIST_CAPACITY,
  );
}

/** REST 活跃告警兜底行 → 展示投影（lastTriggeredAt 为业务发生时刻锚点） */
function toAlertRow(alarm: AlarmVO): AlertRowVM {
  const level = levelMetaOf(alarm.alarmLevel);
  return {
    key: alarm.alarmNo ?? '',
    kind: 'IOT',
    levelText: level.text,
    levelTone: level.tone,
    title: alarm.alarmNo ?? '',
    desc: `${alarm.metricCode ?? '-'} ${alarm.triggerValue ?? ''}`.trim(),
    time: alarm.lastTriggeredAt ?? '',
  };
}

/** REST 兜底装载：替换设备告警行、保留 WS 呼叫行在前（呼叫通道独立于 iot 连接态） */
function mergeAlertSnapshot(rows: AlertRowVM[]): void {
  // D-4 统一清理入口：过期 WS 呼叫行先出清（快照 IOT 行无册记，随快照全量替换自生灭）
  purgeExpiredRows(Date.now());
  const snapshotKeys = new Set(rows.map((row) => row.key));
  // 快照覆盖到的 WS 呼叫行注销册记转快照生命周期（行随快照段自生灭，TTL 不再管辖）
  for (const key of snapshotKeys) {
    callRowFirstSeen.delete(key);
  }
  const callRows = alertRows.value.filter(
    (existing) => existing.kind === 'CALL' && !snapshotKeys.has(existing.key),
  );
  alertRows.value = [...callRows, ...rows].slice(0, ALARM_LIST_CAPACITY);
}

/* ---------- ③ 输液动态条（infusion-board REST + 遥测/设备状态信号刷新 + 升级行） ---------- */
const infusionDevices = ref<InfusionBoardDeviceRow[]>([]);

/** 输注升级行视图模型（按执行单号扁平展开——幂等去重锚） */
interface EscalationRowVM {
  executionNo: string;
  alarmNo: string;
}

const escalationRows = ref<EscalationRowVM[]>([]);

function infusionLevelMeta(code: string | undefined): { text: string; tone: string } {
  return INFUSION_LEVEL_META[code ?? ''] ?? { text: code ?? '-', tone: 'is-muted' };
}

/**
 * 输注升级帧前插（Task 11 minor③：executionNos 可跨患者/多病区各帧重复携带——按执行单号
 * 幂等去重，重复帧先移除旧行再置顶，容量截断防内存无界）
 */
function prependEscalationRows(payload: InfusionEscalationPayload): void {
  // D-4 统一清理入口：升级行唯一 WS 入口，帧到达先清过期行
  purgeExpiredRows(Date.now());
  const incoming = payload.executionNos.map((executionNo) => ({
    executionNo,
    alarmNo: payload.alarmNo,
  }));
  // 新升级行入首见册（TTL 退役锚点，首见后不随重复帧重置）
  const now = Date.now();
  for (const row of incoming) {
    if (!escalationFirstSeen.has(row.executionNo)) {
      escalationFirstSeen.set(row.executionNo, now);
    }
  }
  const incomingKeys = new Set(incoming.map((row) => row.executionNo));
  escalationRows.value = [
    ...incoming,
    ...escalationRows.value.filter((existing) => !incomingKeys.has(existing.executionNo)),
  ].slice(0, ESCALATION_LIST_CAPACITY);
}

/** 余量倒计时展示（组合计算归 utils/nursingMessage 纯函数；无数据占位 —） */
function remainText(device: InfusionBoardDeviceRow): string {
  const minutes = infusionRemainMinutes(device);
  return minutes === null ? '—' : `${minutes} 分钟`;
}

/** 余量/滴速展示（后端无数据落 null 非 undefined，nullish 双判占位不渲染 null 字面量） */
function metricText(value: number | null | undefined): string {
  return value == null ? '—' : String(value);
}

/* ---------- ④ 任务逾期看板（board overdueTasks 段 + TASK_OVERDUE 帧前插升级闪烁） ---------- */
/** 逾期行视图模型（REST 行与 WS 帧公共投影；escalationCount 驱动档位词与闪烁类） */
interface OverdueRowVM {
  taskNo: string;
  taskType: string;
  planTime: string;
  escalationCount: number;
}

const overdueRows = ref<OverdueRowVM[]>([]);

/** 不良事件上报超时提醒（ADVERSE_EVENT_REMIND 最近一帧；null=无） */
const adverseRemind = ref<AdverseEventRemindPayload | null>(null);

function taskTypeText(code: string | undefined): string {
  return TASK_TYPE_META[code ?? ''] ?? code ?? '-';
}

function escalationText(count: number): string {
  return ESCALATION_META[count] ?? `第 ${count} 档`;
}

/** WS 逾期帧装载：同号行覆盖并移顶（重复首标/升档刷新，防同号双行） */
function upsertOverdueFromFrame(payload: OverdueTaskPayload): void {
  // D-4：新 WS 逾期行入首见册（宽限窗退役锚点，首见后不随升档重复帧重置）
  if (!wsRowFirstSeen.has(payload.taskNo)) {
    wsRowFirstSeen.set(payload.taskNo, Date.now());
  }
  const rest = overdueRows.value.filter((row) => row.taskNo !== payload.taskNo);
  overdueRows.value = [
    {
      taskNo: payload.taskNo,
      taskType: payload.taskType,
      planTime: payload.planTime,
      escalationCount: payload.escalationCount,
    },
    ...rest,
  ];
}

/** REST 快照逾期段装载：同号行取升级档更高者，WS 前插行（快照未覆盖）保留在前 */
function mergeOverdueSnapshot(rows: OverdueTaskRow[]): void {
  // D-4 统一清理入口：超宽限窗的 wsOnly 行先出清（快照持续未确认视为后端已解除）
  purgeExpiredRows(Date.now());
  const snapshot = rows.map((row) => ({
    taskNo: row.taskNo ?? '',
    taskType: row.taskType ?? '',
    planTime: row.planTime ?? '',
    escalationCount: row.escalationCount ?? 0,
  }));
  const snapshotKeys = new Set(snapshot.map((row) => row.taskNo));
  // 快照覆盖行注销册记转快照生命周期（下一轮快照无此行即自然消失，不受宽限窗误伤）
  for (const taskNo of snapshotKeys) {
    wsRowFirstSeen.delete(taskNo);
  }
  // wsOnly=仍在册的 WS 派生行（未入册=快照生命周期行或已被快照接管后撤销的行，不保留）
  const wsOnly = overdueRows.value.filter(
    (row) => !snapshotKeys.has(row.taskNo) && wsRowFirstSeen.has(row.taskNo),
  );
  const merged = snapshot.map((row) => {
    const fromWs = overdueRows.value.find((existing) => existing.taskNo === row.taskNo);
    return fromWs !== undefined && fromWs.escalationCount > row.escalationCount ? fromWs : row;
  });
  overdueRows.value = [...wsOnly, ...merged];
}

/* ---------- D-4 WS 派生行 TTL 退役（三族统一清理——快照无「解除」帧的驻留兜底） ---------- */
/** WS 派生行首见册（毫秒时间戳，键=行去重键：taskNo/告警行 key/executionNo）——只登记 WS 前插行，快照行自带生命周期不入册 */
const wsRowFirstSeen = new Map<string, number>();
const callRowFirstSeen = new Map<string, number>();
const escalationFirstSeen = new Map<string, number>();

/** 首见时间戳是否已过档位时限（无册记=快照生命周期行，不受 TTL 管） */
function expired(firstSeen: number | undefined, now: number, ttl: number): boolean {
  return firstSeen !== undefined && now - firstSeen > ttl;
}

/** 册记对账：行已不在数组的册记条目删除（覆盖过期清除/容量截断/快照接管三路，防 Map 无界增长） */
function reconcileFirstSeen(map: Map<string, number>, liveKeys: Set<string>): void {
  for (const key of map.keys()) {
    if (!liveKeys.has(key)) {
      map.delete(key);
    }
  }
}

/**
 * 三族统一清理入口（mergeOverdueSnapshot/mergeAlertSnapshot/prependEscalationRows 三入口调用）：
 * 过期 WS 派生行从数组出清 + 册记对账双清。无变化时不重赋值（避免无谓响应性触发）。
 */
function purgeExpiredRows(now: number): void {
  // ④ 逾期看板 wsOnly 行：超 10 分钟宽限窗退役
  if (
    overdueRows.value.some((row) => expired(wsRowFirstSeen.get(row.taskNo), now, WS_ROW_GRACE_MS))
  ) {
    overdueRows.value = overdueRows.value.filter(
      (row) => !expired(wsRowFirstSeen.get(row.taskNo), now, WS_ROW_GRACE_MS),
    );
  }
  // ② 告警列 WS 呼叫行：超 5 分钟 TTL 退役（快照 IOT 行无册记不在此列）
  if (alertRows.value.some((row) => expired(callRowFirstSeen.get(row.key), now, CALL_ROW_TTL_MS))) {
    alertRows.value = alertRows.value.filter(
      (row) => !expired(callRowFirstSeen.get(row.key), now, CALL_ROW_TTL_MS),
    );
  }
  // ③ 输注升级行：超 30 分钟 TTL 退役
  if (
    escalationRows.value.some((row) =>
      expired(escalationFirstSeen.get(row.executionNo), now, ESCALATION_TTL_MS),
    )
  ) {
    escalationRows.value = escalationRows.value.filter(
      (row) => !expired(escalationFirstSeen.get(row.executionNo), now, ESCALATION_TTL_MS),
    );
  }
  reconcileFirstSeen(wsRowFirstSeen, new Set(overdueRows.value.map((row) => row.taskNo)));
  reconcileFirstSeen(
    callRowFirstSeen,
    new Set(alertRows.value.filter((row) => row.kind === 'CALL').map((row) => row.key)),
  );
  reconcileFirstSeen(
    escalationFirstSeen,
    new Set(escalationRows.value.map((row) => row.executionNo)),
  );
}

/* ---------- ⑤ 出入院动态滚动条（board admissions 段近 24h 时间线） ---------- */
const admissionRows = ref<AdmissionRow[]>([]);

function admissionMeta(type: string | undefined): { text: string; tone: string } {
  return ADMISSION_TYPE_META[type ?? ''] ?? { text: type ?? '-', tone: 'is-muted' };
}

/* ---------- 时刻格式化（ISO → MM-dd HH:mm / HH:mm；解析失败回退原始字符串） ---------- */
/**
 * 北京钟面格式化器（时区钉扎，后端 HEALTHCARE_TZ 纪律的前端面）：大屏业务时刻（入区/
 * 计划时点/告警与出入院时点）一律按 Asia/Shanghai 钟面展示，与浏览器/CI 运行时区解耦——
 * CI 跑在 UTC 时钟面不漂移。模块级单例复用（DateTimeFormat 无状态可复用）；hourCycle
 * 取 h23 规避午夜 24 点形态。bigscreen 无共享时区常量面，故本地钉扎（后续他页同需求再提炼）。
 */
const beijingClockFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** 北京钟面字段槽位（formatToParts 的字面量分隔符部件经槽位判别过滤） */
type BeijingClockPart = 'month' | 'day' | 'hour' | 'minute';

/** 北京钟面四字段抽取（各两位；非法时刻已在调用侧 NaN 拦截，不进入本函数） */
function beijingClockParts(date: Date): Record<BeijingClockPart, string> {
  const parts: Record<BeijingClockPart, string> = { month: '', day: '', hour: '', minute: '' };
  for (const part of beijingClockFormatter.formatToParts(date)) {
    if (
      part.type === 'month' ||
      part.type === 'day' ||
      part.type === 'hour' ||
      part.type === 'minute'
    ) {
      parts[part.type] = part.value;
    }
  }
  return parts;
}

function formatDayClock(iso: string | undefined): string {
  return formatWith(iso, false);
}

function formatClock(iso: string | undefined): string {
  return formatWith(iso, true);
}

function formatWith(iso: string | undefined, timeOnly: boolean): string {
  if (iso === undefined || iso === '') {
    return '-';
  }
  const date = new Date(iso);
  // 解析失败回退原始字符串（词表外数据不炸渲染）；NaN 必须先拦截——formatToParts 遇无效时刻抛 RangeError
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const { month, day, hour, minute } = beijingClockParts(date);
  return timeOnly ? `${hour}:${minute}` : `${month}-${day} ${hour}:${minute}`;
}

/* ---------- REST 首屏 + 10s 轮询降级编排（护理/设备双通道独立门控） ---------- */
/** REST 加载失败标记（任一资源失败置位，横幅提示；下一轮轮询自愈） */
const restFailed = ref(false);

/** 访问令牌（setup 期一次性读取；大屏无登录页，令牌经遥测页注入 sessionStorage，键值禁入日志） */
const storedToken = sessionStorage.getItem(IOT_TOKEN_STORAGE_KEY) ?? '';

/** 访问令牌缺失（缺失=iot WS 链路停用，告警列/输液信号以 REST 轮询承载） */
const tokenMissing = ref(storedToken === '');

/** 护理大屏四段快照加载（Redis TTL 5s read-through，轮询侧天然节流） */
async function loadBoard(): Promise<void> {
  const board = await nursing.board(wardId.value);
  bedRows.value = board.beds ?? [];
  mergeOverdueSnapshot(board.overdueTasks ?? []);
  admissionRows.value = board.admissions ?? [];
}

/** 输液看板快照加载（余量/滴速/档位 REST 唯一来源——无 WS 推送主题） */
async function loadInfusion(): Promise<void> {
  const board = await ward.infusionBoard(wardId.value);
  infusionDevices.value = board.devices ?? [];
}

/** 活跃告警分页加载（iot WS 断连期告警列兜底数据源） */
async function loadAlarms(): Promise<void> {
  const page = await alarms.list({
    page: 1,
    size: ALARM_LIST_CAPACITY,
    wardId: wardId.value,
    status: 'ACTIVE',
  });
  mergeAlertSnapshot((page.content ?? []).map(toAlertRow));
}

/** REST 三资源首屏加载（Promise.allSettled 并行：单资源失败不拖垮其余区域首屏） */
async function loadAll(): Promise<void> {
  const results = await Promise.allSettled([loadBoard(), loadInfusion(), loadAlarms()]);
  if (results.some((result) => result.status === 'rejected')) {
    restFailed.value = true;
  }
}

/**
 * 轮询 tick（10s）：页面隐藏暂停（EX-41）、各通道 WS 已连接跳过（推送/信号驱动承载），
 * 否则对应资源 REST 降级刷新。恢复可见时经 visibilitychange 立即调用一次（隐藏期变更
 * 一次补齐，工作站先例）。
 */
function pollTick(): void {
  if (document.hidden) {
    return;
  }
  const loaders: Promise<void>[] = [];
  if (nursingConnectionState.value !== 'connected') {
    loaders.push(loadBoard());
  }
  if (iotConnectionState.value !== 'connected') {
    loaders.push(loadInfusion(), loadAlarms());
  }
  if (loaders.length === 0) {
    return;
  }
  void Promise.allSettled(loaders).then((results) => {
    restFailed.value = results.some((result) => result.status === 'rejected');
  });
}

/* ---------- 帧驱动节流刷新（BED_PATIENT 定位键信号 / 遥测与设备状态输液信号） ---------- */
/** 最近一次 board 信号刷新时刻（毫秒时间戳；节流防短窗多帧重复拉取） */
let lastBoardRefreshAt = 0;

/** 最近一次输液信号刷新时刻（毫秒时间戳） */
let lastInfusionRefreshAt = 0;

/** board 快照节流刷新（BED_PATIENT 帧仅定位键无展示字段——信号驱动的快照拉取） */
function scheduleBoardRefresh(): void {
  if (document.hidden) {
    // 隐藏态丢信号帧：不出网（后台标签页零请求，EX-41），节流时钟也不推进
    return;
  }
  const now = Date.now();
  if (now - lastBoardRefreshAt < FRAME_REFRESH_MIN_INTERVAL_MS) {
    return;
  }
  lastBoardRefreshAt = now;
  void loadBoard().catch(() => {
    restFailed.value = true;
  });
}

/** 输液看板节流刷新（遥测摘要/设备状态帧作为「有新数据」信号，workstation 同款形态） */
function scheduleInfusionRefresh(): void {
  if (document.hidden) {
    return;
  }
  const now = Date.now();
  if (now - lastInfusionRefreshAt < FRAME_REFRESH_MIN_INTERVAL_MS) {
    return;
  }
  lastInfusionRefreshAt = now;
  void loadInfusion().catch(() => {
    restFailed.value = true;
  });
}

/* ---------- /ws/nursing board 主题帧分发（五类型判别式） ---------- */
function onNursingFrame(frame: NursingBoardFrame): void {
  switch (frame.type) {
    case 'BED_PATIENT':
      // 投影行变更四路同构承载（无子类型字段可辨入科/出院）：定位键信号 → 节流快照刷新
      scheduleBoardRefresh();
      break;
    case 'TASK_OVERDUE':
      upsertOverdueFromFrame(frame.payload);
      break;
    case 'INFUSION_ESCALATION':
      prependEscalationRows(frame.payload);
      break;
    case 'ADVERSE_EVENT_REMIND':
      adverseRemind.value = frame.payload;
      break;
    case 'CALL_TRIGGERED':
      prependCallRow(frame.payload);
      break;
  }
}

/** 呼叫转发行装载（前插告警列，callNo 去重；级别槽以呼叫类型 code 承载） */
function prependCallRow(payload: CallTriggeredPayload): void {
  prependAlertRow({
    key: payload.callNo,
    kind: 'CALL',
    levelText: payload.callType,
    levelTone: 'is-warn',
    title: payload.callNo,
    desc: `${payload.callType} ${payload.deviceId}`,
    time: payload.triggeredAt,
  });
}

/* ---------- /ws/iot 三主题消费（告警直订 + 输液刷新信号） ---------- */
/** iot 告警帧 → 告警列前插（payload 已经 useIotStomp 管线收窄为强类型） */
function onIotAlarmFrame(frame: IotAlarmFrame): void {
  const level = levelMetaOf(frame.alarmLevel);
  prependAlertRow({
    key: frame.alarmNo,
    kind: 'IOT',
    levelText: level.text,
    levelTone: level.tone,
    title: frame.alarmNo,
    desc: `${frame.metricCode} ${frame.triggerValue}`,
    time: frame.occurredAt,
  });
}

/**
 * 设备状态帧最小消费面（本页仅作输液看板刷新信号——设备上下线影响看板设备行，全字段
 * 展示不消费；窄化为 deviceId 非空字符串的信号形态）
 */
interface DeviceStatusSignal {
  deviceId: string;
}

function parseDeviceStatusSignal(raw: unknown): DeviceStatusSignal | null {
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const deviceId = (raw as Record<string, unknown>)['deviceId'];
  return typeof deviceId === 'string' && deviceId !== '' ? { deviceId } : null;
}

/* ---------- 订阅句柄登记与生命周期 ---------- */
/** 全页订阅句柄（nursing board + iot 三主题；卸载全量退订） */
const subscriptions: StompSubscription[] = [];

/** 轮询定时器句柄（全页唯一定时器；卸载必清理——长时值守零泄漏） */
let pollTimer: ReturnType<typeof setInterval> | null = null;

/** 页面可见性恢复：立刷一轮兜底（EX-41 恢复可见语义） */
function onVisibilityChange(): void {
  if (!document.hidden) {
    pollTick();
  }
}

onMounted(() => {
  // REST 首屏先行（匿名只读面，不依赖令牌）；各通道 WS 就绪后由推送/信号驱动、轮询退位
  void loadAll().catch(() => {
    restFailed.value = true;
  });
  // 护理链路：匿名短期令牌自动签发建连（useQueueStomp 先例），board 主题恒订阅
  nursingConnect(wardId.value);
  subscriptions.push(subscribeBoard(wardId.value, onNursingFrame));
  // 设备链路：令牌经遥测页注入 sessionStorage（运营大屏同键面），缺失时不建连不订阅
  if (storedToken !== '') {
    iotConnect({ token: storedToken, wardId: wardId.value });
    subscriptions.push(
      subscribeIotTopic(alarmTopicPath(wardId.value), parseIotAlarmFrame, onIotAlarmFrame),
    );
    subscriptions.push(subscribeTelemetrySummary(wardId.value, () => scheduleInfusionRefresh()));
    subscriptions.push(
      subscribeIotTopic(deviceStatusTopicPath(wardId.value), parseDeviceStatusSignal, () =>
        scheduleInfusionRefresh(),
      ),
    );
  }
  pollTimer = setInterval(pollTick, REST_POLL_INTERVAL_MS);
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  document.removeEventListener('visibilitychange', onVisibilityChange);
  // 订阅句柄全量退订（B.3-3 卸载条款）+ 双端点断连（deactivate 取消库内建重连）
  for (const subscription of subscriptions) {
    subscription.unsubscribe();
  }
  subscriptions.length = 0;
  void nursingDisconnect();
  void iotDisconnect();
});

/* ---------- 头部链路状态与降级横幅 ---------- */

/** 状态徽标文案（三态中文；连接中不展示降级横幅——等待库内建重连结果） */
function stateLabel(state: string): string {
  switch (state) {
    case 'connected':
      return '已连接';
    case 'connecting':
      return '连接中';
    default:
      return '未连接';
  }
}

/** 呼吸点三态色（ok 绿/brand 青/gray 灰——运营大屏同款语义） */
function stateDotClass(state: string): string {
  if (state === 'connected') {
    return 'is-ok';
  }
  if (state === 'connecting') {
    return 'is-brand';
  }
  return 'is-gray';
}

const nursingStateLabel = computed(() => stateLabel(nursingConnectionState.value));
const nursingDotClass = computed(() => stateDotClass(nursingConnectionState.value));
const iotStateLabel = computed(() => stateLabel(iotConnectionState.value));
const iotDotClass = computed(() => stateDotClass(iotConnectionState.value));

/** 降级横幅文案清单（空清单=不渲染）：护理/设备双通道独立判定，均以 REST 10s 轮询承载 */
const degradedTexts = computed(() => {
  const texts: string[] = [];
  if (tokenFailed.value) {
    texts.push('护理实时链路令牌获取失败，REST 每 10 秒轮询承载');
  } else if (nursingConnectionState.value === 'disconnected') {
    texts.push('护理实时链路中断，REST 每 10 秒轮询降级中');
  }
  if (tokenMissing.value) {
    texts.push('未注入访问令牌，设备告警列以 REST 每 10 秒轮询承载');
  } else if (iotConnectionState.value === 'disconnected') {
    texts.push('设备实时链路中断，REST 每 10 秒轮询承载');
  }
  return texts;
});
</script>

<template>
  <section class="nurse-view">
    <!-- 头部：站点名 + 病区书签标识 + 护理/设备双链路状态呼吸点 -->
    <header class="nurse-header">
      <div class="nurse-header-title">
        <h1>护士站大屏</h1>
        <span class="nurse-ward fuy-num">病区 {{ wardId }}</span>
      </div>
      <div class="nurse-links">
        <div class="nurse-link">
          <span
            class="nurse-dot fuy-breath fuy-loading-essential"
            :class="nursingDotClass"
            aria-hidden="true"
          ></span>
          <span class="nurse-link-state">护理链路 {{ nursingStateLabel }}</span>
        </div>
        <div class="nurse-link">
          <span
            class="nurse-dot fuy-breath fuy-loading-essential"
            :class="iotDotClass"
            aria-hidden="true"
          ></span>
          <span class="nurse-link-state">设备链路 {{ iotStateLabel }}</span>
        </div>
      </div>
    </header>

    <!-- 降级横幅（护理/设备双通道独立判定；连接中等待重连不渲染） -->
    <div v-for="text in degradedTexts" :key="text" class="nurse-banner" role="status">
      {{ text }}
    </div>

    <!-- REST 失败横幅（下一轮轮询自愈） -->
    <div v-if="restFailed" class="nurse-banner" role="alert">数据加载失败，自动重试中</div>

    <!-- 主区三列：左床位墙 / 中告警列 / 右输液动态+逾期看板堆叠 -->
    <div class="nurse-main">
      <!-- ① 床位总览墙：护理级别色阶/责任护士/风险标记 -->
      <section class="nurse-panel nurse-beds-panel" aria-label="床位总览墙">
        <h2 class="nurse-panel-title">床位总览墙</h2>
        <p v-if="bedRows.length === 0" class="nurse-empty">暂无在册患者</p>
        <ul v-else class="nurse-beds">
          <li v-for="bed in bedRows" :key="bed.visitId" class="nurse-bed">
            <div class="nurse-bed-head">
              <span class="nurse-bed-no fuy-num">{{ bedLabel(bed.bedNo) }}</span>
              <span class="nurse-bed-level" :class="levelMeta(bed.nursingLevel).tone">{{
                levelMeta(bed.nursingLevel).text
              }}</span>
              <span class="nurse-bed-assignee fuy-num">{{
                bed.assigneeName === null ||
                bed.assigneeName === undefined ||
                bed.assigneeName === ''
                  ? '未指派'
                  : bed.assigneeName
              }}</span>
            </div>
            <div class="nurse-bed-foot">
              <span v-for="chip in riskChips(bed.riskFlags)" :key="chip" class="nurse-risk-chip">{{
                chip
              }}</span>
              <span class="nurse-bed-in fuy-num">入区 {{ formatDayClock(bed.admittedAt) }}</span>
            </div>
          </li>
        </ul>
      </section>

      <!-- ② 未确认告警列：iot 告警直订 + 呼叫转发行 -->
      <section class="nurse-panel" aria-label="未确认告警">
        <h2 class="nurse-panel-title">未确认告警</h2>
        <p v-if="alertRows.length === 0" class="nurse-empty">暂无未确认告警</p>
        <ol v-else class="nurse-alerts">
          <li v-for="row in alertRows" :key="row.key" class="nurse-alert">
            <span class="nurse-alert-kind" :class="row.kind === 'CALL' ? 'is-warn' : 'is-muted'">{{
              row.kind === 'CALL' ? '呼叫' : '设备'
            }}</span>
            <span class="nurse-alert-level" :class="row.levelTone">{{ row.levelText }}</span>
            <span class="nurse-alert-no fuy-num">{{ row.title }}</span>
            <span class="nurse-alert-desc fuy-num">{{ row.desc }}</span>
            <span class="nurse-alert-time fuy-num">{{ formatClock(row.time) }}</span>
          </li>
        </ol>
      </section>

      <!-- 右列堆叠：③ 输液动态条 + ④ 任务逾期看板 -->
      <div class="nurse-right">
        <section class="nurse-panel" aria-label="输液动态">
          <h2 class="nurse-panel-title">输液动态</h2>
          <p v-if="infusionDevices.length === 0" class="nurse-empty">暂无输液设备</p>
          <ul v-else class="nurse-infusions">
            <li v-for="device in infusionDevices" :key="device.deviceId" class="nurse-infusion">
              <span class="nurse-infusion-device fuy-num">{{ device.deviceId }}</span>
              <span class="nurse-infusion-metric fuy-num"
                >余量 {{ metricText(device.remainLatest) }}ml / 滴速
                {{ metricText(device.dropRateLatest) }}ml/h</span
              >
              <span class="nurse-infusion-remain fuy-num">约 {{ remainText(device) }}</span>
              <span
                class="nurse-infusion-level"
                :class="infusionLevelMeta(device.alertLevel).tone"
                >{{ infusionLevelMeta(device.alertLevel).text }}</span
              >
            </li>
          </ul>
          <!-- 输注升级强提醒行（执行单号幂等清单；无帧不渲染） -->
          <ul v-if="escalationRows.length > 0" class="nurse-escalations">
            <li v-for="row in escalationRows" :key="row.executionNo" class="nurse-escalation">
              <span class="nurse-escalation-alarm fuy-num">{{ row.alarmNo }}</span>
              <span class="nurse-escalation-exec fuy-num">{{ row.executionNo }}</span>
              <span class="nurse-escalation-tag">输注升级</span>
            </li>
          </ul>
        </section>

        <section class="nurse-panel" aria-label="任务逾期看板">
          <h2 class="nurse-panel-title">任务逾期看板</h2>
          <!-- 不良事件上报超时提醒（非惩罚只提醒；无帧不渲染） -->
          <p v-if="adverseRemind !== null" class="nurse-adverse" role="status">
            不良事件上报超时 {{ adverseRemind.overdueCount }} 例：{{
              adverseRemind.sampleEventNos.join('、')
            }}
          </p>
          <p v-if="overdueRows.length === 0" class="nurse-empty">暂无逾期任务</p>
          <ol v-else class="nurse-overdues">
            <li
              v-for="row in overdueRows"
              :key="row.taskNo"
              class="nurse-overdue"
              :class="{ 'is-escalated': row.escalationCount >= 2 }"
            >
              <span class="nurse-overdue-no fuy-num">{{ row.taskNo }}</span>
              <span class="nurse-overdue-type">{{ taskTypeText(row.taskType) }}</span>
              <span class="nurse-overdue-plan fuy-num">{{ formatDayClock(row.planTime) }}</span>
              <span
                class="nurse-overdue-escalation"
                :class="row.escalationCount >= 2 ? 'is-alert' : ''"
                >{{ escalationText(row.escalationCount) }}</span
              >
            </li>
          </ol>
        </section>
      </div>
    </div>

    <!-- ⑤ 出入院动态滚动条：board admissions 段近 24h 时间线（横向溢出滚动） -->
    <section class="nurse-panel nurse-admissions-panel" aria-label="出入院动态">
      <h2 class="nurse-panel-title">出入院动态（近 24 小时）</h2>
      <p v-if="admissionRows.length === 0" class="nurse-empty">近 24 小时无出入院动态</p>
      <ul v-else class="nurse-admissions">
        <li
          v-for="(row, index) in admissionRows"
          :key="`${row.visitId}-${row.type}-${row.at ?? index}`"
          class="nurse-admission"
        >
          <span class="nurse-admission-type" :class="admissionMeta(row.type).tone">{{
            admissionMeta(row.type).text
          }}</span>
          <span class="nurse-admission-bed fuy-num">{{
            row.bedNo === null || row.bedNo === undefined || row.bedNo === ''
              ? '待排床'
              : `${row.bedNo} 床`
          }}</span>
          <span class="nurse-admission-time fuy-num">{{ formatClock(row.at) }}</span>
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
/* 布局壳（暗色五区，对齐 DashboardView 设计语言）：flex 纵向（头部/横幅/主区/出入院条），
   主区三列弹性占满、100dvh 零滚动（分区内滚动）；字号全 rem；底色/文本/描边全部消费
   tokens.css 暗色语义变量，零自创色值 */
.nurse-view {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-height: 100dvh;
  padding: 1rem;
  background: var(--fuy-screen-bg-base);
  color: var(--fuy-screen-text-primary);
}

/* 头部：站点名 2.5rem + 病区标识 1.5rem；双链路呼吸点 8px 常驻（豁免面 .fuy-loading-essential） */
.nurse-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.nurse-header-title {
  display: flex;
  align-items: baseline;
  gap: 1rem;
}
.nurse-header h1 {
  margin: 0;
  font-size: 2.5rem;
  font-weight: 700;
}
.nurse-ward {
  font-size: 1.5rem;
  color: var(--fuy-screen-text-secondary);
}
.nurse-links {
  display: flex;
  gap: 1.5rem;
}
.nurse-link {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.25rem;
}
.nurse-dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: var(--fuy-radius-full);
  background: currentColor;
}
.nurse-dot.is-ok {
  color: var(--fuy-screen-ok);
}
.nurse-dot.is-brand {
  color: var(--fuy-screen-brand);
}
.nurse-dot.is-gray {
  color: var(--fuy-screen-text-secondary);
}
.nurse-link-state {
  color: var(--fuy-screen-text-secondary);
}

/* 横幅通栏：降级/失败态（面板底 + 描边） */
.nurse-banner {
  padding: 0.5rem 1rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
  color: var(--fuy-screen-text-secondary);
  font-size: 1.25rem;
  text-align: center;
}

/* 主区三列：左床位墙 1.2fr / 中告警列 1fr / 右输液+逾期堆叠 1.3fr；min-height 0 承载
   分区内滚动 */
.nurse-main {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1.3fr;
  gap: 0.75rem;
  flex: 1;
  min-height: 0;
}
.nurse-right {
  display: grid;
  grid-template-rows: auto 1fr;
  gap: 0.75rem;
  min-height: 0;
}

/* 面板通用形态（暗色面板语言）：panel 底 + hairline 描边 + radius-lg */
.nurse-panel {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  min-height: 0;
  padding: 0.75rem 1rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
  overflow: hidden;
}
.nurse-panel-title {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--fuy-screen-text-secondary);
}
.nurse-empty {
  margin: auto 0;
  font-size: 1.25rem;
  color: var(--fuy-screen-text-secondary);
  text-align: center;
}

/* ① 床位总览墙：床位卡单列滚动；护理级别语义文本色（特级红/危重橙）+ 风险 chips */
.nurse-beds {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.nurse-bed {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
}
.nurse-bed-head {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 1.25rem;
}
.nurse-bed-no {
  font-weight: 700;
}
.nurse-bed-level.is-alert {
  color: var(--fuy-screen-triage-l1);
}
.nurse-bed-level.is-warn {
  color: var(--fuy-screen-triage-l2);
}
.nurse-bed-assignee {
  margin-left: auto;
  color: var(--fuy-screen-text-secondary);
}
.nurse-bed-foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
  font-size: 1rem;
  color: var(--fuy-screen-text-secondary);
}
.nurse-risk-chip {
  padding: 0.125rem 0.5rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-sm);
  color: var(--fuy-screen-triage-l2);
}
.nurse-bed-in {
  margin-left: auto;
}

/* ② 未确认告警列：行卡滚动；级别词语义文本色（危急红/警告橙/提示蓝），呼叫行橙 */
.nurse-alerts {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.nurse-alert {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
  font-size: 1.25rem;
}
.nurse-alert-kind.is-warn {
  color: var(--fuy-screen-triage-l2);
}
.nurse-alert-kind.is-muted {
  color: var(--fuy-screen-text-secondary);
}
.nurse-alert-level.is-alert {
  color: var(--fuy-screen-triage-l1);
}
.nurse-alert-level.is-warn {
  color: var(--fuy-screen-triage-l2);
}
.nurse-alert-level.is-info {
  color: var(--fuy-screen-triage-l4);
}
.nurse-alert-level.is-muted {
  color: var(--fuy-screen-text-secondary);
}
.nurse-alert-no {
  font-weight: 700;
}
.nurse-alert-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fuy-screen-text-secondary);
}
.nurse-alert-time {
  color: var(--fuy-screen-text-secondary);
}

/* ③ 输液动态条：设备行滚动 + 升级行置底；档位词语义文本色（红/橙/黄） */
.nurse-infusions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.nurse-infusion {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
  font-size: 1.25rem;
}
.nurse-infusion-device {
  font-weight: 700;
}
.nurse-infusion-metric {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fuy-screen-text-secondary);
  font-size: 1rem;
}
.nurse-infusion-remain {
  font-weight: 600;
}
.nurse-infusion-level.is-alert {
  color: var(--fuy-screen-triage-l1);
}
.nurse-infusion-level.is-warn {
  color: var(--fuy-screen-triage-l2);
}
.nurse-infusion-level.is-muted {
  color: var(--fuy-screen-text-secondary);
}
.nurse-escalations {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.nurse-escalation {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  border: var(--fuy-screen-border-hairline);
  font-size: 1rem;
  color: var(--fuy-screen-text-secondary);
}
.nurse-escalation-alarm {
  font-weight: 700;
}
.nurse-escalation-exec {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nurse-escalation-tag {
  color: var(--fuy-screen-triage-l2);
}

/* ④ 任务逾期看板：行卡滚动；护士长档（escalationCount≥2）闪烁强调（紧迫状态指示——
   motion 铁律常驻动画豁免同族语义，动画属性仅 opacity；prefers-reduced-motion 全局兜底） */
.nurse-overdues {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.nurse-overdue {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
  font-size: 1.25rem;
}
.nurse-overdue.is-escalated {
  animation: nurse-overdue-blink 1.2s linear infinite alternate;
}
@keyframes nurse-overdue-blink {
  from {
    opacity: 1;
  }
  to {
    opacity: 0.45;
  }
}
.nurse-overdue-no {
  font-weight: 700;
}
.nurse-overdue-type {
  color: var(--fuy-screen-text-secondary);
}
.nurse-overdue-plan {
  margin-left: auto;
  color: var(--fuy-screen-text-secondary);
}
.nurse-overdue-escalation.is-alert {
  color: var(--fuy-screen-triage-l1);
}
.nurse-adverse {
  margin: 0;
  padding: 0.25rem 0.75rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-md);
  font-size: 1rem;
  color: var(--fuy-screen-triage-l2);
}

/* ⑤ 出入院动态滚动条：横向单行溢出滚动（近 24h 有界时间线，无驻留滚动动画） */
.nurse-admissions-panel {
  flex-shrink: 0;
}
.nurse-admissions {
  display: flex;
  gap: 0.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-x: auto;
}
.nurse-admission {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
  font-size: 1.25rem;
  white-space: nowrap;
}
.nurse-admission-type.is-ok {
  color: var(--fuy-screen-ok);
}
.nurse-admission-type.is-muted {
  color: var(--fuy-screen-text-secondary);
}
.nurse-admission-time {
  color: var(--fuy-screen-text-secondary);
}
</style>
