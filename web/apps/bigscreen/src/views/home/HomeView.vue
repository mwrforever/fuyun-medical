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

    <!-- 链路状态区：连接状态徽标 + 当前订阅主题路径 + 已接收帧计数 -->
    <section class="link-panel">
      <span class="link-panel-state" :class="`link-panel-state--${connectionState}`">
        {{ stateLabel }}
      </span>
      <span>订阅主题：{{ topicPath ?? '未订阅' }}</span>
      <span>已接收帧数：{{ frameCount }}</span>
    </section>

    <!-- 遥测摘要区：最近一帧覆盖渲染 -->
    <TelemetrySummaryPanel :summary="latestSummary" :frame-count="frameCount" />
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：最小可读样式，完整大屏版式（图表/网格）随 P1 交付 */
.home-view {
  padding: 16px;
}

.home-view-subtitle {
  color: #909399;
}

.connect-panel {
  margin-top: 16px;
}

.connect-panel-field {
  display: block;
  margin-top: 8px;
}

.connect-panel-field span {
  display: inline-block;
  width: 72px;
}

.connect-panel-field input {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 4px 8px;
  width: 280px;
}

.connect-panel-actions {
  margin-top: 8px;
}

.connect-panel-hint {
  color: #e6a23c;
  margin-top: 8px;
}

.link-panel {
  align-items: center;
  display: flex;
  gap: 24px;
  margin-top: 16px;
}

.link-panel-state {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 2px 10px;
}

.link-panel-state--connected {
  border-color: #67c23a;
  color: #67c23a;
}

.link-panel-state--connecting {
  border-color: #e6a23c;
  color: #e6a23c;
}

.link-panel-state--disconnected {
  border-color: #909399;
  color: #909399;
}
</style>
