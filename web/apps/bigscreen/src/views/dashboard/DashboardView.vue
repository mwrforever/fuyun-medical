<script setup lang="ts">
// IoT 运营大屏（FU-M14-13 前端面，P2 PR-2 Task 17）：暗色五区布局壳——顶部全院指标带
// （设备在线比/离线/告警活跃/积压水位/质量分 + 风暴态通栏横幅）+ 左病区设备状态墙 +
// 中活跃告警列表（WS /topic/iot/alarm/{wardId} 增量前插）+ 右遥测趋势 echarts 图
// （series 1min 桶，按需注册）+ 底部遥测摘要（WS /topic/iot/telemetry/{wardId} 最近一帧）。
//
// 双通道承载（web B.3-3/B.3-4）：WS 经 useIotStomp 单例订三主题（token 取 sessionStorage，
// 与遥测页同键面）；WS 不可用（令牌缺失/断连）时 REST 10s 轮询降级（dashboard/wards 两
// 端点 + series/alarms 兜底），已连接态与页面隐藏态跳过轮询。本组件只做组装与轮询编排
// （QueueBoardView 同款先例），图表生命周期在 useEChart，帧解析在 utils/iotMessage。
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue';
import { useRoute } from 'vue-router';
import { alarms, dashboard, telemetry } from '@/api/iot';
import type { AlarmVO, BedDeviceItem, DashboardSummaryVO, TelemetryPoint } from '@/api/iot';
import { useEChart } from '@/composables/useEChart';
import {
  DASHBOARD_GLOBAL_TOPIC,
  IOT_TOKEN_STORAGE_KEY,
  alarmTopicPath,
  connect as stompConnect,
  connectionState,
  disconnect as stompDisconnect,
  subscribeIotTopic,
  subscribeTelemetrySummary,
} from '@/composables/useIotStomp';
import type { StompSubscription } from '@stomp/stompjs';
import type { TelemetrySummary } from '@/types/iot';
import { parseDashboardSummary, parseIotAlarmFrame } from '@/utils/iotMessage';
import type { EChartsOption } from '@/utils/echarts';

/** 病区 ID 合法形态：纯数字字符串（状态墙/告警/遥测主题路径参数同口径） */
const WARD_ID_PATTERN = /^\d+$/;

/** 路由 query 缺省病区（演示病区，与遥测页示例值一致；书签化部署按 query 覆盖） */
const DEFAULT_WARD_ID = '1001';

/** REST 轮询降级周期（毫秒，brief 冻结值 10s；WS 就绪时跳过） */
const REST_POLL_INTERVAL_MS = 10000;

/** 趋势曲线时窗（毫秒）：近 1 小时，1 分钟聚合桶（brief 冻结口径） */
const SERIES_WINDOW_MS = 3600000;

/** 趋势曲线默认指标（指标字典种子 MDC_ECG_HEART_RATE，大屏心率趋势主视角） */
const DEFAULT_METRIC_CODE = 'MDC_ECG_HEART_RATE';

/** 中列告警容量（新帧前插 + 截断防长时值守内存无界增长） */
const ALARM_LIST_CAPACITY = 20;

/** 路由注入（wardId query 书签化读取） */
const route = useRoute();

/** 病区 ID（路由 query 书签化；非法/缺失回退演示病区） */
const wardId = ref(readWardFromRoute());

function readWardFromRoute(): string {
  const raw = route.query['wardId'];
  return typeof raw === 'string' && WARD_ID_PATTERN.test(raw) ? raw : DEFAULT_WARD_ID;
}

/* ---------- 顶部全院指标带（REST summary 首屏 + dashboard/global 帧覆盖） ---------- */
const summary = ref<DashboardSummaryVO | null>(null);

