<script setup lang="ts">
// 护理执行工作台页（/nursing/execution，PR-3 Task 14，FU-M05-04 前端面）三段布局：
// ①顶部病区/班次/日期筛选（GET /executions 支撑）②分组看板四列（待签收/待核对/待执行/
// 执行中，行卡=患者床号/医嘱摘要/计划时间/升级标记；LONG 锚行以类型标签区分且不进
// 操作流——Task 4 裁决锚行 m04PlanNo 为空）③右侧详情抽屉（单执行单闭环时间线+五动作
// 按钮组 sign-receive/check/start/finish/cancel 在途互斥+扫码核对输入框回车提交[PDA
// 同款正则]+输液遥测条[护理侧在途行按 patientId × ward 侧设备遥测前端组合，组合降级
// 注记 UI 内不体现]）。执行单号文本+复制按钮（打印降级注记：打印归后续批次，复制回执
// 号面已覆盖床旁对单需求）。业务状态面归 useExecutions（EX-47 拆分），视图只做组装；
// 失败弹错归响应拦截器（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import {
  CHECK_TYPE_OPTIONS,
  EXECUTION_TYPE_LABELS,
  SHIFT_OPTIONS,
  WARD_OPTIONS,
} from '@/api/nursing';
import type { OrderExecutionVO } from '@/api/nursing';
import { INFUSION_ALERT_LABELS } from '@/api/ward';
import { useAuthStore } from '@/stores/auth';
import { formatTime } from '@/utils/timeFormat';
import { isAnchorRow, useExecutions } from './composables/useExecutions';

const auth = useAuthStore();

/** 当前病区（会话内记忆：切换/刷新不回默认病区，口径同护士站） */
const wardId = ref(sessionStorage.getItem('nursing.exec.wardId') ?? WARD_OPTIONS[0].code);

/** 执行工作台状态面（执行人=会话用户 userId，start/finish 留痕锚点） */
const state = useExecutions({
  wardId,
  getExecutorId: () => auth.user?.userId ?? '',
});
const {
  filterDate,
  filterShift,
  columns,
  boardLoading,
  loadBoard,
  drawerVisible,
  selected,
  trace,
  traceLoading,
  openDetail,
  infusionStrip,
  acting,
  availableActions,
  onSignReceive,
  onScanCheck,
  onStart,
  onFinish,
  onCancelExecution,
  scanCode,
  scanType,
} = state;

/** 病区切换：会话记忆 + 看板重拉 */
function onWardChange(): void {
  sessionStorage.setItem('nursing.exec.wardId', wardId.value);
  void loadBoard();
}

/** 行卡类型标签（Task 4 锚行区分：LONG=类型锚行 / STAT=执行快照） */
function rowTypeLabel(row: OrderExecutionVO): string {
  return isAnchorRow(row) ? '类型锚行' : '执行快照';
}

/** 行卡标记（升级标记：越权核对/关联告警；输液类型标记监测挂接） */
function rowBadges(row: OrderExecutionVO): Array<{ cls: string; text: string }> {
  const badges: Array<{ cls: string; text: string }> = [
    { cls: 'exec-badge--type', text: EXECUTION_TYPE_LABELS[row.executionType ?? ''] ?? '' },
  ];
  if (row.overrideFlag === true) {
    badges.push({ cls: 'exec-badge--override', text: '越权' });
  }
  if ((row.latestAlarmNo ?? '') !== '') {
    badges.push({ cls: 'exec-badge--alarm', text: '告警' });
  }
  return badges.filter((badge) => badge.text !== '');
}

/** 闭环时间线（五环节时点 + 拔针；未到环节灰显占位——闭环进度一眼可读） */
const timeline = computed(() => {
  const source = trace.value ?? selected.value;
  if (source === null) {
    return [];
  }
  return [
    { label: '计划', at: source.planTime },
    { label: '签收', at: source.signedAt },
    { label: '核对', at: source.checkedAt },
    { label: '开始', at: source.startedAt },
    { label: '完成', at: source.finishedAt },
    { label: '拔针', at: source.needleOutAt },
  ];
});

/** 核对流水（trace 面核对明细；checkResult PASS/FAIL 双落留痕） */
const checkLogs = computed(() => trace.value?.checkLogs ?? []);

/** 当前抽屉行的可用动作集（锚行恒空集） */
const actions = computed(() => (selected.value === null ? [] : availableActions(selected.value)));

