<script setup lang="ts">
// 病区输液看板（/ward/infusion-board，M16 WS+REST 混合面前端面 · 暖纸卷宗丁-1 换脸重排）：
// 「换脸不换业务」——STOMP 订阅/帧节流/visibilityState 暂停/轮询降级/banner 联动链零变动，
// 仅重排纸墨脸：门牌页首（衬线标题 + WS 连接胶囊 + 病区下拉 + 刷新 + 批注行）→ 告急联动
// 提示条（印泥朱描边 + 状态朱 alpha 派生洗底）→ 三档纸语法床卡墙（grid 轨道卡墙 + 纸面卡 +
// 左缘 4px 档位规线 + 余量大数字档位色 + 档位徽标，G3 三通道）→ 余量趋势抽屉 520px（点床卡
// 出网拉 24h 双序列曲线，getInfusionHistory 只读取用，把「还剩多久输完」从心算变可读曲线）
// → 页脚批注（快照时点）。数据链不变：REST 全量快照（病区输液设备床卡列表：余量/滴速最新值
// 与告警档位，档位判定归后端 mapAlertLevel：YELLOW ≤15ml/ORANGE ≤10ml/RED ≤5ml）+ WS 增量
// （useIotStomp 订阅遥测摘要与病区告警双主题——摘要帧作为"有新遥测"信号触发热刷新拉取最新
// 快照，告急帧渲染呼叫联动提示条）+ WS 不可用时 REST 轮询降级（30s 周期，连接落地自动停止）
// + 页面隐藏暂停轮询/订阅消费（EX-41：恢复可见立即刷一轮）。病区下拉=listOrgs({type:'WARD'})
// 真数据三态（册级总则 G4：病区假常量本页零消费，出网值锚定真实病区编码）。三档着色一律
// --fuy-* 语义 token（RED 档=状态朱通道非装饰朱），声音提示本批次未接入（静默提示面）。
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import type { StompSubscription } from '@stomp/stompjs';
import { INFUSION_ALERT_LABELS, getInfusionHistory, infusionBoard } from '@/api/ward';
import type {
  InfusionBoardDeviceVO,
  InfusionBoardVO,
  InfusionHistoryVO,
  TelemetryPoint,
} from '@/api/ward';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAuthStore } from '@/stores/auth';
import { useAsyncTask } from '@/composables/useAsyncTask';
import {
  alarmTopicPath,
  connect as stompConnect,
  connectionState,
  disconnect as stompDisconnect,
  subscribeTopic,
  telemetryTopicPath,
} from '@/composables/useIotStomp';
import { parseAlarmPayload, parseTelemetrySummary } from '@/utils/iotMessage';
import type { AlarmTriggeredPayload, TelemetrySummary } from '@/types/iot';
import { formatTime } from '@/utils/timeFormat';
import { warn } from '@/utils/logger';

/** WS 不可用时的 REST 轮询降级周期（毫秒） */
const POLL_INTERVAL_MS = 30000;

/** 遥测帧驱动的热刷新最小间隔（毫秒）：摘要帧 2s 窗口合并，短窗内多帧只触发一次刷新 */
const FRAME_REFRESH_MIN_INTERVAL_MS = 2000;

/** 卡墙分组阈值（⑨.4/G5：卡墙 >120 卡必须分组，低于阈值单组直出零分组开销） */
const WALL_GROUP_THRESHOLD = 120;

/** 告警级别中文词表（联动提示条展示；与后端 AlarmLevel 三值口径一致） */
const ALARM_LEVEL_TEXT: Record<string, string> = {
  INFO: '提示',
  WARNING: '警告',
  CRITICAL: '危急',
};

/* ---------- 门牌批注行：签认人（会话身份）· 时刻（本地时钟零出网，BindingManage 先例同构） ---------- */
const auth = useAuthStore();

/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 当前病区（纯数字字符串，与 WS 主题病区段同源；来源：listOrgs 病区清单选择；空串=未就绪） */
const wardId = ref('');

/** 病区选项（启用机构清单，sort 升序；空清单/失败态下拉禁用——出网值锚定真实病区编码） */
const wardOptions = ref<OrgVO[]>([]);

/** 看板快照（REST 全量；null=尚未加载） */
const board = ref<InfusionBoardVO | null>(null);

/** 最近一次快照成功取数的本地钟面（页脚批注「快照时点」；客户端记录，零伪数据） */
const boardLoadedAt = ref('—');

/** 最近一条输液告急联动帧（null=无；banner 展示锚点） */
const lastShortageAlarm = ref<AlarmTriggeredPayload | null>(null);

/** 床卡列表（空安全：无快照/无设备均渲染空态） */
const devices = computed(() => board.value?.devices ?? []);

/** 档位中文词表反查（床卡档位标签） */
function alertLabel(code: string | undefined): string {
  return INFUSION_ALERT_LABELS[code ?? ''] ?? code ?? '—';
}

