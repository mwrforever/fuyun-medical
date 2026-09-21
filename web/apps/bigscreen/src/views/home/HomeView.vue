<script setup lang="ts">
// 最小遥测页（PR-5 B5.1 三区信息架构）：连接设置区（wardId 路由 query 可书签化 + 访问令牌
// 注入 sessionStorage，仅标签页周期存活——标签页关闭即清除，主动断开不清令牌以便重连）+
// 链路状态区（状态徽标/订阅主题/帧计数）+ 遥测摘要区（最近一帧覆盖渲染）。
// 本组件只做组装（web B.1 views 边界）：连接与订阅逻辑在 useIotTelemetry/useIotStomp，
// 摘要渲染在 TelemetrySummaryPanel。站点名 h1 为 App.spec 冒烟断言锚点（禁改名）。
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useIotTelemetry } from '@/composables/useIotTelemetry';
import { IOT_TOKEN_STORAGE_KEY } from '@/composables/useIotStomp';
import TelemetrySummaryPanel from './components/TelemetrySummaryPanel.vue';

const route = useRoute();
const router = useRouter();
const { latestSummary, frameCount, topicPath, connectionState, connect, disconnect } =
  useIotTelemetry();

/** 病区 ID 输入值：路由 query.wardId 回填（可书签化），仅认字符串形态的 query */
const wardIdInput = ref(typeof route.query['wardId'] === 'string' ? route.query['wardId'] : '');
/** 访问令牌输入值：从 sessionStorage 回填（同标签页刷新免重贴；键值禁入日志与快照） */
const tokenInput = ref(sessionStorage.getItem(IOT_TOKEN_STORAGE_KEY) ?? '');
/** 连接拦截提示文案（空串=无提示）：wardId 非法/令牌缺失时展示 */
const connectHint = ref('');

/** 状态徽标文案：三态中文标签（链路状态区） */
const stateLabel = computed(() => {
  switch (connectionState.value) {
    case 'connected':
      return '已连接';
    case 'connecting':
      return '连接中';
    case 'disconnected':
      return '已断开';
  }
});

/**
 * 连接点击：页面级校验承载用户提示（组合层同口径防御兜底）→ wardId 同步路由 query
 *（router.replace 不产生历史记录，刷新/书签可还原）→ 建连并订阅。
 */
function handleConnect(): void {
  const wardId = wardIdInput.value.trim();
  if (!/^\d+$/.test(wardId)) {
    connectHint.value = '请输入纯数字病区 ID（如 1001）';
    return;
  }
  const token = tokenInput.value.trim();
  if (token === '') {
    connectHint.value = '请先注入访问令牌（经登录接口获取后粘贴）';
    return;
  }
  connectHint.value = '';
  // wardId 变更经 router.replace 同步 query（可书签化）；同值导航被路由器去重，无需判等
  void router.replace({ query: { wardId } });
  connect(token, wardId);
}

/** 断开点击：组合层先退订后断连（deactivate 同时取消库内建自动重连） */
async function handleDisconnect(): Promise<void> {
  await disconnect();
}
</script>

