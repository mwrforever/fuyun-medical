<script setup lang="ts">
// 候诊叫号大屏页（FU-M03-05 大屏端前端面，设计文档 §3.4/§8.5）：诊区叫号暗色三段 grid——
// 头部（诊区名/时钟/连接呼吸点）+ 当前叫号卡（bg-elevated+glow，WS CALLED 帧 :key 票号重挂
// 触发 motion.css 三段编排）+ 候诊榜（REST 快照首屏 + 前 8 条两列 grid，斑马纹）。
// 受控演示面红线：VITE_BIGSCREEN_TOKEN 未配置时整页横幅 + 零出网（零 REST 零订阅）；
// 长时值守零泄漏：定时器仅时钟 1 个（onUnmounted 清理），WS 重连全交库内建。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { QueueTicketVO } from '@/api/outpatientQueue';
import { getQueueSnapshot } from '@/api/outpatientQueue';
import {
  connect,
  disconnect,
  isQueueTokenConfigured,
  subscribeQueue,
} from '@/composables/useQueueStomp';
import type { QueueCalledNotice } from '@/composables/useQueueStomp';
import { connectionState } from '@/composables/useQueueStomp';
import { useRoute, useRouter } from 'vue-router';

/** 候诊榜容量（§7.2：固定前 8 条，WS 快照数组 slice，不入 VirtualList） */
const WAITING_LIST_CAPACITY = 8;

/** 票据状态角标词表（暗色文字色，非 EP tag；§8.5 暗色版映射） */
const STATUS_BADGES: Record<string, { text: string; tone: 'secondary' | 'warn' | 'ok' }> = {
  WAITING: { text: '候诊', tone: 'secondary' },
  CALLED: { text: '已叫号', tone: 'warn' },
  PASSED: { text: '已过号', tone: 'warn' },
  SERVING: { text: '就诊中', tone: 'ok' },
  SERVED: { text: '已就诊', tone: 'secondary' },
  CANCELLED: { text: '已取消', tone: 'secondary' },
};

const route = useRoute();
const router = useRouter();

/** 受控演示面开关：令牌未配置 → 整页横幅 + 零出网（计算一次，构建期常量） */
const tokenConfigured = isQueueTokenConfigured();

/** 诊区编码（路由 query 可书签化；缺省内科演示诊区） */
const deptCode = ref(readDeptFromRoute());

function readDeptFromRoute(): string {
  const raw = route.query.dept;
  return typeof raw === 'string' && raw.trim() !== '' ? raw : 'DEPT-INT';
}

/** 切诊区：更新路由 query（书签化）→ 快照重拉 + 重订阅（watch 统一入口） */
function onDeptInput(event: Event): void {
  const value = (event.target as HTMLInputElement).value.trim();
  if (value === '' || value === deptCode.value) {
    return;
  }
  deptCode.value = value;
}

watch(deptCode, (next) => {
  void router.replace({ query: { dept: next } });
  if (tokenConfigured) {
    void loadSnapshot();
    queueStompSubscribe(next);
  }
});

/* ---------- 当前叫号卡（WS CALLED 帧 :key=ticketNo 重挂触发三段编排 §6.3） ---------- */
interface CurrentCall {
  ticketNo: string;
  patientName: string | null;
  room: string | null;
}

const currentCall = ref<CurrentCall | null>(null);

/** 引导语（§3.4：「请 X 号到 Y 诊室」；room 缺省为「指定诊室」兜底文案） */
const callGuideText = computed(() => {
  if (currentCall.value === null) {
    return '';
  }
  return `请 ${currentCall.value.ticketNo} 号到 ${currentCall.value.room ?? '指定'}诊室`;
});

/* ---------- 候诊榜（REST 快照首屏 + CALLED 帧该票离队本地移除 §8.5） ---------- */
const waiting = ref<QueueTicketVO[]>([]);
const snapshotFailed = ref(false);