/** 指标带展示项（值域：展示字符串 + 语义 tone 类；数值均为后端字符串化 long 原样承载） */
const metricItems = computed(() => {
  const current = summary.value;
  if (current === null) {
    return [];
  }
  const activeAlarmCount = Number(current.activeAlarmCount ?? '0');
  return [
    {
      label: '设备在线',
      value: `${current.onlineCount ?? '-'}/${current.deviceTotal ?? '-'}`,
      tone: 'is-ok',
    },
    { label: '设备离线', value: current.offlineCount ?? '-', tone: 'is-warn' },
    // 告警活跃 >0 转红（危急处置提示），0 保持主文本色
    {
      label: '告警活跃',
      value: current.activeAlarmCount ?? '-',
      tone: activeAlarmCount > 0 ? 'is-alert' : '',
    },
    { label: '积压水位', value: String(current.backlogEstimate ?? '-'), tone: '' },
    { label: '质量分', value: String(current.qualityScore ?? '-'), tone: '' },
  ];
});

/* ---------- 左病区设备状态墙（REST wardWall 首屏 + 降级轮询刷新） ---------- */
const wallItems = ref<BedDeviceItem[]>([]);

/** 设备状态五态词表（暗色文字色语义映射：在线 ok/离线 warn/异常红/停用与未激活灰） */
const DEVICE_STATUS_META: Record<string, { text: string; tone: string }> = {
  ONLINE: { text: '在线', tone: 'is-ok' },
  OFFLINE: { text: '离线', tone: 'is-warn' },
  ABNORMAL: { text: '异常', tone: 'is-alert' },
  DISABLED: { text: '已停用', tone: 'is-muted' },
  INACTIVE: { text: '未激活', tone: 'is-muted' },
};

/** 床位标签（移动式绑定无床位，显示「移动」；固定式显示「N 床」） */
function bedLabel(bedId: string | undefined): string {
  return bedId === undefined || bedId === '' ? '移动' : `${bedId} 床`;
}

function statusMeta(status: string | undefined): { text: string; tone: string } {
  return DEVICE_STATUS_META[status ?? ''] ?? { text: status ?? '-', tone: 'is-muted' };
}

/* ---------- 中活跃告警列表（REST 分页首屏 + WS alarm 帧前插，按 alarmNo 去重） ---------- */
/** 告警行视图模型（REST AlarmVO 与 WS 帧的公共展示投影；occurredAt 原样 ISO 字符串） */
interface AlarmRowVM {
  alarmNo: string;
  alarmLevel: string;
  metricCode: string;
  triggerValue: string;
  deviceId: string;
  occurredAt: string;
}

const alarmRows = ref<AlarmRowVM[]>([]);

/** 告警级别词表（危急红/警告橙/提示蓝——分诊四级 token 的三档复用，workstation 同语义） */
const ALARM_LEVEL_META: Record<string, { text: string; tone: string }> = {
  CRITICAL: { text: '危急', tone: 'is-alert' },
  WARNING: { text: '警告', tone: 'is-warn' },
  INFO: { text: '提示', tone: 'is-info' },
};

function levelMeta(level: string | undefined): { text: string; tone: string } {
  return ALARM_LEVEL_META[level ?? ''] ?? { text: level ?? '-', tone: 'is-muted' };
}

/** REST 告警行 → 展示投影（lastTriggeredAt 为业务发生时刻锚点） */
function toAlarmRow(alarm: AlarmVO): AlarmRowVM {
  return {
    alarmNo: alarm.alarmNo ?? '',
    alarmLevel: alarm.alarmLevel ?? '',
    metricCode: alarm.metricCode ?? '',
    triggerValue: alarm.triggerValue ?? '',
    deviceId: alarm.deviceId ?? '',
    occurredAt: alarm.lastTriggeredAt ?? '',
  };
}

/** WS 告警帧前插（按 alarmNo 去重：重复帧先移除旧行再置顶，容量截断防内存无界） */
function prependAlarmFrame(row: AlarmRowVM): void {
  alarmRows.value = [
    row,
    ...alarmRows.value.filter((existing) => existing.alarmNo !== row.alarmNo),
  ].slice(0, ALARM_LIST_CAPACITY);
}