/** 复制执行单号（回执号面；打印降级注记——打印归后续批次，复制已覆盖床旁对单需求） */
async function onCopyNo(): Promise<void> {
  const no = selected.value?.executionNo ?? '';
  if (no === '') {
    return;
  }
  try {
    await navigator.clipboard.writeText(no);
    void ElMessage.success('执行单号已复制');
  } catch {
    void ElMessage.warning('复制失败（浏览器未授权剪贴板），请手动抄录执行单号');
  }
}

onMounted(() => {
  void loadBoard();
});
</script>

<template>
  <div class="fuy-page exec-workbench fuy-stagger">
    <!-- ① 顶部筛选：病区 / 班次 / 日期 -->
    <header class="exec-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="exec-title">护理执行工作台</h2>
      <span class="exec-hint">医嘱执行单五环节闭环（签收-核对-执行-完成-追溯）</span>
      <el-select v-model="wardId" class="exec-ward" @change="onWardChange">
        <el-option
          v-for="ward in WARD_OPTIONS"
          :key="ward.code"
          :label="ward.label"
          :value="ward.code"
        />
      </el-select>
      <el-select
        v-model="filterShift"
        class="exec-shift"
        placeholder="全部班次"
        clearable
        @change="loadBoard"
      >
        <el-option
          v-for="shift in SHIFT_OPTIONS"
          :key="shift.code"
          :label="shift.label"
          :value="shift.code"
        />
      </el-select>
      <input
        v-model="filterDate"
        type="date"
        class="exec-date"
        aria-label="计划日期"
        @change="loadBoard"
      />
      <el-button type="primary" :loading="boardLoading" @click="loadBoard">查询</el-button>
    </header>

    <!-- ② 分组看板四列（终态行不进列；列内按计划时间升序=后端返回序） -->
    <div
      v-loading="boardLoading"
      class="exec-board fuy-stagger"
      :style="{ '--fuy-stagger-index': 1 }"
    >
      <section
        v-for="column in columns"
        :key="column.status"
        class="exec-column"
        :aria-label="column.label"
      >
        <header class="exec-column-head">
          <span class="exec-column-title">{{ column.label }}</span>
          <span class="fuy-num exec-column-count">{{ column.rows.length }}</span>
        </header>
        <div class="exec-column-body">
          <button
            v-for="row in column.rows"
            :key="row.executionNo"
            type="button"
            class="exec-card"
            :class="{ 'is-anchor': isAnchorRow(row) }"
            @click="openDetail(row)"
          >
            <div class="exec-card-head">
              <span class="fuy-num exec-card-bed">{{ row.bedNo ?? '—' }}</span>
              <span class="fuy-num exec-card-plan">{{ formatTime(row.planTime) }}</span>
            </div>
            <div
              class="exec-card-item"
              :title="`${row.execItemName ?? ''} ${row.dosageText ?? ''}`"
            >
              {{ row.execItemName ?? '—' }}
              <span v-if="(row.dosageText ?? '') !== ''" class="exec-card-dosage fuy-num">{{
                row.dosageText
              }}</span>
            </div>
            <div class="exec-card-badges">
              <span
                v-for="badge in rowBadges(row)"
                :key="badge.text"
                class="exec-badge"
                :class="badge.cls"
                >{{ badge.text }}</span
              >
              <span
                class="exec-badge exec-badge--rowtype"
                :class="{ 'is-anchor': isAnchorRow(row) }"
              >
                {{ rowTypeLabel(row) }}
              </span>
            </div>
          </button>
          <p v-if="column.rows.length === 0" class="exec-column-empty">—</p>
        </div>
      </section>
      <el-empty
        v-if="columns.every((column) => column.rows.length === 0)"
        :image-size="72"
        description="暂无执行单"
        class="exec-board-empty"
      />
    </div>

    <!-- ③ 详情抽屉：单执行单闭环追溯 + 动作组 + 扫码核对 + 输液遥测条 -->
    <el-drawer v-model="drawerVisible" title="执行单详情" size="380px" class="exec-drawer">
      <template v-if="selected !== null">
        <!-- 执行单号 + 复制按钮（打印降级注记：复制已覆盖对单需求） -->
        <div class="exec-drawer-no-row">
          <span class="fuy-num exec-drawer-no">{{ selected.executionNo }}</span>
          <el-button link type="primary" size="small" @click="onCopyNo">复制单号</el-button>
        </div>
        <div class="exec-drawer-meta">
          <span class="fuy-num">{{ selected.bedNo ?? '—' }} 床</span>
          <span class="fuy-num">{{ selected.visitId }}</span>
          <span
            class="exec-badge exec-badge--rowtype"
            :class="{ 'is-anchor': isAnchorRow(selected) }"
          >
            {{ rowTypeLabel(selected) }}
          </span>
        </div>

        <!-- 输液遥测条（前端组合面：护理侧在途行 × ward 侧设备遥测；无在途输注不渲染） -->
        <div v-if="infusionStrip !== null" class="exec-infusion-strip">
          <span class="exec-infusion-title">输注监测</span>
          <span class="fuy-num">
            余量 {{ infusionStrip.telemetry?.remainLatest ?? '—' }}ml / 滴速
            {{ infusionStrip.telemetry?.dropRateLatest ?? '—' }}
          </span>
          <span
            v-if="infusionStrip.telemetry?.alertLevel !== undefined"
            class="exec-infusion-alert"
            :class="`is-${(infusionStrip.telemetry.alertLevel ?? 'none').toLowerCase()}`"
          >
            {{ INFUSION_ALERT_LABELS[infusionStrip.telemetry.alertLevel] ?? '' }}
          </span>
          <span
            v-if="(infusionStrip.infusion.escalationCount ?? 0) > 0"
            class="exec-badge exec-badge--alarm"
            >升级 {{ infusionStrip.infusion.escalationCount }} 次</span
          >
        </div>

        <!-- 闭环时间线（五环节+拔针；未到环节灰显） -->
        <h4 class="fuy-section-title">闭环时间线</h4>
        <div v-loading="traceLoading" class="exec-timeline">
          <div
            v-for="node in timeline"
            :key="node.label"
            class="exec-timeline-node"
            :class="{ 'is-done': (node.at ?? '') !== '' }"
          >
            <i class="exec-timeline-dot" />
            <span class="exec-timeline-label">{{ node.label }}</span>
            <span class="fuy-num exec-timeline-at">{{ node.at ? formatTime(node.at) : '—' }}</span>
          </div>
        </div>

        <!-- 核对流水（PASS/FAIL 双落留痕） -->
        <template v-if="checkLogs.length > 0">
          <h4 class="fuy-section-title">核对流水</h4>
          <div class="exec-checklogs">
            <p v-for="(log, index) in checkLogs" :key="index" class="exec-checklog fuy-num">
              {{ formatTime(log.occurredAt) }}
              {{
                log.checkType === 'WRISTBAND'
                  ? '腕带'
                  : log.checkType === 'BAG_LABEL'
                    ? '瓶签'
                    : '执行单'
              }}
              {{ log.checkResult === 'PASS' ? '核对通过' : '核对不符' }}
            </p>
          </div>
        </template>

        <!-- 扫码核对（三向单维；输入框回车提交=扫码枪形态，PDA 同款正则校验） -->
        <h4 class="fuy-section-title">扫码核对</h4>
        <div class="exec-scan">
          <select v-model="scanType" class="exec-scan-type" aria-label="核对方式">
            <option v-for="item in CHECK_TYPE_OPTIONS" :key="item.code" :value="item.code">
              {{ item.label }}
            </option>
          </select>
          <input
            v-model="scanCode"
            class="exec-scan-input"
            type="text"
            autocomplete="off"
            placeholder="扫码后回车提交"
            :disabled="actions.length === 0"
            @keyup.enter="onScanCheck"
          />
        </div>

        <!-- 操作按钮组（五动作在途互斥；锚行不进操作流）；五动作（PR-4F #29，NURSE 绑定）
             同码 v-perm 直挂——无码 DOM 移除（D-34），:disabled 在途数据态正交叠加；
             扫码核对输入框回车通道（附件 A 元素列未列）不挂码，越权出网由后端端点绑定拦截 -->
        <div v-if="actions.length > 0" class="exec-drawer-actions">
          <el-button
            v-if="actions.includes('signReceive')"
            v-perm="'nursing:execution:btn:perform'"
            type="primary"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onSignReceive"
            >补签收</el-button
          >
          <el-button
            v-if="actions.includes('check')"
            v-perm="'nursing:execution:btn:perform'"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onScanCheck"
            >核对</el-button
          >
          <el-button
            v-if="actions.includes('start')"
            v-perm="'nursing:execution:btn:perform'"
            type="primary"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onStart"
            >开始执行</el-button
          >
          <el-button
            v-if="actions.includes('finish')"
            v-perm="'nursing:execution:btn:perform'"
            type="success"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onFinish"
            >完成执行</el-button
          >
          <el-button
            v-if="actions.includes('cancel')"
            v-perm="'nursing:execution:btn:perform'"
            link
            type="danger"
            size="small"
            :disabled="acting"
            @click="onCancelExecution"
            >撤销</el-button
          >
        </div>
        <p v-else class="exec-anchor-hint">类型锚行不进操作流（STAT 快照行才是执行载体）</p>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
