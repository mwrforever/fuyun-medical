<script setup lang="ts">
// 病区输液看板（/ward/infusion-board，M16 WS+REST 混合面前端面）：REST 全量快照（病区输液
// 设备床卡列表：余量/滴速最新值与告警档位，档位判定归后端 mapAlertLevel：YELLOW ≤15ml/
// ORANGE ≤10ml/RED ≤5ml）+ WS 增量（useIotStomp 订阅遥测摘要与病区告警双主题——摘要帧作为
// "有新遥测"信号触发热刷新拉取最新快照，告急帧渲染呼叫联动提示条）+ WS 不可用时 REST 轮询
// 降级（30s 周期，连接落地自动停止）+ 页面隐藏暂停轮询/订阅消费（EX-41：恢复可见立即刷
// 一轮）。三档着色一律 --fuy-color-* 语义 token（M16 增量别名），声音提示本批次未接入
// （静默提示面）。
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import type { StompSubscription } from '@stomp/stompjs';
import { INFUSION_ALERT_LABELS, WARD_OPTIONS, infusionBoard } from '@/api/ward';
import type { InfusionBoardVO } from '@/api/ward';
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
import { warn } from '@/utils/logger';

/** WS 不可用时的 REST 轮询降级周期（毫秒） */
const POLL_INTERVAL_MS = 30000;

/** 遥测帧驱动的热刷新最小间隔（毫秒）：摘要帧 2s 窗口合并，短窗内多帧只触发一次刷新 */
const FRAME_REFRESH_MIN_INTERVAL_MS = 2000;

/** 告警级别中文词表（联动提示条展示；与后端 AlarmLevel 三值口径一致） */
const ALARM_LEVEL_TEXT: Record<string, string> = {
  INFO: '提示',
  WARNING: '警告',
  CRITICAL: '危急',
};

/** 当前病区（纯数字字符串，与 WS 主题病区段同源；来源：病区选择器） */
const wardId = ref(WARD_OPTIONS[0].code);

/** 看板快照（REST 全量；null=尚未加载） */
const board = ref<InfusionBoardVO | null>(null);
const loading = ref(false);

/** 最近一条输液告急联动帧（null=无；banner 展示锚点） */
const lastShortageAlarm = ref<AlarmTriggeredPayload | null>(null);

/** 床卡列表（空安全：无快照/无设备均渲染空态） */
const devices = computed(() => board.value?.devices ?? []);

/** 档位中文词表反查（床卡档位标签） */
function alertLabel(code: string | undefined): string {
  return INFUSION_ALERT_LABELS[code ?? ''] ?? code ?? '—';
}

/** 床卡着色类（fuy-infusion-card--{level} 契约类：none/yellow/orange/red，色值经语义 token 承载） */
function cardClass(code: string | undefined): string {
  return `fuy-infusion-card--${(code ?? 'none').toLowerCase()}`;
}

/** 余量/滴速展示（无遥测数据占位不炸渲染） */
function metricText(value: number | undefined): string {
  return value === undefined ? '—' : String(value);
}

/* ==================== REST 全量 ==================== */

/** 拉取病区输液看板快照（REST 全量兜底面；失败弹错归响应拦截器，驻留旧快照） */
async function loadBoard(): Promise<void> {
  loading.value = true;
  try {
    board.value = await infusionBoard.byWard(wardId.value);
  } catch {
    // 失败弹错归响应拦截器；驻留旧快照
  } finally {
    loading.value = false;
  }
}

/* ==================== WS 增量（遥测摘要 + 病区告警双主题） ==================== */

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

/* ==================== REST 轮询降级 ==================== */

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