/* ---------- 右遥测趋势图（REST series 首屏 + 降级轮询刷新；生命周期在 useEChart） ---------- */
const seriesPoints = ref<TelemetryPoint[]>([]);
/** 图表容器模板引用（useTemplateRef 3.5 基线；挂载后由 useEChart 惰性 init） */
const seriesChart = useTemplateRef<HTMLDivElement>('seriesChart');

/** 读 tokens.css 语义变量色值（echarts canvas 无法消费 CSS var，运行时取既有 token 值，
 * 禁自创色值；非浏览器/测试环境返回 undefined 由 echarts 默认色兜底） */
function tokenColor(name: string): string | undefined {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value === '' ? undefined : value;
}

/** 时刻格式化（ISO → HH:mm 类目轴标签；解析失败回退原始字符串） */
function formatClock(iso: string | undefined): string {
  if (iso === undefined) {
    return '-';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 趋势图 option（min/max/avg 三线：均值品牌主线，上下界次级灰细线；色值全部取 token） */
function buildSeriesOption(): EChartsOption {
  const points = seriesPoints.value;
  const brand = tokenColor('--fuy-screen-brand');
  const secondary = tokenColor('--fuy-screen-text-secondary');
  const elevated = tokenColor('--fuy-screen-bg-elevated');
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: elevated,
      borderColor: secondary,
      textStyle: { color: tokenColor('--fuy-screen-text-primary') },
    },
    grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: points.map((point) => formatClock(point.time)),
      axisLabel: { color: secondary },
      axisLine: { lineStyle: { color: secondary } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: secondary },
      splitLine: { lineStyle: { color: elevated } },
    },
    series: [
      {
        name: '均值',
        type: 'line',
        data: points.map((point) => point.avg ?? null),
        showSymbol: false,
        lineStyle: { width: 2, color: brand },
        itemStyle: { color: brand },
      },
      {
        name: '最小值',
        type: 'line',
        data: points.map((point) => point.min ?? null),
        showSymbol: false,
        lineStyle: { width: 1, color: secondary },
      },
      {
        name: '最大值',
        type: 'line',
        data: points.map((point) => point.max ?? null),
        showSymbol: false,
        lineStyle: { width: 1, color: secondary },
      },
    ],
  };
}

const { refresh: refreshChart } = useEChart(seriesChart, buildSeriesOption);

// 曲线数据更新后重渲染（REST 首屏与降级轮询刷新共用；未初始化时 refresh 幂等跳过）
watch(seriesPoints, () => {
  refreshChart();
});

/* ---------- 底部遥测摘要（WS /topic/iot/telemetry/{wardId} 最近一帧覆盖渲染） ---------- */
const telemetrySummary = ref<TelemetrySummary | null>(null);

/* ---------- REST 首屏 + 10s 轮询降级编排 ---------- */
/** REST 加载失败标记（任一资源失败置位，横幅提示；下一轮轮询自愈） */
const restFailed = ref(false);

/** 访问令牌（setup 期一次性读取；大屏无登录页，令牌经遥测页注入 sessionStorage，键值禁入日志） */
const storedToken = sessionStorage.getItem(IOT_TOKEN_STORAGE_KEY) ?? '';

/** 访问令牌缺失（缺失=WS 链路停用，REST 轮询承载） */
const tokenMissing = ref(storedToken === '');

/** 全院摘要加载（Redis 快照 TTL 5s，轮询侧天然节流） */
async function loadSummary(): Promise<void> {
  summary.value = await dashboard.summary();
}

/** 病区状态墙加载 */
async function loadWardWall(): Promise<void> {
  const wall = await dashboard.wardWall(wardId.value);
  wallItems.value = wall.items ?? [];
}