/** 床卡档位契约类（fuy-infusion-card--{level}：none/yellow/orange/red，档位判定归后端） */
function cardClass(code: string | undefined): string {
  return `fuy-infusion-card--${(code ?? 'none').toLowerCase()}`;
}

/** 余量/滴速展示（无遥测数据占位不炸渲染；null=后端无遥测真实回包态，与缺字段 undefined 同归「—」） */
function metricText(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : String(value);
}

/** 本地钟面（HH:mm:ss）：页脚快照时点批注 */
function clockText(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/* ==================== REST 全量 ==================== */

/**
 * 拉取病区输液看板快照（REST 全量兜底面）：失败弹错归响应拦截器，驻留旧快照；入参守卫承载
 * 「病区未就绪零出网」——快照路径必须锚定真实病区编码，轮询/可见恢复/帧驱动各触发点共用此
 * 单点守卫，禁向 /v1/ward/infusion-board/ 空病区段出网。WS 帧驱动的节流守卫在外层
 * handleTelemetryFrame/handleAlarmFrame 承载，不经任务体。
 */
const { loading, run: loadBoard } = useAsyncTask(async () => {
  if (wardId.value === '') {
    return;
  }
  board.value = await infusionBoard.byWard(wardId.value);
  boardLoadedAt.value = clockText(new Date());
});

/* ==================== 病区下拉（G4 真数据三态） ==================== */

/**
 * 拉取病区清单（listOrgs WARD 档，装载与重试共用汇合点）：清单就绪即落默认首项病区并接线
 * （订阅双主题 + 拉快照）；已选值不覆盖。失败态由恢复条承载（说问题给恢复），订阅与快照
 * 在失败/空清单下零出网。
 */
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
  if (wardId.value === '' && wardOptions.value.length > 0) {
    wardId.value = wardOptions.value[0]?.orgCode ?? '';
    subscribeWard();
    await loadBoard();
  }
});
const wardLoading = wardLoad.loading;
const wardError = wardLoad.error;

/** 病区下拉禁用态：清单在途或空清单（失败同形）皆不可选 */
const wardDisabled = computed(() => wardLoading.value || wardOptions.value.length === 0);

/** 病区清单重试（失败恢复同路：清单到达即落默认病区并接线） */
function onWardRetry(): void {
  void wardLoad.run();
}

/* ==================== 卡墙分组（⑨.4/G5：>120 卡按设备尾号十位段分组） ==================== */

/** 卡墙分组（组头签行 + 组内床卡；组键/标签形态与 wardBoardShared.groupWallPatients 同构） */
interface InfusionWallGroup {
  key: string;
  label: string;
  devices: InfusionBoardDeviceVO[];
}

/** 设备号尾部数字段（无尾随数字归「其他尾号」组，不伪称床位） */
function deviceTailNo(deviceId: string): number | null {
  const match = /(\d+)$/.exec(deviceId);
  return match === null ? null : Number(match[1]);
}

/**
 * 卡墙分组：常规量（≤120）单组直出零分组开销；超阈值按设备尾号十位段分组（设备 VO 无床号
 * 字段，分组键诚实取设备号尾号段，组头注明「尾号」段界），组序按段起始升序。
 */
function groupWallDevices(all: InfusionBoardDeviceVO[]): InfusionWallGroup[] {
  if (all.length <= WALL_GROUP_THRESHOLD) {
    return all.length === 0 ? [] : [{ key: 'all', label: '', devices: all }];
  }
  const buckets = new Map<number, InfusionBoardDeviceVO[]>();
  const others: InfusionBoardDeviceVO[] = [];
  for (const device of all) {
    const tail = deviceTailNo(device.deviceId ?? '');
    if (tail === null) {
      others.push(device);
      continue;
    }
    const start = Math.floor(tail / 10) * 10;
    let bucket = buckets.get(start);
    if (bucket === undefined) {
      bucket = [];
      buckets.set(start, bucket);
    }
    bucket.push(device);
  }
  const groups = [...buckets.keys()]
    .sort((a, b) => a - b)
    .map((start) => ({
      key: `seg-${start}`,
      // 组头「尾号 0000-0009」段界形态（四位补零，与设备号尾号段展示对齐）
      label: `尾号 ${String(start).padStart(4, '0')}-${String(start + 9).padStart(4, '0')}`,
      devices: buckets.get(start) ?? [],
    }));
  if (others.length > 0) {
    groups.push({ key: 'other', label: '其他尾号', devices: others });
  }
  return groups;
}

/** 卡墙分组视图（组头签行 grid-column 通栏，组内床卡随组续排） */
const wallGroups = computed<InfusionWallGroup[]>(() => groupWallDevices(devices.value));

/* ==================== WS 增量（遥测摘要 + 病区告警双主题，业务链冻结） ==================== */

/** 遥测/告警两主题在册订阅句柄（退旧订新与卸载退订的执行凭据） */
let telemetrySub: StompSubscription | null = null;
let alarmSub: StompSubscription | null = null;

/** 最近一次帧驱动刷新时刻（毫秒时间戳；节流防短窗多帧重复拉取） */
let lastFrameRefreshAt = 0;