/** 连接状态展示串（页头状态徽标） */
const connectionText = computed(() => {
  if (connectionState.value === 'connected') {
    return 'WS 已连接';
  }
  return connectionState.value === 'connecting' ? 'WS 连接中' : 'WS 未连接（REST 轮询降级）';
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
 * 暂停轮询/订阅消费，释放后端扇出压力；暂停语义归各驱动点 document.hidden 守卫承载）。
 */
function onVisibilityChange(): void {
  if (document.hidden) {
    return;
  }
  void loadBoard();
}

onMounted(() => {
  void loadBoard();
  stompConnect();
  subscribeWard();
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
  <div class="fuy-page infusion-board fuy-stagger">
    <!-- 页头：标题 + 连接状态 + 病区选择 + 刷新 -->
    <header class="infusion-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="infusion-title">病区输液看板</h2>
      <span
        class="infusion-ws-state"
        :class="
          connectionState === 'connected' ? 'infusion-ws-state--on' : 'infusion-ws-state--off'
        "
        >{{ connectionText }}</span
      >
      <select
        v-model="wardId"
        class="infusion-ward-select"
        aria-label="病区"
        @change="onWardChange"
      >
        <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
          {{ ward.label }}
        </option>
      </select>
      <el-button :loading="loading" @click="loadBoard">刷新</el-button>
    </header>

    <!-- 输液呼叫联动提示条（输液告急帧触发；声音提示本批次未接入，静默提示面） -->
    <div
      v-if="lastShortageAlarm !== null"
      class="infusion-linkage-banner fuy-stagger"
      :style="{ '--fuy-stagger-index': 1 }"
      data-test="call-linkage-banner"
      role="alert"
    >
      <span class="infusion-linkage-text fuy-num">
        输液告急联动：{{ lastShortageAlarm.alarmNo }} ·
        {{ ALARM_LEVEL_TEXT[lastShortageAlarm.alarmLevel] ?? lastShortageAlarm.alarmLevel }} · 设备
        {{ lastShortageAlarm.deviceId }} · 触发值 {{ lastShortageAlarm.triggerValue }}ml
        （已联动病区呼叫，请及时处置）
      </span>
      <el-button link size="small" @click="lastShortageAlarm = null">知道了</el-button>
    </div>

    <!-- 床卡列表（三档着色；余量/滴速/档位由后端判定承载） -->
    <div
      v-loading="loading"
      class="infusion-card-grid fuy-stagger"
      :style="{ '--fuy-stagger-index': 2 }"
    >
      <div v-if="devices.length > 0">
        <div
          v-for="device in devices"
          :key="device.deviceId"
          class="infusion-card"
          :class="cardClass(device.alertLevel)"
        >
          <p class="infusion-card-device fuy-num">{{ device.deviceId }}</p>
          <p class="infusion-card-remain fuy-num">
            余量 <strong>{{ metricText(device.remainLatest) }}</strong> ml
          </p>
          <p class="infusion-card-drop fuy-num">
            滴速 {{ metricText(device.dropRateLatest) }} ml/h
          </p>
          <el-tag size="small" class="fuy-tag-aa" :class="cardClass(device.alertLevel)">
            {{ alertLabel(device.alertLevel) }}
          </el-tag>
        </div>
      </div>
      <el-empty v-else :image-size="72" description="本病区暂无输液设备遥测" />
    </div>
  </div>
</template>

<style scoped>
/* 输液看板工具行与床卡网格，token 取色禁自创色值 */
.infusion-toolbar {
  align-items: center;
}

.infusion-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

/* WS 连接状态徽标（描边文本两态） */
.infusion-ws-state {
  padding: 2px var(--fuy-space-2);
  border-radius: var(--fuy-radius-full);
  font-size: var(--fuy-font-size-xs);
}

.infusion-ws-state--on {
  border: 1px solid var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.infusion-ws-state--off {
  border: 1px solid var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.infusion-ward-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 输液呼叫联动提示条（告急语义，警示但留确认出口） */
.infusion-linkage-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-color-infusion-red);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
}

.infusion-linkage-text {
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-infusion-red);
}

/* 床卡网格：高密度病区一屏可见优先 */
.infusion-card-grid {
  min-height: 120px;
}

.infusion-card {
  display: inline-block;
  box-sizing: border-box;
  width: 180px;
  margin: 0 var(--fuy-space-3) var(--fuy-space-3) 0;
  padding: var(--fuy-space-3);
  vertical-align: top;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-lg);
  background: var(--el-bg-color);
}

.infusion-card p {
  margin: 0 0 var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.infusion-card-device {
  font-weight: 600;
}

.infusion-card-remain strong {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}

/* 三档着色（15/10/5ml 档位由后端判定）：描边与关键数字取同档语义色，
   none 正常弱化——色值全为语义 token 别名（M16 增量，零自创色值） */
.infusion-card--none {
  border-color: var(--el-border-color);
}

.infusion-card--yellow {
  border-color: var(--fuy-color-infusion-yellow);
}

.infusion-card--yellow strong,
.infusion-card--yellow .fuy-tag-aa {
  color: var(--fuy-color-infusion-yellow);
}

.infusion-card--orange {
  border-color: var(--fuy-color-infusion-orange);
}

.infusion-card--orange strong,
.infusion-card--orange .fuy-tag-aa {
  color: var(--fuy-color-infusion-orange);
}

.infusion-card--red {
  border-color: var(--fuy-color-infusion-red);
}

.infusion-card--red strong,
.infusion-card--red .fuy-tag-aa {
  color: var(--fuy-color-infusion-red);
}

/* 正常档关键数字弱化为正文色（防满屏强调） */
.infusion-card--none strong {
  color: var(--fuy-color-text-emphasis);
}
</style>