/** 趋势曲线加载（近 1 小时 1min 桶，ward 作用域默认指标） */
async function loadSeries(): Promise<void> {
  seriesPoints.value = await telemetry.series({
    scope: 'ward',
    wardId: wardId.value,
    metricCode: DEFAULT_METRIC_CODE,
    from: new Date(Date.now() - SERIES_WINDOW_MS).toISOString(),
    to: new Date().toISOString(),
    granularity: '1min',
  });
}

/** 活跃告警分页加载（WS 断连期中列兜底数据源） */
async function loadAlarms(): Promise<void> {
  const page = await alarms.list({
    page: 1,
    size: ALARM_LIST_CAPACITY,
    wardId: wardId.value,
    status: 'ACTIVE',
  });
  alarmRows.value = (page.content ?? []).map(toAlarmRow);
}

/** REST 四资源全量刷新（Promise.allSettled 并行：单资源失败不拖垮其余区域首屏） */
async function loadAll(): Promise<void> {
  restFailed.value = false;
  const loaders = [loadSummary(), loadWardWall(), loadSeries(), loadAlarms()];
  const results = await Promise.allSettled(loaders);
  // 任一资源失败即置横幅（ScreenApiError 已归一化，无需读取 detail）
  if (results.some((result) => result.status === 'rejected')) {
    restFailed.value = true;
  }
}

/** 轮询 tick（10s）：页面隐藏暂停（B.3-4）、WS 已连接跳过（推送驱动承载），否则 REST 降级刷新 */
function pollTick(): void {
  if (document.hidden) {
    return;
  }
  if (connectionState.value === 'connected') {
    return;
  }
  void loadAll();
}

/* ---------- WS 三主题订阅（useIotStomp 单例；句柄统一登记，卸载全量退订） ---------- */
const subscriptions: StompSubscription[] = [];

/** 订阅三主题：遥测摘要（底部）/ 告警增量（中列前插）/ 全院摘要（顶部指标带覆盖） */
function subscribeAll(): void {
  subscriptions.push(
    subscribeTelemetrySummary(wardId.value, (frame) => {
      telemetrySummary.value = frame;
    }),
  );
  subscriptions.push(
    subscribeIotTopic(alarmTopicPath(wardId.value), parseIotAlarmFrame, (frame) => {
      prependAlarmFrame({
        alarmNo: frame.alarmNo,
        alarmLevel: frame.alarmLevel,
        metricCode: frame.metricCode,
        triggerValue: frame.triggerValue,
        deviceId: frame.deviceId,
        occurredAt: frame.occurredAt,
      });
    }),
  );
  subscriptions.push(
    subscribeIotTopic(DASHBOARD_GLOBAL_TOPIC, parseDashboardSummary, (frame) => {
      summary.value = frame;
    }),
  );
}

/** 轮询定时器句柄（全页唯一定时器；卸载必清理——长时值守零泄漏） */
let pollTimer: ReturnType<typeof setInterval> | null = null;

onMounted(() => {
  // REST 首屏先行（匿名只读面，不依赖令牌）；WS 就绪后由推送驱动、轮询自动退位
  void loadAll();
  if (storedToken !== '') {
    stompConnect({ token: storedToken, wardId: wardId.value });
    subscribeAll();
  }
  pollTimer = setInterval(pollTick, REST_POLL_INTERVAL_MS);
});

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  // 订阅句柄全量退订（B.3-3 卸载条款）+ 单例断连（deactivate 取消库内建重连）
  for (const subscription of subscriptions) {
    subscription.unsubscribe();
  }
  subscriptions.length = 0;
  void stompDisconnect();
});

/* ---------- 头部链路状态与降级横幅 ---------- */

/** 状态徽标文案（三态中文；连接中不展示降级横幅——等待库内建重连结果） */
const stateLabel = computed(() => {
  switch (connectionState.value) {
    case 'connected':
      return '已连接';
    case 'connecting':
      return '连接中';
    case 'disconnected':
      return '未连接';
  }
});