/**
 * 遥测摘要帧处理：摘要帧不含数值载荷，仅作为"该病区有新遥测"信号——节流后热刷新拉取最新
 * 快照（WS 感知 + REST 取数的增量形态）；残缺载荷毒帧 warn 留痕零刷新。页面隐藏期间暂停
 * 订阅消费（EX-41）：帧信号丢弃零出网，恢复可见由立刷兜底，隐藏期变更一次补齐。
 */
function handleTelemetryFrame(payload: unknown): void {
  const summary: TelemetrySummary | null = parseTelemetrySummary(payload);
  if (summary === null) {
    warn('遥测摘要帧载荷不合法，已忽略本帧', telemetryTopicPath(wardId.value));
    return;
  }
  if (document.hidden) {
    // 隐藏态丢帧：不出网（后台标签页零请求），节流时钟也不推进
    return;
  }
  const now = Date.now();
  if (now - lastFrameRefreshAt < FRAME_REFRESH_MIN_INTERVAL_MS) {
    // 节流窗内：跳过本次刷新（下一帧自然兜底）
    return;
  }
  lastFrameRefreshAt = now;
  void loadBoard();
}

/**
 * 告警帧处理：输液告急帧（metricCode=INFUSION_SHORTAGE）渲染呼叫联动提示条；残缺载荷
 * 毒帧 warn 留痕不更新提示。
 */
function handleAlarmFrame(payload: unknown): void {
  const alarm: AlarmTriggeredPayload | null = parseAlarmPayload(payload);
  if (alarm === null) {
    warn('告警帧载荷不合法，已忽略本帧', alarmTopicPath(wardId.value));
    return;
  }
  if (alarm.metricCode === 'INFUSION_SHORTAGE') {
    // 输液告急与病区呼叫联动（ward 侧消费者同源判定口径）：提示条 + 快照热刷新
    lastShortageAlarm.value = alarm;
    // 页面隐藏暂停热刷新出网（EX-41）：提示条状态仍落（恢复可见即见，告警不因隐藏丢失），
    // 隐藏期快照变更由恢复立刷兜底
    if (!document.hidden) {
      void loadBoard();
    }
  }
}

/** 订阅当前病区双主题（连接落地前由 useIotStomp 登记待订阅，onConnect 自动转正） */
function subscribeWard(): void {
  telemetrySub = subscribeTopic(telemetryTopicPath(wardId.value), handleTelemetryFrame);
  alarmSub = subscribeTopic(alarmTopicPath(wardId.value), handleAlarmFrame);
}

/** 退订在册双主题（幂等：句柄未落地时仅清凭据） */
function unsubscribeWard(): void {
  telemetrySub?.unsubscribe();
  telemetrySub = null;
  alarmSub?.unsubscribe();
  alarmSub = null;
}

/* ==================== REST 轮询降级（业务链冻结） ==================== */

/** 轮询定时器句柄（null=未在轮询） */
let pollTimer: ReturnType<typeof setInterval> | null = null;

/** 启动 REST 轮询降级（幂等：已在轮询时跳过） */
function startPolling(): void {
  if (pollTimer !== null) {
    return;
  }
  pollTimer = setInterval(() => {
    // 页面隐藏暂停轮询出网（EX-41）：定时器保持空转，恢复可见续拍（同分诊台看板范式）
    if (!document.hidden) {
      void loadBoard();
    }
  }, POLL_INTERVAL_MS);
}

/** 停止 REST 轮询（WS 已接管；幂等） */
function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

// 连接状态联动：已连接 → WS 增量接管停轮询；未连接/连接中 → 轮询降级兜底（连接中仍轮询
// 是防"连不上又不轮询"的数据真空，代价仅低频请求）
watch(
  connectionState,
  (state) => {
    if (state === 'connected') {
      stopPolling();
    } else {
      startPolling();
    }
  },
  { immediate: true },
);

/** 连接状态展示串（门牌 WS 胶囊文案） */
const connectionText = computed(() => {
  if (connectionState.value === 'connected') {
    return 'WS 已连接';
  }
  return connectionState.value === 'connecting' ? 'WS 连接中' : 'WS 未连接（REST 轮询降级）';
});

/**
 * WS 胶囊两态类（蓝图丁-1.4）：on=绿+呼吸点（实时语义）、off=琥珀（降级轮询）；呼吸点仅
 * 实时语义挂载，降级态零呼吸点（色+文字双通道承载，呼吸点不承义）。
 */
const wsPillClass = computed(() =>
  connectionState.value === 'connected' ? 'fuy-status-pill--success' : 'fuy-status-pill--amber',
);

/* ==================== 余量趋势抽屉（丁-1 呈现增强：只增呈现不加业务动作） ==================== */

/** 趋势抽屉可见态（床卡点击开抽屉，EP 内建右推过渡=翻纸语义不压制） */
const trendVisible = ref(false);