/** 榜单容量裁剪（模板层不做 slice，防每次渲染重算——§7.2 固定前 8 条） */
const waitingTop = computed(() => waiting.value.slice(0, WAITING_LIST_CAPACITY));

function statusBadge(row: QueueTicketVO): { text: string; tone: 'secondary' | 'warn' | 'ok' } {
  return STATUS_BADGES[row.status ?? ''] ?? { text: row.status ?? '—', tone: 'secondary' };
}

/** REST 快照首屏（失败置横幅态，旧榜驻留防闪烁） */
async function loadSnapshot(): Promise<void> {
  try {
    waiting.value = await getQueueSnapshot(deptCode.value);
    snapshotFailed.value = false;
  } catch {
    snapshotFailed.value = true;
  }
}

/* ---------- WS 订阅（useQueueStomp 单例；帧驱动当前叫号 + 榜单离队） ---------- */

/** 当前叫号帧处理：票号变化才重挂三段编排（§6.3 触发条件），同时把该票从榜单移除 */
function onQueueFrame(notice: QueueCalledNotice): void {
  if (notice.type !== 'CALLED' || notice.ticketNo === null || notice.ticketNo === '') {
    // P1 通道恒 CALLED；后续类型随通道演进登记——未知类型本帧忽略（注记 §8.5）
    return;
  }
  if (currentCall.value?.ticketNo !== notice.ticketNo) {
    currentCall.value = {
      ticketNo: notice.ticketNo,
      patientName: notice.patientName,
      room: notice.room,
    };
  }
  // 该票离队：从候诊榜移除（fuy-flip leave 过渡由 TransitionGroup 承载）
  waiting.value = waiting.value.filter((row) => row.ticketNo !== notice.ticketNo);
}

/** 订阅/换诊区重订阅（单例代理句柄；组件卸载退订） */
let subscription: { unsubscribe: () => void } | null = null;

function queueStompSubscribe(dept: string): void {
  // 单例语义：重复调用自动替换在册订阅（useQueueStomp 换诊区重订阅语义）
  subscription?.unsubscribe();
  subscription = null;
  connect(dept);
  subscription = subscribeQueue(dept, onQueueFrame);
}

/* ---------- 时钟（全页唯一定时器，1s 文本直更无动画 §8.5；卸载必清理 §7.5） ---------- */
const clockText = ref('--:--:--');
let clockTimer: ReturnType<typeof setInterval> | null = null;