/** 呼吸点三态色（ok 绿/brand 青/gray 灰——QueueBoardView 同款语义） */
const stateDotClass = computed(() => {
  if (connectionState.value === 'connected') {
    return 'is-ok';
  }
  if (connectionState.value === 'connecting') {
    return 'is-brand';
  }
  return 'is-gray';
});

/** 降级横幅文案（空串=不渲染）：令牌缺失与断连两态，均以 REST 10s 轮询承载 */
const degradedText = computed(() => {
  if (tokenMissing.value) {
    return '未注入访问令牌，实时链路停用，REST 每 10 秒轮询承载';
  }
  if (connectionState.value === 'disconnected') {
    return '实时链路中断，REST 每 10 秒轮询降级中';
  }
  return '';
});
</script>

<template>
  <section class="dashboard-view">
    <!-- 头部：站点名 + 病区书签标识 + 链路状态呼吸点 -->
    <header class="dashboard-header">
      <div class="dashboard-header-title">
        <h1>IoT 运营大屏</h1>
        <span class="dashboard-ward fuy-num">病区 {{ wardId }}</span>
      </div>
      <div class="dashboard-link">
        <span
          class="dashboard-dot fuy-breath fuy-loading-essential"
          :class="stateDotClass"
          aria-hidden="true"
        ></span>
        <span class="dashboard-link-state">{{ stateLabel }}</span>
      </div>
    </header>

    <!-- 降级横幅（令牌缺失/断连态；连接中等待重连不渲染） -->
    <div v-if="degradedText !== ''" class="dashboard-banner" role="status">{{ degradedText }}</div>

    <!-- 风暴态通栏横幅（全院摘要 stormActive 语义，REST 与 WS 帧同源驱动） -->
    <div v-if="summary?.stormActive" class="dashboard-banner dashboard-banner--storm" role="alert">
      告警风暴进行中，请优先处置危急告警
    </div>

    <!-- REST 失败横幅（下一轮轮询自愈） -->
    <div v-if="restFailed" class="dashboard-banner" role="alert">数据加载失败，自动重试中</div>

    <!-- 顶部全院指标带：五项聚合（在线比/离线/告警活跃/积压水位/质量分） -->
    <ul class="dashboard-metrics" aria-label="全院运营指标">
      <li v-for="metric in metricItems" :key="metric.label" class="dashboard-metric">
        <span class="dashboard-metric-label">{{ metric.label }}</span>
        <span class="dashboard-metric-value fuy-num" :class="metric.tone">{{ metric.value }}</span>
      </li>
    </ul>

    <!-- 主区三列：左状态墙 / 中告警列表 / 右趋势图 -->
    <div class="dashboard-main">
      <section class="dashboard-panel" aria-label="病区设备状态墙">
        <h2 class="dashboard-panel-title">病区设备状态墙</h2>
        <p v-if="wallItems.length === 0" class="dashboard-empty">暂无绑定设备</p>
        <ul v-else class="dashboard-wall">
          <li v-for="item in wallItems" :key="item.deviceId" class="dashboard-bed">
            <div class="dashboard-bed-head">
              <span class="dashboard-bed-name fuy-num">{{ bedLabel(item.bedId) }}</span>
              <span class="dashboard-bed-device">{{ item.deviceName ?? item.deviceId }}</span>
              <span class="dashboard-bed-status" :class="statusMeta(item.status).tone">{{
                statusMeta(item.status).text
              }}</span>
            </div>
            <div class="dashboard-bed-values">
              <span
                v-for="(value, index) in item.latestValues ?? []"
                :key="`${value.metricCode ?? 'metric'}-${index}`"
                class="dashboard-bed-value fuy-num"
                >{{ value.metricCode ?? '-' }} {{ value.value ?? '-' }}</span
              >
            </div>
          </li>
        </ul>
      </section>

      <section class="dashboard-panel" aria-label="活跃告警">
        <h2 class="dashboard-panel-title">活跃告警</h2>
        <p v-if="alarmRows.length === 0" class="dashboard-empty">暂无活跃告警</p>
        <ol v-else class="dashboard-alarms">
          <li v-for="row in alarmRows" :key="row.alarmNo" class="dashboard-alarm">
            <span class="dashboard-alarm-level" :class="levelMeta(row.alarmLevel).tone">{{
              levelMeta(row.alarmLevel).text
            }}</span>
            <span class="dashboard-alarm-no fuy-num">{{ row.alarmNo }}</span>
            <span class="dashboard-alarm-desc fuy-num"
              >{{ row.metricCode }} {{ row.triggerValue }}</span
            >
            <span class="dashboard-alarm-time fuy-num">{{ row.occurredAt }}</span>
          </li>
        </ol>
      </section>

      <section class="dashboard-panel dashboard-chart-panel" aria-label="遥测趋势">
        <h2 class="dashboard-panel-title">遥测趋势（近 1 小时 · 1 分钟桶）</h2>
        <!-- echarts 容器：尺寸随面板弹性伸缩，init/resize/dispose 生命周期在 useEChart -->
        <div ref="seriesChart" class="dashboard-chart"></div>
      </section>
    </div>

    <!-- 底部遥测摘要：WS 最近一帧（count/occurredAtUpperBound/items 明细） -->
    <section class="dashboard-panel" aria-label="遥测摘要">
      <h2 class="dashboard-panel-title">遥测摘要</h2>
      <p v-if="telemetrySummary === null" class="dashboard-empty">暂无遥测数据</p>
      <template v-else>
        <p class="dashboard-telemetry-meta">
          本批 <span class="fuy-num">{{ telemetrySummary.count }}</span> 条 · 最近批次
          <span class="fuy-num">{{ telemetrySummary.occurredAtUpperBound }}</span>
        </p>
        <ul class="dashboard-telemetry-items">
          <li
            v-for="(item, index) in telemetrySummary.items"
            :key="`${item.deviceId}-${item.metricCode}-${index}`"
            class="dashboard-telemetry-item"
          >
            {{ item.deviceId }} · {{ item.metricCode }}
          </li>
        </ul>
      </template>
    </section>
  </section>