/** 趋势抽屉目标设备（标题/重试上下文锚点） */
const trendDevice = ref<InfusionBoardDeviceVO | null>(null);

/** 趋势抽屉历史数据（null=尚未到达；开抽屉/换设备即清，禁串卡驻留旧曲线） */
const trendHistory = ref<InfusionHistoryVO | null>(null);

/** 拉取设备 24h 输液历史（getInfusionHistory 只读取用；失败弹错归拦截器+错误卡恢复） */
const trendLoad = useAsyncTask(async (deviceId: string) => {
  trendHistory.value = await getInfusionHistory(deviceId);
});
const trendLoading = trendLoad.loading;
const trendError = trendLoad.error;

/** 打开趋势抽屉（点床卡出网；设备号缺位的脏卡跳过出网——快照路径禁止空设备段） */
function openTrend(device: InfusionBoardDeviceVO): void {
  trendDevice.value = device;
  trendHistory.value = null;
  trendVisible.value = true;
  const deviceId = device.deviceId ?? '';
  if (deviceId !== '') {
    void trendLoad.run(deviceId);
  }
}

/** 趋势历史重试（失败恢复同路：错误卡「重新查询」） */
function retryTrend(): void {
  const deviceId = trendDevice.value?.deviceId ?? '';
  if (deviceId !== '') {
    void trendLoad.run(deviceId);
  }
}

/* ---------- 曲线几何（静态渲染零动画；viewBox 520×180，绘图区 y ∈ [10,160]） ---------- */

/** 图表几何常量（与模板 viewBox/网格线坐标一致，改一处须同步） */
const CHART_WIDTH = 520;
const CHART_PADDING_X = 8;
const CHART_PLOT_TOP = 10;
const CHART_PLOT_BOTTOM = 160;

/** 曲线绘图点（viewBox 坐标） */
interface TrendPoint {
  x: number;
  y: number;
}

/**
 * 遥测聚合点列 → 曲线绘图点：余量取桶末值 last（该时段末真实余量，曲线斜率即消耗速率）、
 * 滴速取桶均值 avg（时段平均滴速）；缺时点/缺值的聚合点剔除不绘。X 轴按时间线性映射，
 * Y 轴按各序列自身值域归一（双序列量纲不同，各自映射只示趋势形态，数值由图例最新值锚定）。
 */
function toChartPoints(series: TelemetryPoint[] | undefined, prefer: 'last' | 'avg'): TrendPoint[] {
  const points = (series ?? [])
    .map((point) => ({ time: point.time ?? '', value: prefer === 'last' ? point.last : point.avg }))
    .filter((point): point is { time: string; value: number } => {
      return point.time !== '' && point.value !== undefined;
    });
  if (points.length === 0) {
    return [];
  }
  const times = points.map((point) => new Date(point.time).getTime());
  const values = points.map((point) => point.value);
  const tMin = Math.min(...times);
  const tMax = Math.max(...times);
  const vMin = Math.min(...values);
  const vMax = Math.max(...values);
  const tSpan = tMax - tMin || 1;
  const vSpan = vMax - vMin || 1;
  return points.map((point, index) => ({
    x: CHART_PADDING_X + ((times[index] - tMin) / tSpan) * (CHART_WIDTH - CHART_PADDING_X * 2),
    y: CHART_PLOT_TOP + (1 - (point.value - vMin) / vSpan) * (CHART_PLOT_BOTTOM - CHART_PLOT_TOP),
  }));
}

/** 坐标保留一位小数（polyline points 属性串紧凑化） */
function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

/** polyline points 属性串（单点退化为 3px 短横刻记——polyline 单点零渲染，刻记保「有数据」语义） */
function polylineAttributeValue(points: TrendPoint[]): string {
  if (points.length === 0) {
    return '';
  }
  if (points.length === 1) {
    const only = points[0];
    return `${round1(only.x - 3)},${round1(only.y)} ${round1(only.x + 3)},${round1(only.y)}`;
  }
  return points.map((point) => `${round1(point.x)},${round1(point.y)}`).join(' ');
}

/** 余量曲线绘图点（桶末值口径） */
const remainPoints = computed<TrendPoint[]>(() =>
  toChartPoints(trendHistory.value?.remainSeries, 'last'),
);
/** 滴速曲线绘图点（桶均值口径） */
const dropPoints = computed<TrendPoint[]>(() =>
  toChartPoints(trendHistory.value?.dropSeries, 'avg'),
);

const remainPolyline = computed(() => polylineAttributeValue(remainPoints.value));
const dropPolyline = computed(() => polylineAttributeValue(dropPoints.value));

/** 双序列皆空=空序列退化态（空态脸承载，禁裸 polyline 空图） */
const trendEmpty = computed(() => remainPoints.value.length === 0 && dropPoints.value.length === 0);