function tickClock(): void {
  const now = new Date();
  const pad = (value: number): string => String(value).padStart(2, '0');
  clockText.value = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

/** 连接状态文案（§8.5 断线横幅：呼吸点转灰 + 1.25rem 提示；恢复后自动重订阅续播） */
const connectionBannerVisible = computed(
  () => tokenConfigured && connectionState.value === 'disconnected',
);

/** 呼吸点三态色：连接中=品牌、已连接=ok、断开=灰（§8.5 常驻呼吸动画仅连接态点） */
const breathingDotClass = computed(() => {
  if (connectionState.value === 'connected') {
    return 'is-ok';
  }
  if (connectionState.value === 'connecting') {
    return 'is-brand';
  }
  return 'is-gray';
});

onMounted(() => {
  // 未配置令牌：整页横幅 + 零出网（零 REST 零订阅，§8.5 链路禁用口径）
  if (!tokenConfigured) {
    return;
  }
  tickClock();
  clockTimer = setInterval(tickClock, 1000);
  void loadSnapshot();
  queueStompSubscribe(deptCode.value);
});

onBeforeUnmount(() => {
  if (clockTimer !== null) {
    clearInterval(clockTimer);
    clockTimer = null;
  }
  // 订阅句柄退订 + 库 deactivate（useQueueStomp 断开条款；长时值守防实例泄漏 §7.5）
  void disconnect();
});
</script>

<template>
  <!-- 未配置大屏令牌：整页横幅（bg-panel + 琥珀描边）+ 零出网（§8.5） -->
  <div v-if="!tokenConfigured" class="queue-board queue-board--disabled">
    <div class="queue-board-banner" role="alert">
      <p class="queue-board-banner-title">未配置大屏令牌，已禁用数据链路</p>
      <p class="queue-board-banner-note">部署时经构建期变量 VITE_BIGSCREEN_TOKEN 注入后重启生效</p>
    </div>
  </div>

  <div v-else class="queue-board">
    <!-- 头部 96px：诊区名 + 时钟 + 连接呼吸点（§3.4） -->
    <header class="queue-board-header">
      <div class="queue-board-dept">
        <label class="queue-board-dept-label" for="queue-board-dept-input">诊区</label>
        <input
          id="queue-board-dept-input"
          class="queue-board-dept-input"
          :value="deptCode"
          spellcheck="false"
          @change="onDeptInput"
        />
      </div>
      <div class="queue-board-clock-wrap">
        <span
          class="queue-board-dot fuy-loading-essential"
          :class="breathingDotClass"
          aria-hidden="true"
        ></span>
        <span class="fuy-num queue-board-clock">{{ clockText }}</span>
      </div>
    </header>

    <!-- 断线横幅（恢复后自动重订阅续播——库内建重连 + onConnect 重订阅） -->
    <div v-if="connectionBannerVisible" class="queue-board-reconnect" role="status">
      连接中断，自动重连中
    </div>

    <!-- 当前叫号卡：bg-elevated + glow + 1px 品牌描边；:key=ticketNo 纯重挂触发三段编排（§6.3
         ——禁 Transition 包装：out-in 延迟入场违背「帧到达 → 立即入场」时序） -->
    <section class="queue-board-current" aria-live="polite">
      <div v-if="currentCall !== null" :key="currentCall.ticketNo" class="fuy-call-card">
        <p class="queue-board-guide">{{ callGuideText }}</p>
        <p class="fuy-num fuy-call-no queue-board-ticket-no">{{ currentCall.ticketNo }}</p>
        <p class="queue-board-patient">{{ currentCall.patientName ?? '请核对票号' }}</p>
      </div>
      <div v-else class="queue-board-current-idle">
        <p class="queue-board-guide">等待叫号</p>
      </div>
    </section>

    <!-- 候诊榜：前 8 条两列 grid 4×2，斑马纹偶数行（§3.4/§8.5） -->
    <section class="queue-board-waiting" aria-label="候诊名单">
      <p v-if="snapshotFailed" class="queue-board-reconnect">快照加载失败，等待下次刷新</p>
      <TransitionGroup v-else name="fuy-flip" tag="ol" class="queue-board-waiting-grid">
        <li
          v-for="(row, index) in waitingTop"
          :key="row.id"
          class="queue-board-row"
          :class="{ 'is-even': index % 2 === 1 }"
        >
          <span class="fuy-num queue-board-row-index">{{ index + 1 }}</span>
          <span class="fuy-num queue-board-row-ticket">{{ row.ticketNo }}</span>
          <span class="queue-board-row-badge" :class="`is-${statusBadge(row).tone}`">{{
            statusBadge(row).text
          }}</span>
        </li>
      </TransitionGroup>
      <p v-if="!snapshotFailed && waitingTop.length === 0" class="queue-board-empty">
        当前诊区候诊队列为空
      </p>
    </section>
  </div>
</template>

<style scoped>
/* 布局（§3.4）：100dvh grid rows 96px/1fr/1.2fr，panel 间距 1.5rem；字号全 rem（§2.3） */
.queue-board {
  display: grid;
  grid-template-rows: 96px 1fr 1.2fr;
  gap: 1.5rem;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--fuy-screen-bg-base);
  color: var(--fuy-screen-text-primary);
}

/* 未配置令牌的整页横幅态（bg-panel + 琥珀描边 §8.5） */
.queue-board--disabled {
  display: flex;
  align-items: center;
  justify-content: center;
  grid-template-rows: none;
}
.queue-board-banner {
  padding: 3rem 4rem;
  border: 1px solid var(--fuy-screen-warn);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
  text-align: center;
}
.queue-board-banner-title {
  margin: 0 0 1rem;
  font-size: 2.5rem;
  font-weight: 700;
  color: var(--fuy-screen-warn);
}
.queue-board-banner-note {
  margin: 0;
  font-size: 1.25rem;
  color: var(--fuy-screen-text-secondary);
}