</template>

<style scoped>
/* 布局壳（§2.5 大屏面）：flex 纵向五段（头部/横幅/指标带/主区/摘要），主区弹性占满、
   100dvh 零滚动（分区内滚动）；字号全 rem（§2.3 根字号随视口缩放）；
   底色/文本/描边消费 tokens.css 暗色语义变量，零自创色值 */
.dashboard-view {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-height: 100dvh;
  padding: 1rem;
  background: var(--fuy-screen-bg-base);
  color: var(--fuy-screen-text-primary);
}

/* 头部：站点名 2.5rem + 病区标识 1.5rem；链路呼吸点 8px 常驻（豁免面 .fuy-loading-essential） */
.dashboard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.dashboard-header-title {
  display: flex;
  align-items: baseline;
  gap: 1rem;
}
.dashboard-header h1 {
  margin: 0;
  font-size: 2.5rem;
  font-weight: 700;
}
.dashboard-ward {
  font-size: 1.5rem;
  color: var(--fuy-screen-text-secondary);
}
.dashboard-link {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.25rem;
}
.dashboard-dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: var(--fuy-radius-full);
  background: currentColor;
}
.dashboard-dot.is-ok {
  color: var(--fuy-screen-ok);
}
.dashboard-dot.is-brand {
  color: var(--fuy-screen-brand);
}
.dashboard-dot.is-gray {
  color: var(--fuy-screen-text-secondary);
}
.dashboard-link-state {
  color: var(--fuy-screen-text-secondary);
}

/* 横幅通栏：降级/风暴/失败三态（面板底 + 描边；风暴态红字红描边醒目） */
.dashboard-banner {
  padding: 0.5rem 1rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
  color: var(--fuy-screen-text-secondary);
  font-size: 1.25rem;
  text-align: center;
}
.dashboard-banner--storm {
  border-color: var(--fuy-screen-triage-l1);
  color: var(--fuy-screen-triage-l1);
}