/** 时间轴刻度（起点/中点/终点三档，取点列更多的序列；MM-dd HH:mm 与表格时点同构） */
const trendTicks = computed<string[]>(() => {
  const remain = trendHistory.value?.remainSeries ?? [];
  const drop = trendHistory.value?.dropSeries ?? [];
  const series = remain.length >= drop.length ? remain : drop;
  const times = series.map((point) => point.time ?? '').filter((time) => time !== '');
  if (times.length === 0) {
    return [];
  }
  if (times.length === 1) {
    return [formatTime(times[0])];
  }
  const mid = times[Math.floor((times.length - 1) / 2)];
  return [formatTime(times[0]), formatTime(mid), formatTime(times[times.length - 1])];
});

/** 图例最新余量（末聚合点桶末值；空序列 — 占位） */
const lastRemainText = computed(() => {
  const last = trendHistory.value?.remainSeries?.at(-1);
  return metricText(last?.last ?? last?.avg);
});

/** 图例最新滴速（末聚合点桶均值；空序列 — 占位） */
const lastDropText = computed(() => {
  const last = trendHistory.value?.dropSeries?.at(-1);
  return metricText(last?.avg ?? last?.last);
});

/* ==================== 病区切换与生命周期 ==================== */

/**
 * 病区切换：退旧订新（双主题路径随病区切换，防旧病区帧流入）→ 重查看板 → 建连补偿
 * （未连接态下切病区同时补一次建连尝试；已连接态 connect 为 no-op）。
 */
async function onWardChange(): Promise<void> {
  unsubscribeWard();
  lastShortageAlarm.value = null;
  subscribeWard();
  await loadBoard();
  stompConnect();
}

/**
 * 页面隐藏暂停自动刷新、可见恢复立即刷一轮（web 宪法 B.3-4/EX-41：visibilityState 隐藏时
 * 暂停轮询/订阅消费，释放后端扇出压力；病区未就绪（清单在途/失败/空）零出网——快照路径
 * 必须锚定真实病区编码）。
 */
function onVisibilityChange(): void {
  if (document.hidden || wardId.value === '') {
    return;
  }
  void loadBoard();
}

