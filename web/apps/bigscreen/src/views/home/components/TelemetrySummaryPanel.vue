<script setup lang="ts">
// 遥测摘要面板（纯展示组件，web A.1-5 单向数据流）：渲染最近一帧 TelemetrySummary——
// 无 emits、无出网、无内部状态；occurredAtUpperBound 以 ISO-8601 原样展示（禁 new Date
// 换算丢失原始精度，统一时间格式化工具随 P1 交付）。
import type { TelemetrySummary } from '@/types/iot';

/** 组件 props（web A.1-3 泛型声明 + 3.5 响应式解构默认值口径） */
interface TelemetrySummaryPanelProps {
  /** 最近一帧遥测摘要；null=尚未收到帧，渲染占位文案 */
  summary: TelemetrySummary | null;
  /** 已接收帧计数（链路会话累计，面板头部展示） */
  frameCount: number;
}

const { summary, frameCount } = defineProps<TelemetrySummaryPanelProps>();
</script>

<template>
  <section class="telemetry-panel">
    <h2>
      最近一帧遥测摘要（累计接收 <span class="fuy-num">{{ frameCount }}</span> 帧）
    </h2>
    <p v-if="summary === null" class="telemetry-panel-empty">暂无遥测数据</p>
    <template v-else>
      <p class="telemetry-panel-meta">
        <span
          >本批条数：<span class="fuy-num">{{ summary.count }}</span></span
        >
        <span class="telemetry-panel-time">发生时刻上界：{{ summary.occurredAtUpperBound }}</span>
      </p>
      <table class="telemetry-panel-table">
        <thead>
          <tr>
            <th>设备号</th>
            <th>指标编码</th>
          </tr>
        </thead>
        <tbody>
          <!-- key 追加序号保唯一（同批同设备同指标理论上可重复出现） -->
          <tr
            v-for="(item, index) in summary.items"
            :key="`${item.deviceId}-${item.metricCode}-${index}`"
          >
            <td>{{ item.deviceId }}</td>
            <td>{{ item.metricCode }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：暗色面板（§9.5.1 遥测表暗色化）——容器 panel 底 +
   hairline 描边（复合简写变量整条消费）+ radius 8px，完整大屏版式随 P1 交付 */
.telemetry-panel {
  background: var(--fuy-screen-bg-panel);
  border: var(--fuy-screen-border-hairline);
  border-radius: var(--fuy-radius-lg);
  margin-top: 16px;
  padding: 16px;
}

.telemetry-panel-empty {
  color: var(--fuy-screen-text-secondary);
}

.telemetry-panel-meta {
  display: flex;
  gap: 24px;
}

.telemetry-panel-table {
  border-collapse: collapse;
}

/* 表头小字（secondary 两档灰）；单元格描边整条消费 hairline 复合变量 */
.telemetry-panel-table th {
  color: var(--fuy-screen-text-secondary);
  font-size: 0.875rem;
  font-weight: 600;
  text-align: left;
}

.telemetry-panel-table th,
.telemetry-panel-table td {
  border: var(--fuy-screen-border-hairline);
  padding: 4px 12px;
  text-align: left;
}

/* 偶数行斑马纹提升行辨识（§8.5 同款值——bigscreen 斑马纹唯一允许字面量） */
.telemetry-panel-table tbody tr:nth-child(even) {
  background: #0d2132;
}

/* 数字等宽防宽度跳动（§9.7-2）：bigscreen 无 EP 无全局工具类，SFC 内最小定义 */
.fuy-num {
  font-variant-numeric: tabular-nums;
}
</style>
