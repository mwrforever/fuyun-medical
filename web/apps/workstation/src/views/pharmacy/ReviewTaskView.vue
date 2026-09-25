<script setup lang="ts">
// 住院审方台页（/pharmacy/review，M06 住院用药审方薄切片工作台）：待审任务列表
// （医嘱号/患者摘要[号面脱敏口径]/药品明细/申请科室/频次，先到先审 FIFO 后端承载）+
// 通过/驳回操作（通过即出网；驳回弹窗意见必填——零出网显式校验前置，PH-1020 服务端兜底）。
// 通过/驳回均为法定留痕写操作（后端 WRITE 审计），回执事件驱动 M04 医嘱迁移；
// 操作后列表重拉刷新。全部写操作自带在途守卫（入口早退先于一切 await）；
// 失败弹错归响应拦截器（AxiosError 防双弹）。
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import { REVIEW_TASK_STATUS_OPTIONS, reviewTasks } from '@/api/pharmacy';
import type { ReviewTaskVO } from '@/api/pharmacy';

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文 */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/* ==================== 待审任务列表 ==================== */
const rows = ref<ReviewTaskVO[]>([]);
const listLoading = ref(false);
/** 状态过滤（默认待审态；空串=全部状态） */
const statusFilter = ref('PENDING');

/** 加载审方任务列表（先到先审 FIFO 排序由后端承载，前端按返回序直出） */
async function loadList(): Promise<void> {
  listLoading.value = true;
  try {
    const page = await reviewTasks.list({
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      page: 0,
      size: 50,
    });
    rows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    listLoading.value = false;
  }
}

/** 状态切换：重拉清单 */
function onFilterChange(): void {
  void loadList();
}

/* ==================== 审方通过 ==================== */
const approvingId = ref('');

/** 审方通过（PENDING→APPROVED）：回执 audit-completed 回流 M04 置医嘱可执行。
 * 入口在途早退守卫（逐行互斥）防双击重复决策。 */
async function onApprove(row: ReviewTaskVO): Promise<void> {
  if (approvingId.value !== '') {
    return;
  }
  approvingId.value = row.id ?? '';
  try {
    await reviewTasks.approve(row.id ?? '');
    void ElMessage.success(`已通过：医嘱 ${row.m04OrderNo ?? ''}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    approvingId.value = '';
  }
}

/* ==================== 审方驳回（意见必填弹窗） ==================== */
const rejectVisible = ref(false);
const rejecting = ref(false);
const rejectTarget = ref<ReviewTaskVO | null>(null);
const rejectOpinion = ref('');

/** 打开驳回弹窗（复位意见） */
function openReject(row: ReviewTaskVO): void {
  rejectTarget.value = row;
  rejectOpinion.value = '';
  rejectVisible.value = true;
}

/**
 * 确认驳回：意见必填显式校验（零出网；服务端 PH-1020 兜底同语义）→ 出网（回执
 * audit-rejected 驱动医生站修改重提）→ 关窗刷新列表。入口在途早退守卫防双击重复决策。
 */
async function onReject(): Promise<void> {
  if (rejecting.value) {
    return;
  }
  if (rejectOpinion.value.trim() === '') {
    void ElMessage.warning('请填写驳回意见（必填）');
    return;
  }
  rejecting.value = true;
  try {
    await reviewTasks.reject(rejectTarget.value?.id ?? '', {
      opinion: rejectOpinion.value.trim(),
    });
    void ElMessage.success(`已驳回：医嘱 ${rejectTarget.value?.m04OrderNo ?? ''}`);
    rejectVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    rejecting.value = false;
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page review-task fuy-stagger">
    <!-- 页头：标题 + 状态过滤 + 刷新 -->
    <header class="review-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="review-title">住院审方台</h2>
      <span class="review-hint">住院用药医嘱须经药师审方通过方可摆药（先到先审）</span>
      <select
        v-model="statusFilter"
        class="review-status-select"
        aria-label="任务状态"
        @change="onFilterChange"
      >
        <option value="">全部状态</option>
        <option v-for="item in REVIEW_TASK_STATUS_OPTIONS" :key="item.code" :value="item.code">
          {{ item.label }}
        </option>
      </select>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-row class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <el-col :span="24">
        <el-card>
          <template #header>
            <span>审方任务（共 {{ rows.length }} 条）</span>
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
              <el-table-column label="医嘱号" min-width="140">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.m04OrderNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="患者摘要（号面）" min-width="200">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.visitId }} / {{ row.patientId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="药品明细" min-width="200">
                <template #default="{ row }">
                  <span class="review-items" :title="row.items">{{ row.items ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="频次" width="60">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.freqCode ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="申请科室" width="90">
                <template #default="{ row }">
                  {{ row.applyDept ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa">{{
                    REVIEW_TASK_STATUS_OPTIONS.find((item) => item.code === row.status)?.label ??
                    row.status
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <template v-if="row.status === 'PENDING'">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :loading="approvingId === row.id"
                      :disabled="approvingId !== ''"
                      @click="onApprove(row)"
                      >通过</el-button
                    >
                    <el-button
                      link
                      type="danger"
                      size="small"
                      :disabled="approvingId !== '' || rejecting"
                      @click="openReject(row)"
                      >驳回</el-button
                    >
                  </template>
                  <span v-else class="review-decided">已处置</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无审方任务" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 驳回弹窗（意见必填） -->
    <el-dialog v-model="rejectVisible" title="审方驳回" width="420px">
      <p class="review-reject-target fuy-num">医嘱 {{ rejectTarget?.m04OrderNo ?? '' }}</p>
      <label class="review-field-label">驳回意见（必填，医生站按意见修改重提）</label>
      <textarea
        v-model="rejectOpinion"
        class="review-textarea"
        rows="3"
        maxlength="512"
        placeholder="填写驳回意见"
        aria-label="驳回意见"
      ></textarea>
      <template #footer>
        <el-button size="small" @click="rejectVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="rejecting"
          :disabled="rejecting"
          @click="onReject"
          >确认驳回</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 审方台布局：单列表格 + 状态过滤，token 取色禁自创色值 */
.review-toolbar {
  align-items: center;
}

.review-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.review-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 状态过滤（native select 与 EP 密度口径对齐） */
.review-status-select {
  margin-left: auto;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 药品明细列（超长省略，title 悬停全览） */
.review-items {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

/* 已处置行弱化提示 */
.review-decided {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 驳回弹窗 */
.review-reject-target {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.review-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.review-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
  resize: vertical;
}
</style>
