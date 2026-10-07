<script setup lang="ts">
// 呼叫工作台（/ward/call-workbench，M16 病区呼叫闭环面前端面）：待应答列表（默认 status=
// CREATED，状态/病区可切换筛选；呼叫类型/来源/床位/升级标记列）+ 闭环操作（应答/处理/完成
// [resultSummary 强制弹窗]/转接/取消——状态机流转规则单点归后端把守，前端按行状态暴露入口，
// 非法流转由后端拒绝并弹错）。升级标记：escalationCount>0 渲染升级徽标（呼叫超时自动升级
// 由后端承载）。声音提示本批次未接入（静默提示面，后续任务补齐——页面注记留痕）。
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import {
  CALL_SOURCE_LABELS,
  CALL_STATUS_LABELS,
  CALL_TYPE_LABELS,
  WARD_OPTIONS,
  wardCalls,
} from '@/api/ward';
import type { WardCallVO } from '@/api/ward';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

/** 呼叫状态中文词表反查（状态列徽标） */
function statusLabel(code: string | undefined): string {
  return CALL_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 呼叫状态徽标状态类（fuy-call-tag--{status} 契约类，色值经语义 token 承载） */
function statusClass(code: string | undefined): string {
  return `fuy-call-tag--${(code ?? '').toLowerCase()}`;
}

/** 呼叫类型中文词表反查（类型列） */
function callTypeLabel(code: string | undefined): string {
  return CALL_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 呼叫来源中文词表反查（来源列） */
function sourceLabel(code: string | undefined): string {
  return CALL_SOURCE_LABELS[code ?? ''] ?? code ?? '—';
}

/* ==================== 呼叫列表 ==================== */
/** 病区筛选（演示病区种子缺省） */
const wardFilter = ref(WARD_OPTIONS[0].code);
/** 状态筛选（默认待应答——工作台主战场；空串=全部六态） */
const statusFilter = ref('CREATED');

/** 加载呼叫列表（wardId/status 过滤由后端承载，前端按返回序直出）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错
 * 归响应拦截器；驻留旧清单） */
const {
  rows,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({
    wardId: wardFilter.value === '' ? undefined : wardFilter.value,
    status: statusFilter.value === '' ? undefined : (statusFilter.value as WardCallVO['status']),
  }),
  fetcher: ({ wardId, status, page, size }) => wardCalls.page({ wardId, status, page, size }),
  pageSize: 50,
});

/* ==================== 闭环操作（在途守卫逐行锚定 callNo） ==================== */

/** 操作行在途登记（同一行防重复提交；跨行互不影响） */
const pendingCallNos = ref<Set<string>>(new Set());

/** 行级在途守卫与登记（返回 true=已在途早退；false=已登记放行） */
function guardPending(callNo: string): boolean {
  if (pendingCallNos.value.has(callNo)) {
    return true;
  }
  pendingCallNos.value.add(callNo);
  return false;
}

/** 释放行级在途登记（finally 统一出口） */
function releasePending(callNo: string): void {
  pendingCallNos.value.delete(callNo);
}

/** 应答（CREATED→ANSWERED；应答人取会话由后端承载） */
async function onAnswer(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.answer(row.callNo);
    void ElMessage.success(`已应答：${row.callNo}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/** 处理（ANSWERED→IN_PROGRESS，开始到场处置） */
async function onProgress(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.progress(row.callNo);
    void ElMessage.success(`已开始处理：${row.callNo}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/** 转接（转接目标链路归后端路由引擎；转接后状态置 TRANSFERRED） */
async function onTransfer(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.transfer(row.callNo);
    void ElMessage.success(`已转接：${row.callNo}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/** 取消（非终态可取消，终态拒绝归后端把守） */
async function onCancel(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.cancel(row.callNo);
    void ElMessage.success(`已取消：${row.callNo}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/* ==================== 完成弹窗（resultSummary 强制） ==================== */
const completeVisible = ref(false);
const completing = ref(false);
/** 完成目标呼叫（弹窗上下文锚点） */
const completeTarget = ref<WardCallVO | null>(null);
const resultSummary = ref('');

/** 打开完成弹窗（仅 IN_PROGRESS 态行暴露入口） */
function openComplete(row: WardCallVO): void {
  completeTarget.value = row;
  resultSummary.value = '';
  completeVisible.value = true;
}

/** 确认完成：摘要强制显式校验（空摘要零出网）→ 出网 → 关窗刷新。 */
async function onComplete(): Promise<void> {
  if (completing.value) {
    return;
  }
  if (resultSummary.value.trim() === '') {
    void ElMessage.warning('请填写处置结果');
    return;
  }
  completing.value = true;
  try {
    await wardCalls.complete(completeTarget.value?.callNo ?? '', {
      resultSummary: resultSummary.value.trim(),
    });
    void ElMessage.success(`已完成：${completeTarget.value?.callNo ?? ''}`);
    completeVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    completing.value = false;
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page call-workbench fuy-stagger">
    <!-- 页头：标题 + 声音提示缺位注记 + 刷新 -->
    <header class="call-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="call-title">病区呼叫工作台</h2>
      <span class="call-hint">
        闭环操作按行状态暴露 · 流转规则归后端把守 · 声音提示本批次未接入（静默提示面，后续补齐）
      </span>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-card class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <template #header>
        <div class="call-card-head">
          <span>呼叫列表（共 {{ rows.length }} 条）</span>
          <div class="call-filters">
            <select
              v-model="wardFilter"
              class="call-filter-select"
              aria-label="病区筛选"
              @change="loadList"
            >
              <option value="">全部病区</option>
              <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
                {{ ward.label }}
              </option>
            </select>
            <select
              v-model="statusFilter"
              class="call-filter-select"
              aria-label="状态筛选"
              @change="loadList"
            >
              <option value="">全部状态</option>
              <option v-for="(label, code) in CALL_STATUS_LABELS" :key="code" :value="code">
                {{ label }}
              </option>
            </select>
          </div>
        </div>
      </template>
      <div v-loading="listLoading">
        <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
          <el-table-column label="呼叫号" min-width="130">
            <template #default="{ row }">
              <span class="fuy-num">{{ row.callNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="床位" width="64">
            <template #default="{ row }">
              <span class="fuy-num">{{ row.bedId ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="患者号" min-width="150">
            <template #default="{ row }">
              <span class="fuy-num">{{ row.patientId ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="88">
            <template #default="{ row }">{{ callTypeLabel(row.callType) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="88">
            <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" class="fuy-tag-aa" :class="statusClass(row.status)">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="升级标记" width="88">
            <template #default="{ row }">
              <el-tag
                v-if="(row.escalationCount ?? 0) > 0"
                size="small"
                class="fuy-tag-aa fuy-call-escalation"
              >
                已升级 {{ row.escalationCount }} 次
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="呼叫时间" width="96">
            <template #default="{ row }">
              <span class="fuy-num">{{ formatTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="处置人" width="76">
            <template #default="{ row }">
              <span class="fuy-num">{{ row.processedBy ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="196" class-name="fuy-ops-8">
            <template #default="{ row }">
              <!-- 呼叫闭环五动作（PR-4F #39，NURSE 绑定）v-perm 同码直挂——应答/处理/
                   完成/转接/取消全生命周期单码收口，无码 DOM 移除（D-34）；CREATED/
                   ANSWERED/IN_PROGRESS 状态机 v-if 数据态与权限判定两层正交；完成弹窗
                   「确认完成」随入口不可达免挂接 -->
              <el-button
                v-if="row.status === 'CREATED'"
                v-perm="'ward:call:btn:handle'"
                link
                type="primary"
                size="small"
                @click="onAnswer(row)"
                >应答</el-button
              >
              <el-button
                v-if="row.status === 'ANSWERED'"
                v-perm="'ward:call:btn:handle'"
                link
                type="primary"
                size="small"
                @click="onProgress(row)"
                >处理</el-button
              >
              <el-button
                v-if="row.status === 'IN_PROGRESS'"
                v-perm="'ward:call:btn:handle'"
                link
                type="success"
                size="small"
                @click="openComplete(row)"
                >完成</el-button
              >
              <el-button
                v-if="
                  row.status === 'CREATED' ||
                  row.status === 'ANSWERED' ||
                  row.status === 'IN_PROGRESS'
                "
                v-perm="'ward:call:btn:handle'"
                link
                type="warning"
                size="small"
                @click="onTransfer(row)"
                >转接</el-button
              >
              <el-button
                v-if="row.status !== 'COMPLETED' && row.status !== 'CANCELLED'"
                v-perm="'ward:call:btn:handle'"
                link
                type="danger"
                size="small"
                @click="onCancel(row)"
                >取消</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="72" description="暂无呼叫记录" />
      </div>
    </el-card>

    <!-- 完成弹窗（resultSummary 处置结果强制留痕） -->
    <el-dialog v-model="completeVisible" title="完成呼叫" width="420px">
      <p class="call-complete-target fuy-num">
        {{ completeTarget?.callNo ?? '' }} · {{ completeTarget?.bedId ?? '' }} 床
      </p>
      <label class="call-field-label">处置结果（强制：处置方式与结果留痕）</label>
      <textarea
        v-model="resultSummary"
        class="call-input"
        rows="2"
        placeholder="如：已到场更换输液，患者无不适"
        aria-label="处置结果"
      ></textarea>
      <template #footer>
        <el-button size="small" @click="completeVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="completing"
          :disabled="completing"
          @click="onComplete"
          >确认完成</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 呼叫工作台单卡布局，token 取色禁自创色值 */
.call-toolbar {
  align-items: center;
}

.call-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.call-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.call-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

.call-filters {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

.call-filter-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 完成弹窗基元 */
.call-complete-target {
  margin: 0 0 var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.call-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.call-input {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 呼叫状态六态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-call-tag--created {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

.fuy-call-tag--answered {
  border-color: var(--fuy-color-brand);
  color: var(--fuy-color-brand);
}

.fuy-call-tag--in_progress {
  border-color: var(--fuy-color-state-passed);
  color: var(--fuy-color-state-passed);
}

.fuy-call-tag--completed {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-call-tag--transferred {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-call-tag--cancelled {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

/* 升级标记徽标（超时自动升级警示语义） */
.fuy-call-escalation {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}
</style>