<template>
  <section class="home-view">
    <h1>富云数据大屏</h1>
    <p class="home-view-subtitle">IoT 遥测链路最小页（P0）</p>

    <!-- 连接设置区：wardId 可书签化 + 令牌人工注入（大屏无登录页，P0 最小方案） -->
    <section class="connect-panel">
      <h2>连接设置</h2>
      <label class="connect-panel-field">
        <span>病区 ID</span>
        <input v-model="wardIdInput" type="text" placeholder="纯数字病区 ID，如 1001" />
      </label>
      <label class="connect-panel-field">
        <span>访问令牌</span>
        <input
          v-model="tokenInput"
          type="password"
          placeholder="登录接口获取的 accessToken"
          autocomplete="off"
        />
      </label>
      <div class="connect-panel-actions">
        <button type="button" @click="handleConnect">连接</button>
        <button v-if="connectionState === 'connected'" type="button" @click="handleDisconnect">
          断开
        </button>
      </div>
      <p v-if="connectHint !== ''" class="connect-panel-hint">{{ connectHint }}</p>
    </section>

    <!-- 链路状态区：连接状态徽标（呼吸点 + 文字）+ 当前订阅主题路径 + 已接收帧计数 -->
    <section class="link-panel">
      <span class="link-panel-state" :class="`link-panel-state--${connectionState}`">
        <!-- 呼吸点为纯装饰（状态语义由文字承载）；常驻呼吸动画经全局 .fuy-breath 类承载
             （motion.css 唯一 keyframes 来源，批次 5 移交项：scoped 副本已删）+ .fuy-loading-essential
             豁免类（motion.css reduce 兜底下降速不清除，值守语义停转=卡死误判） -->
        <span
          class="link-panel-state-dot fuy-breath fuy-loading-essential"
          aria-hidden="true"
        ></span>
        {{ stateLabel }}
      </span>
      <span>订阅主题：{{ topicPath ?? '未订阅' }}</span>
      <span
        >已接收帧数：<span class="fuy-num">{{ frameCount }}</span></span
      >
    </section>

    <!-- 遥测摘要区：最近一帧覆盖渲染 -->
    <TelemetrySummaryPanel :summary="latestSummary" :frame-count="frameCount" />
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：暗色遥测值守页（§9.5.1，与新 QueueBoardView §8.5 同一
   视觉语言）——底色/文本消费 tokens.css 暗色语义变量；完整大屏版式（图表/网格）随 P1 交付 */
.home-view {
  background: var(--fuy-screen-bg-base);
  color: var(--fuy-screen-text-primary);
  min-height: 100dvh;
  padding: 16px;
}

.home-view-subtitle {
  color: var(--fuy-screen-text-secondary);
}

.connect-panel {
  margin-top: 16px;
}

.connect-panel-field {
  display: block;
  margin-top: 8px;
}

.connect-panel-field span {
  color: var(--fuy-screen-text-secondary);
  display: inline-block;
  width: 72px;
}

/* 原生控件暗色基线（§4.4/§9.5.1）：panel 底 + hairline 描边（复合简写变量整条消费）
   + radius-md，输入文本 primary/占位提示 secondary 两档 */
.connect-panel-field input {
  background: var(--fuy-screen-bg-panel);
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-md);
  color: var(--fuy-screen-text-primary);
  padding: 4px 8px;
  width: 280px;
}

.connect-panel-field input::placeholder {
  color: var(--fuy-screen-text-secondary);
}

.connect-panel-actions {
  margin-top: 8px;
}

.connect-panel-actions button {
  background: var(--fuy-screen-bg-panel);
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-md);
  color: var(--fuy-screen-text-primary);
  cursor: pointer;
  padding: 4px 12px;
}

.connect-panel-actions button:hover {
  /* 悬停反馈（交互三态基础）：抬升一档面板底，无过渡动画（值守页克制口径） */
  background: var(--fuy-screen-bg-elevated);
}

/* 键盘焦点环由 motion.css 全局 :focus-visible 承载（brand 描边 + 0 0 0 3px 柔光环，
   与 §9.5.1 基线同值），scoped 零重复声明 */
.connect-panel-hint {
  color: var(--fuy-screen-warn);
  margin-top: 8px;
}

.link-panel {
  align-items: center;
  display: flex;
  gap: 24px;
  margin-top: 16px;
}

/* 连接状态徽标 = 8px 呼吸点 + 1.25rem 文字（§9.5.1）：状态色由 color 承载，
   圆点经 currentColor 同色，三态 ok/warn/secondary */
.link-panel-state {
  align-items: center;
  display: inline-flex;
  font-size: 1.25rem;
  gap: 8px;
}

.link-panel-state--connected {
  color: var(--fuy-screen-ok);
}

.link-panel-state--connecting {
  color: var(--fuy-screen-warn);
}

.link-panel-state--disconnected {
  color: var(--fuy-screen-text-secondary);
}

.link-panel-state-dot {
  /* 呼吸动画定义在全局 .fuy-breath（motion.css），本块只留点形态；opacity 1→.4 alternate
     1.2s linear 仅 opacity 单属性（§6 常驻动画唯一允许面：连接状态呼吸点） */
  background: currentColor;
  border-radius: var(--fuy-radius-full);
  display: inline-block;
  height: 8px;
  width: 8px;
}
</style>