onMounted(() => {
  // 装配即建连（不依赖病区清单）+ 拉病区清单（到达后落默认病区并接线订阅与快照）
  void wardLoad.run();
  stompConnect();
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onUnmounted(() => {
  // web B.3-3 卸载清理条款：先退订双主题再断连，轮询定时器与可见性监听同步清掉
  unsubscribeWard();
  stopPolling();
  void stompDisconnect();
  document.removeEventListener('visibilitychange', onVisibilityChange);
});
</script>

<template>
  <!-- 契约 ⑦.4：页面根节点禁挂 .fuy-stagger，级联由页内 flow 包裹层承接（flex+gap 同构） -->
  <div class="fuy-page infusion-board">
    <div class="fuy-stagger infusion-flow">
      <!-- 门牌页首（蓝图丁-1.2/契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（WS 胶囊+
           病区下拉 + 刷新）+ 批注行（刷新机制批注 + 「谁·时刻」）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">病区输液看板</h1>
          <span class="fuy-page-en" aria-hidden="true">INFUSION BOARD</span>
        </div>
        <div class="fuy-page-status">
          <!-- WS 连接胶囊两态（蓝图丁-1.4）：on=绿+呼吸点（实时语义）、off=琥珀（降级轮询） -->
          <span data-test="ws-pill" class="fuy-status-pill" :class="wsPillClass">
            <i v-if="connectionState === 'connected'" class="fuy-live-dot" aria-hidden="true"></i
            >{{ connectionText }}
          </span>
          <!-- 病区下拉换真数据（G4：清单在途/空清单禁用——出网值锚定真实病区编码） -->
          <select
            v-model="wardId"
            class="infusion-ward-select"
            aria-label="病区"
            :disabled="wardDisabled"
            @change="onWardChange"
          >
            <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode">
              {{ org.orgName }}（{{ org.orgCode }}）
            </option>
          </select>
          <el-button :loading="loading" @click="loadBoard">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          遥测摘要帧节流热刷新 · REST 30s 轮询降级 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- 病区清单失败恢复条（G4/G6 说问题给恢复：不伪装空态，重试同路落默认病区） -->
      <div
        v-if="wardError !== null"
        class="infusion-ward-error"
        :style="{ '--fuy-stagger-index': 1 }"
        role="alert"
      >
        <span>病区清单加载失败，暂无法选择病区与盯输</span>
        <el-button
          data-test="ward-retry"
          link
          type="primary"
          size="small"
          :loading="wardLoading"
          @click="onWardRetry"
          >重试</el-button
        >
      </div>

      <!-- 输液呼叫联动提示条（输液告急帧触发；声音提示本批次未接入，静默提示面；
           印泥朱描边 + 状态朱 alpha 派生洗底，替换旧中性灰底） -->
      <div
        v-if="lastShortageAlarm !== null"
        class="infusion-linkage-banner"
        :style="{ '--fuy-stagger-index': 1 }"
        data-test="call-linkage-banner"
        role="alert"
      >
        <span class="infusion-linkage-text fuy-num">
          输液告急联动：{{ lastShortageAlarm.alarmNo }} ·
          {{ ALARM_LEVEL_TEXT[lastShortageAlarm.alarmLevel] ?? lastShortageAlarm.alarmLevel }} ·
          设备 {{ lastShortageAlarm.deviceId }} · 触发值 {{ lastShortageAlarm.triggerValue }}ml
          （已联动病区呼叫，请及时处置）
        </span>
        <el-button link size="small" @click="lastShortageAlarm = null">知道了</el-button>
      </div>

      <!-- 三档纸语法床卡墙（G3 裁决：grid 轨道卡墙 + 纸面卡 + 左缘档位规线 + 余量大数字档位色
           + 档位徽标三通道；>120 卡尾号段分组，组头签行通栏）。显式 v-if 而非 v-else：
           告急 banner 与卡墙必须并存（告急帧恰是针对墙内设备的热刷新信号，banner 在展时
           隐藏墙体会把「哪台告急」的上下文一并藏掉）；墙区仅在病区清单失败时让位恢复条 -->
      <div
        v-if="wardError === null"
        v-loading="loading"
        class="infusion-wall"
        :style="{ '--fuy-stagger-index': 2 }"
      >
        <template v-for="group in wallGroups" :key="group.key">
          <!-- 组头签行（石规虚缝；>120 卡才出现，常规量零分组头） -->
          <p v-if="group.label !== ''" class="infusion-group-head">{{ group.label }}</p>
          <!-- 床卡=纸面按钮（键盘可达）；纵向序：设备号 → 余量（3xl 关键计数档）→ 滴速 →
               档位徽标；点击开余量趋势抽屉（呈现增强，不增业务动作） -->
          <button
            v-for="device in group.devices"
            :key="device.deviceId"
            type="button"
            class="infusion-card"
            :class="cardClass(device.alertLevel)"
            :data-test="`infusion-card-${device.deviceId}`"
            :aria-label="`${device.deviceId} 余量趋势`"
            @click="openTrend(device)"
          >
            <span class="infusion-card-device fuy-num">{{ device.deviceId }}</span>
            <span class="infusion-card-remain fuy-num">
              <strong>{{ metricText(device.remainLatest) }}</strong> ml
            </span>
            <span class="infusion-card-drop fuy-num">
              滴速 {{ metricText(device.dropRateLatest) }} ml/h
            </span>
            <!-- 档位徽标（G3 三通道之三）：只承文字色（色由卡档类经后代选择器下发），
                 不复挂卡档契约类——卡档类同时承载左缘 4px 规线，复挂会让小徽标多出一条
                 突兀左规线（规线是卡级通道，非徽标级通道） -->
            <el-tag size="small" class="fuy-tag-aa">{{ alertLabel(device.alertLevel) }}</el-tag>
          </button>
        </template>
        <!-- 空态脸（G6）：病区未配置与无遥测设备两态分述，主句=暂无〈业务客体〉+ 说明给下一步 -->
        <div v-if="devices.length === 0" class="fuy-empty" role="status">
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">
            {{ wardOptions.length === 0 ? '暂无可用病区' : '暂无输液设备遥测' }}
          </p>
          <p class="fuy-empty-hint">
            {{
              wardOptions.length === 0
                ? '系统未配置启用病区，请联系管理员配置后重试。'
                : '设备接入并上报后床卡在此落位；可切换病区或点击刷新。'
            }}
          </p>
        </div>
      </div>

      <!-- 页脚批注（契约 ⑧.8）：快照时点（客户端记录的取数钟面，零伪数据）+ 数据口径注记 -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 3 }">
        快照时点 {{ boardLoadedAt }} · 余量/滴速为设备最新遥测值，档位判定归后端承载
      </footer>
    </div>

    <!-- 余量趋势抽屉（蓝图丁-1.2④：520px 统一档挂 .fuy-drawer 衬线标题；双 polyline+
         体温单网格语法发丝+时间轴刻度；曲线静态渲染零动画） -->
    <el-drawer
      :model-value="trendVisible"
      class="fuy-drawer"
      size="520px"
      :title="`余量趋势 — ${trendDevice?.deviceId ?? ''}`"
      @update:model-value="trendVisible = $event"
    >
      <p class="infusion-trend-note">
        查看人 {{ signerName }} · <time>{{ todayLabel }}</time> · 近 24 小时聚合点
      </p>
      <div v-loading="trendLoading">
        <!-- 查询失败=说问题给恢复错误小卡（G6：不伪装空态；重试=墨实底主操作） -->
        <div
          v-if="trendError !== null && trendHistory === null"
          class="infusion-trend-error"
          role="alert"
        >
          <p class="infusion-trend-error-title">输液历史查询失败</p>
          <p class="infusion-trend-error-hint">
            网络异常或设备暂不可达，24 小时曲线暂缺；可重试，问题持续请联系系统管理员。
          </p>
          <el-button type="primary" size="small" :loading="trendLoading" @click="retryTrend"
            >重新查询</el-button
          >
        </div>
        <!-- 加载骨架（fuy-sk 纸感静态块，min-height 锁 CLS） -->
        <template v-else-if="trendLoading">
          <span class="fuy-sk fuy-sk-trend"></span>
          <span class="fuy-sk fuy-sk-text"></span>
        </template>
        <!-- 空序列退化态（双序列皆空：空态脸，禁裸 polyline 空图） -->
        <div v-else-if="trendEmpty" class="fuy-empty" role="status">
          <span class="fuy-empty-mark" aria-hidden="true">空</span>
          <p class="fuy-empty-title">暂无遥测历史</p>
          <p class="fuy-empty-hint">
            该设备近 24 小时无遥测归档点；设备恢复上报后曲线在此落位，可稍后重查。
          </p>
        </div>
        <!-- 双序列曲线：余量（实线·浓墨）/滴速（虚线·灰墨）——色+线形双通道；图例配最新值 -->
        <template v-else>
          <div class="infusion-trend-legend">
            <span class="infusion-trend-legend-item">
              <i class="infusion-trend-swatch infusion-trend-swatch--remain" aria-hidden="true"></i>
              余量 ml（最新 {{ lastRemainText }}）
            </span>
            <span class="infusion-trend-legend-item">
              <i class="infusion-trend-swatch infusion-trend-swatch--drop" aria-hidden="true"></i>
              滴速 ml/h（最新 {{ lastDropText }}）
            </span>
          </div>
          <svg
            v-if="!trendEmpty"
            class="infusion-trend-chart"
            viewBox="0 0 520 180"
            role="img"
            aria-label="余量与滴速 24 小时趋势曲线"
          >
            <!-- 体温单网格语法（fuy-chart-grid 发丝）：四道横规 -->
            <g class="infusion-trend-grid" shape-rendering="crispEdges">
              <line x1="8" y1="10" x2="512" y2="10" />
              <line x1="8" y1="60" x2="512" y2="60" />
              <line x1="8" y1="110" x2="512" y2="110" />
              <line x1="8" y1="160" x2="512" y2="160" />
            </g>
            <polyline
              v-if="remainPoints.length > 0"
              class="infusion-trend-line infusion-trend-line--remain"
              :points="remainPolyline"
            />
            <polyline
              v-if="dropPoints.length > 0"
              class="infusion-trend-line infusion-trend-line--drop"
              :points="dropPolyline"
            />
          </svg>
          <!-- 时间轴刻度（起点/中点/终点） -->
          <div v-if="trendTicks.length > 0" class="infusion-trend-ticks">
            <span v-for="tick in trendTicks" :key="tick" class="fuy-num">{{ tick }}</span>
          </div>
          <!-- 后端数据说明注记（InfusionHistoryVO.note，有则诚实呈现） -->
          <p v-if="trendHistory?.note" class="infusion-trend-data-note">{{ trendHistory.note }}</p>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4）：页面根不挂 .fuy-stagger 后根级 flex gap 失效，纵排与区块间距
   由本层同值承接（.fuy-page 同构 flex+gap，binding-flow/discharge-flow 同构） */
.infusion-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：WS 胶囊 + 病区下拉 + 刷新右挂（蓝图丁-1.2；乙-5 状态位同位语法；flex-wrap
   与丁-2/丁-3 同构——窄窗下状态位换行不溢出） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 病区下拉（native select 与 EP 密度口径对齐；围合取值直取 --fuy-* 语义通道——石规描边+
   最亮纸白，BindingManage 同形） */
.infusion-ward-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

.infusion-ward-select:disabled {
  color: var(--fuy-color-text-secondary);
  background: var(--fuy-palette-gray-50);
}

/* 病区清单失败恢复条（G4/G6 说问题给恢复：warning 语义左缘 + 洗底经 A-5 已批 EP light-9
   语义通道，零新色值） */
.infusion-ward-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--el-color-warning-light-9);
  border-left: 3px solid var(--fuy-color-warning-text);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
}