/* 头部：诊区名/时钟 3rem；呼吸点 8px 常驻（豁免面 .fuy-loading-essential） */
.queue-board-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.queue-board-dept {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.queue-board-dept-label {
  font-size: 1.5rem;
  color: var(--fuy-screen-text-secondary);
}
.queue-board-dept-input {
  width: 12rem;
  height: 2.5rem;
  padding: 0 0.75rem;
  border: 1px solid var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-screen-bg-panel);
  color: var(--fuy-screen-text-primary);
  font-size: 1.5rem;
}
.queue-board-dept-input:focus {
  outline: none;
  border-color: var(--fuy-screen-brand);
}
.queue-board-clock-wrap {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.queue-board-clock {
  font-size: 3rem;
  font-weight: 700;
}
.queue-board-dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: var(--fuy-radius-full);
  /* 常驻呼吸动画 keyframe 在 motion.css（.fuy-breath 类承载豁免面与动画定义，§7.5） */
  animation: fuy-breath 1.2s linear infinite alternate;
}
.queue-board-dot.is-ok {
  background: var(--fuy-screen-ok);
}
.queue-board-dot.is-brand {
  background: var(--fuy-screen-brand);
}
.queue-board-dot.is-gray {
  background: var(--fuy-screen-text-secondary);
}

/* 断线/快照失败横幅 1.25rem（§8.5） */
.queue-board-reconnect {
  padding: 0.5rem 1rem;
  border: 1px solid var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
  color: var(--fuy-screen-warn);
  font-size: 1.25rem;
  text-align: center;
}

/* 当前叫号卡：bg-elevated + glow + 1px 品牌描边（§8.5）；will-change 在 motion.css 唯一允许面 */
.queue-board-current {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--fuy-screen-brand);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-elevated);
  box-shadow: var(--fuy-screen-glow);
}
.queue-board-current-idle {
  text-align: center;
}
.queue-board-guide {
  margin: 0 0 1rem;
  font-size: 1.75rem;
  color: var(--fuy-screen-text-secondary);
}
.queue-board-ticket-no {
  margin: 0;
  font-size: 10rem;
  font-weight: 700;
  line-height: 1.1;
  color: var(--fuy-screen-call);
}
.queue-board-patient {
  margin: 1rem 0 0;
  font-size: 4.5rem;
  font-weight: 600;
}
.queue-board-current .fuy-call-card {
  text-align: center;
}

/* 候诊榜：前 8 条两列 grid 4×2；行卡斑马纹偶数行 #0d2132（§8.5 唯一允许常量） */
.queue-board-waiting {
  min-height: 0;
}
.queue-board-waiting-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  margin: 0;
  padding: 0;
  list-style: none;
}
.queue-board-row {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  padding: 0.75rem 1.5rem;
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-screen-bg-panel);
}
.queue-board-row.is-even {
  background: #0d2132;
}
.queue-board-row-index {
  min-width: 2.5rem;
  font-size: 1.5rem;
  color: var(--fuy-screen-text-secondary);
}
.queue-board-row-ticket {
  flex: 1;
  font-size: 2.5rem;
  font-weight: 700;
}
.queue-board-row-badge {
  font-size: 1.25rem;
}
.queue-board-row-badge.is-secondary {
  color: var(--fuy-screen-text-secondary);
}
.queue-board-row-badge.is-warn {
  color: var(--fuy-screen-warn);
}
.queue-board-row-badge.is-ok {
  color: var(--fuy-screen-ok);
}
.queue-board-empty {
  margin: 1rem 0 0;
  font-size: 1.75rem;
  color: var(--fuy-screen-text-secondary);
  text-align: center;
}
</style>