/* 顶部指标带：五项等宽卡（面板底 + hairline）；数值 2.5rem 等宽，语义 tone 文本色 */
.dashboard-metrics {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 0.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
}
.dashboard-metric {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0.75rem 1rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
}
.dashboard-metric-label {
  font-size: 1.25rem;
  color: var(--fuy-screen-text-secondary);
}
.dashboard-metric-value {
  font-size: 2.5rem;
  font-weight: 700;
}
.dashboard-metric-value.is-ok {
  color: var(--fuy-screen-ok);
}
.dashboard-metric-value.is-warn {
  color: var(--fuy-screen-warn);
}
.dashboard-metric-value.is-alert {
  color: var(--fuy-screen-triage-l1);
}

/* 主区三列：左 1.1fr / 中 1.2fr / 右 1.3fr；min-height 0 承载分区内滚动 */
.dashboard-main {
  display: grid;
  grid-template-columns: 1.1fr 1.2fr 1.3fr;
  gap: 0.75rem;
  flex: 1;
  min-height: 0;
}

/* 面板通用形态（§8.5 暗色面板语言）：panel 底 + hairline 描边 + radius-lg */
.dashboard-panel {
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
.dashboard-panel-title {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--fuy-screen-text-secondary);
}
.dashboard-empty {
  margin: auto 0;
  font-size: 1.25rem;
  color: var(--fuy-screen-text-secondary);
  text-align: center;
}

/* 左状态墙：床位卡单列滚动；状态词语义文本色（暗底亮色文字，远距可读） */
.dashboard-wall {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.dashboard-bed {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
}
.dashboard-bed-head {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 1.25rem;
}
.dashboard-bed-name {
  font-weight: 700;
}
.dashboard-bed-device {
  flex: 1;
}
.dashboard-bed-status.is-ok {
  color: var(--fuy-screen-ok);
}
.dashboard-bed-status.is-warn {
  color: var(--fuy-screen-warn);
}
.dashboard-bed-status.is-alert {
  color: var(--fuy-screen-triage-l1);
}
.dashboard-bed-status.is-muted {
  color: var(--fuy-screen-text-secondary);
}
.dashboard-bed-values {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  font-size: 1rem;
  color: var(--fuy-screen-text-secondary);
}

/* 中告警列表：行卡滚动；级别词语义文本色（危急红/警告橙/提示蓝） */
.dashboard-alarms {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}
.dashboard-alarm {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
  font-size: 1.25rem;
}
.dashboard-alarm-level.is-alert {
  color: var(--fuy-screen-triage-l1);
}
.dashboard-alarm-level.is-warn {
  color: var(--fuy-screen-triage-l2);
}
.dashboard-alarm-level.is-info {
  color: var(--fuy-screen-triage-l4);
}
.dashboard-alarm-level.is-muted {
  color: var(--fuy-screen-text-secondary);
}
.dashboard-alarm-no {
  font-weight: 700;
}
.dashboard-alarm-desc {
  flex: 1;
  color: var(--fuy-screen-text-secondary);
}
.dashboard-alarm-time {
  color: var(--fuy-screen-text-secondary);
}

/* 右趋势图面板：容器弹性占满面板剩余高度（echarts init 需非零尺寸） */
.dashboard-chart-panel {
  min-height: 0;
}
.dashboard-chart {
  flex: 1;
  min-height: 12rem;
}

/* 底部遥测摘要：元信息一行 + 明细 chips（流式换行） */
.dashboard-telemetry-meta {
  margin: 0;
  font-size: 1.25rem;
  color: var(--fuy-screen-text-secondary);
}
.dashboard-telemetry-items {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
}
.dashboard-telemetry-item {
  padding: 0.25rem 0.75rem;
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-base);
  font-size: 1.25rem;
}
</style>