/* 输液呼叫联动提示条（告急语义，警示但留确认出口）：印泥朱描边 + 状态朱 alpha 派生洗底
   （洗底取 --fuy-color-danger-text（印泥朱状态通道）的 0.06 档 alpha 派生 rgba 字面量，
   十值纪律内 alpha 派生写 rgba 形式；替换旧中性灰洗底） */
.infusion-linkage-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-color-infusion-red);
  border-radius: var(--fuy-radius-md);
  background: rgba(180, 35, 24, 0.06);
}

.infusion-linkage-text {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-infusion-red);
}

/* 床卡墙：grid 轨道卡墙（G3 裁决，auto-fill 180px 轨道，换行即落下一轨不齐问题根除）+
   高密度一屏可见优先；will-change: transform 为 ⑨.3 STOMP 高频更新面唯一预声明例外
   （帧热刷新落响应式状态触发的重排层提示；scoped 样式随组件卸载移除，零常驻）；
   min-height 120px 锁 CLS */
.infusion-wall {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--fuy-space-3);
  min-height: 120px;
  will-change: transform;
}

/* 组头签行（>120 卡分组态）：grid-column 通栏 + 石规虚缝下缘（段界墨笔细线语义） */
.infusion-group-head {
  grid-column: 1 / -1;
  margin: 0;
  padding-bottom: var(--fuy-space-1);
  border-bottom: 1px dashed var(--fuy-color-rule-stone);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 床卡=纸面卡（G3：卡面亮纸 + 发丝描边 + radius-card，版面内零投影；button 语义承载点击
   开趋势抽屉，键盘可达）；flex 纵向序：设备号 → 余量 → 滴速 → 档位徽标 */
.infusion-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--fuy-space-1);
  margin: 0;
  padding: var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-card);
  background: var(--fuy-surface-card);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