/* ① 筛选条 */
.exec-toolbar {
  align-items: center;
}
.exec-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}
.exec-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.exec-ward {
  width: 150px;
}
.exec-shift {
  width: 110px;
}
.exec-date {
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
}

/* ② 四列看板（等宽 grid；min-height CLS 锁） */
.exec-board {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--fuy-space-3);
  min-height: 320px;
}
.exec-column {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-palette-gray-50);
}
.exec-column-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-bottom: var(--fuy-border-hairline);
}
.exec-column-title {
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}
.exec-column-count {
  min-width: 18px;
  padding: 0 4px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-color-primary-light-8);
  color: var(--fuy-color-brand);
  font-size: var(--fuy-font-size-xs);
  font-weight: 700;
  text-align: center;
}
.exec-column-body {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  padding: var(--fuy-space-2);
}
.exec-column-empty {
  margin: 0;
  text-align: center;
  color: var(--fuy-color-text-secondary);
}
/* 行卡（1px 描边白底；锚行虚线描边降区分） */
.exec-card {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-lg);
  background: var(--el-bg-color);
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.exec-card:hover {
  border-color: var(--fuy-color-brand);
  background: var(--fuy-palette-brand-100);
}
.exec-card.is-anchor {
  border-style: dashed;
}
.exec-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.exec-card-bed {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
.exec-card-plan {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.exec-card-item {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fuy-font-size-sm);
}
.exec-card-dosage {
  margin-left: 4px;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.exec-card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
/* 标记（类型/越权/告警，token 取色禁自创色值） */
.exec-badge {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--fuy-radius-full);
  font-size: var(--fuy-font-size-xs);
  line-height: 1;
}
.exec-badge--type {
  background: var(--el-color-primary-light-8);
  color: var(--fuy-color-brand);
}
.exec-badge--rowtype {
  background: var(--el-fill-color-light);
  color: var(--fuy-color-text-secondary);
}
.exec-badge--rowtype.is-anchor {
  background: var(--el-color-info-light-8);
  color: var(--el-text-color-secondary);
}
.exec-badge--override {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.exec-badge--alarm {
  background: var(--el-color-danger-light-9);
  color: var(--fuy-color-danger-text);
}
.exec-board-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ③ 抽屉 */
.exec-drawer-no-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.exec-drawer-no {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
.exec-drawer-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin: var(--fuy-space-2) 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
/* 输液遥测条（组合面；三档着色语义 token） */
.exec-infusion-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
}
.exec-infusion-title {
  font-weight: 600;
}
.exec-infusion-alert {
  padding: 0 6px;
  border-radius: var(--fuy-radius-full);
  font-size: var(--fuy-font-size-xs);
}
.exec-infusion-alert.is-yellow {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.exec-infusion-alert.is-orange {
  background: var(--el-color-warning-light-8);
  color: var(--el-color-warning-dark-2);
}
.exec-infusion-alert.is-red {
  background: var(--el-color-danger-light-9);
  color: var(--fuy-color-danger-text);
}
/* 时间线（左缘连线；已到环节实心点+深色，未到灰显） */
.exec-timeline {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  min-height: 120px;
  padding-left: var(--fuy-space-2);
}
.exec-timeline::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 6px;
  bottom: 6px;
  width: 1px;
  background: var(--el-border-color);
}
.exec-timeline-node {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: var(--fuy-space-2);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.exec-timeline-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-border-color);
}
.exec-timeline-node.is-done {
  color: var(--fuy-color-text-emphasis);
}
.exec-timeline-node.is-done .exec-timeline-dot {
  background: var(--fuy-color-brand);
}
.exec-timeline-label {
  min-width: 32px;
}
.exec-timeline-at {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.exec-checklogs {
  margin-bottom: var(--fuy-space-3);
}
.exec-checklog {
  margin: 0 0 var(--fuy-space-1);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
/* 扫码核对（核对方式 select + 原文输入，Enter 提交） */
.exec-scan {
  display: flex;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
}
.exec-scan-type {
  width: 88px;
  flex: none;
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
}
.exec-scan-input {
  flex: 1;
  min-width: 0;
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
}
.exec-scan-input:focus-visible {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 0;
}
.exec-drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
}
.exec-anchor-hint {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
</style>