/* 悬停=纸面亮度表达 + 描边转石规一档（阴影纪律：卡悬浮禁投影） */
.infusion-card:hover {
  border-color: var(--fuy-color-rule-stone);
  background: var(--fuy-palette-gray-50);
}

.infusion-card-device {
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
  color: var(--fuy-color-text-secondary);
}

/* 余量大数字（蓝图丁-1.3：24px/700 tabular=--fuy-font-size-3xl 关键计数档） */
.infusion-card-remain strong {
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
}

.infusion-card-drop {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 三档档位规线（G3 三通道之一：左缘 4px 状态规线；档位由后端判定，色值全为 infusion 语义
   token 别名——YELLOW→警示通道 / ORANGE→分级橙 / RED→印泥朱即 --fuy-color-danger-text
   状态朱通道（5ml 告急=危急语义走状态朱非装饰朱）） */
.fuy-infusion-card--none {
  border-left: 4px solid var(--fuy-color-rule-stone);
}

.fuy-infusion-card--yellow {
  border-left: 4px solid var(--fuy-color-infusion-yellow);
}

.fuy-infusion-card--yellow .infusion-card-remain strong,
.fuy-infusion-card--yellow .fuy-tag-aa {
  color: var(--fuy-color-infusion-yellow);
}

.fuy-infusion-card--orange {
  border-left: 4px solid var(--fuy-color-infusion-orange);
}

.fuy-infusion-card--orange .infusion-card-remain strong,
.fuy-infusion-card--orange .fuy-tag-aa {
  color: var(--fuy-color-infusion-orange);
}

.fuy-infusion-card--red {
  border-left: 4px solid var(--fuy-color-infusion-red);
}

.fuy-infusion-card--red .infusion-card-remain strong,
.fuy-infusion-card--red .fuy-tag-aa {
  color: var(--fuy-color-infusion-red);
}

/* 正常档关键数字弱化为正文色（防满屏强调） */
.fuy-infusion-card--none .infusion-card-remain strong {
  color: var(--fuy-color-text-emphasis);
}

/* 趋势抽屉：头部批注槽（G2「谁·何时」）+ 图例/刻度批注层级 */
.infusion-trend-note {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 图例（双通道：色样线形 + 文字；flex 布局免行内块） */
.infusion-trend-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-2);
}

.infusion-trend-legend-item {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.infusion-trend-swatch {
  width: 18px;
  height: 0;
  border-top: 2px solid var(--fuy-color-text-emphasis);
}

.infusion-trend-swatch--drop {
  border-top-style: dashed;
  border-top-color: var(--fuy-color-text-secondary);
}

/* 曲线容器：宽随抽屉自适应（viewBox 等比缩放）；零动画（无 transition/animation 声明） */
.infusion-trend-chart {
  display: block;
  width: 100%;
  height: auto;
}

/* 网格发丝（体温单 chart-grid 语法：--fuy-chart-grid-color 与卡内发丝同值；线宽 0.5 与
   体温单 .fuy-chart-grid 基准档同源，丁-1/丁-3 两图发丝口径一致） */
.infusion-trend-grid line {
  stroke: var(--fuy-chart-grid-color);
  stroke-width: 0.5;
}

/* 双序列线形（余量=实线浓墨 / 滴速=虚线灰墨——色+线形双通道，色弱可辨） */
.infusion-trend-line--remain {
  fill: none;
  stroke: var(--fuy-color-text-emphasis);
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.infusion-trend-line--drop {
  fill: none;
  stroke: var(--fuy-color-text-secondary);
  stroke-width: 1.5;
  stroke-dasharray: 4 3;
}

/* 时间轴刻度（起点/中点/终点三档） */
.infusion-trend-ticks {
  display: flex;
  justify-content: space-between;
  margin-top: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 后端数据说明注记（InfusionHistoryVO.note） */
.infusion-trend-data-note {
  margin: var(--fuy-space-3) 0 0;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 历史查询失败恢复条（G6 说问题给恢复：danger 语义左缘 + A-5 已批 EP light-9 洗底通道） */
.infusion-trend-error {
  padding: var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--el-color-danger-light-9);
  border-left: 3px solid var(--fuy-color-danger-text);
}

.infusion-trend-error-title {
  margin: 0 0 var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.infusion-trend-error-hint {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
</style>
