<script setup lang="ts">
// 护理不良事件上报页（/nursing/adverse-events，PR-3 Task 14，FU-M05-09 前端面）四件套：
// 列表（分页/类别/状态筛选——I/II 级 24h 上限时限与按时/超时留痕标记）+ 上报弹窗
// （类别/分级/等级/病区/时点/经过必填，匿名开关——非惩罚通道口径）+ 处理/RCA 关闭/
// 退回三操作（操作人留痕提示：弹窗文案明示以会话用户身份留痕）。列表分页与表单状态归
// useAdverseEvents（EX-47 拆分），视图只做组装；失败弹错归响应拦截器。
import { onMounted } from 'vue';
import 'element-plus/es/components/message/style/css';
import {
  ADVERSE_CATEGORY_OPTIONS,
  SEVERITY_CLASS_OPTIONS,
  SEVERITY_GRADE_OPTIONS,
  WARD_OPTIONS,
} from '@/api/nursing';
import type { AdverseEventVO } from '@/api/nursing';
import { useAuthStore } from '@/stores/auth';
import { formatTime } from '@/utils/timeFormat';
import { useAdverseEvents } from './composables/useAdverseEvents';

const auth = useAuthStore();

/** 不良事件状态面（操作人=会话用户 userId，处理/关闭/退回留痕锚点） */
const {
  rows,
  total,
  listLoading,
  currentPage,
  pageSize,
  categoryFilter,
  statusFilter,
  search,
  goToPage,
  reportVisible,
  reporting,
  reportForm,
  openReport,
  onReport,
  onHandle,
  onClose,
  onReturn,
} = useAdverseEvents({ getOperatorId: () => auth.user?.userId ?? '' });

/** 状态标签映射（REPORTED 待处理/HANDLING 处理中/CLOSED 已关闭终态） */
const STATUS_META: Record<string, { type: 'info' | 'warning' | 'success'; text: string }> = {
  REPORTED: { type: 'info', text: '待处理' },
  HANDLING: { type: 'warning', text: '处理中' },
  CLOSED: { type: 'success', text: '已关闭' },
};

/** 类别词表反查（列表展示中文类别名） */
function categoryLabel(code: string | undefined): string {
  return ADVERSE_CATEGORY_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/** 时限标记（I/II 级 24h 强制上报时限：超时只留痕不拒绝——非惩罚原则） */
function deadlineMeta(row: AdverseEventVO): { text: string; overdue: boolean } | null {
  if ((row.reportDeadline ?? '') === '') {
    return null;
  }
  return row.deadlineMet === true
    ? { text: '按时上报', overdue: false }
    : { text: '超时留痕', overdue: true };
}

/** 行操作可用性（状态机：REPORTED=处理/退回；HANDLING=关闭；CLOSED 终态无操作） */
function canHandle(row: AdverseEventVO): boolean {
  return row.status === 'REPORTED';
}

onMounted(() => {
  void search();
});
</script>

<template>
  <div class="fuy-page adverse-event fuy-stagger">
    <!-- 工具条：标题 + 类别/状态筛选 + 上报入口 -->
    <header class="ae-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="ae-title">护理不良事件上报</h2>
      <span class="ae-hint">非惩罚通道：主动报告免责，匿名可选</span>
      <select v-model="categoryFilter" class="ae-filter" aria-label="事件类别" @change="search">
        <option value="">全部类别</option>
        <option v-for="item in ADVERSE_CATEGORY_OPTIONS" :key="item.code" :value="item.code">
          {{ item.label }}
        </option>
      </select>
      <select v-model="statusFilter" class="ae-filter" aria-label="处理状态" @change="search">
        <option value="">全部状态</option>
        <option value="REPORTED">待处理</option>
        <option value="HANDLING">处理中</option>
        <option value="CLOSED">已关闭</option>
      </select>
      <!-- 上报事件（PR-4F #31，NURSE 绑定）v-perm 直挂；上报弹窗「提交上报」随入口
           免挂接（入口隐藏即弹窗不可达）；类别/状态筛选为读面过滤不挂码 -->
      <el-button v-perm="'nursing:adverse-event:btn:manage'" type="primary" @click="openReport"
        >上报事件</el-button
      >
    </header>

    <!-- 列表（分页；单号/类别/分级+等级/状态/时限标记/匿名标记/操作） -->
    <el-row class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <el-col :span="24">
        <el-card>
          <template #header>
            <span
              >事件清单（共 <span class="fuy-num">{{ total }}</span> 条）</span
            >
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
              <el-table-column label="事件号" min-width="140">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.eventNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类别" width="100">
                <template #default="{ row }">
                  {{ categoryLabel(row.category) }}
                </template>
              </el-table-column>
              <el-table-column label="分级/等级" width="90">
                <template #default="{ row }">
                  <span class="fuy-num"
                    >{{ row.severityClass ?? '—' }} / {{ row.severityGrade ?? '—' }}</span
                  >
                </template>
              </el-table-column>
              <el-table-column label="病区" width="70">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.wardId ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="发生时点" width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.occurredAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="经过" min-width="180">
                <template #default="{ row }">
                  <span class="ae-summary" :title="row.eventSummary">{{ row.eventSummary }}</span>
                </template>
              </el-table-column>
              <el-table-column label="时限" width="90">
                <template #default="{ row }">
                  <el-tag
                    v-if="deadlineMeta(row) !== null"
                    size="small"
                    :type="deadlineMeta(row)?.overdue ? 'danger' : 'success'"
                    class="fuy-tag-aa"
                    >{{ deadlineMeta(row)?.text }}</el-tag
                  >
                  <span v-else class="ae-no-deadline">—</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    :type="STATUS_META[row.status ?? '']?.type ?? 'info'"
                    class="fuy-tag-aa"
                  >
                    {{ STATUS_META[row.status ?? '']?.text ?? row.status }}
                  </el-tag>
                  <el-tag
                    v-if="row.isAnonymous === true"
                    size="small"
                    type="info"
                    class="fuy-tag-aa"
                    >匿名</el-tag
                  >
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <!-- 处理/退回/关闭三动作（PR-4F #31）与上报入口同码 v-perm 直挂——
                       不良事件全生命周期动作单码收口；状态机数据态（REPORTED/HANDLING/
                       CLOSED）与权限判定两层正交 -->
                  <template v-if="canHandle(row)">
                    <el-button
                      v-perm="'nursing:adverse-event:btn:manage'"
                      link
                      type="primary"
                      size="small"
                      @click="onHandle(row)"
                      >处理</el-button
                    >
                    <el-button
                      v-perm="'nursing:adverse-event:btn:manage'"
                      link
                      type="warning"
                      size="small"
                      @click="onReturn(row)"
                      >退回</el-button
                    >
                  </template>
                  <el-button
                    v-else-if="row.status === 'HANDLING'"
                    v-perm="'nursing:adverse-event:btn:manage'"
                    link
                    type="success"
                    size="small"
                    @click="onClose(row)"
                    >关闭</el-button
                  >
                  <span v-else class="ae-closed">已闭环</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无不良事件记录" />
            <div v-if="total > pageSize" class="ae-pagination">
              <el-pagination
                layout="prev, pager, next, total"
                :total="total"
                :page-size="pageSize"
                :current-page="currentPage"
                @current-change="goToPage"
              />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 上报弹窗（必填面：类别/分级/等级/病区/时点/经过；匿名开关） -->
    <el-dialog v-model="reportVisible" title="不良事件上报" width="560px" destroy-on-close>
      <el-form label-width="90px" size="small">
        <el-form-item label="事件类别" required>
          <el-select v-model="reportForm.category" placeholder="选择类别">
            <el-option
              v-for="item in ADVERSE_CATEGORY_OPTIONS"
              :key="item.code"
              :label="item.label"
              :value="item.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度分级" required>
          <el-select v-model="reportForm.severityClass" placeholder="I 最重 → IV 最轻">
            <el-option
              v-for="item in SEVERITY_CLASS_OPTIONS"
              :key="item.code"
              :label="item.label"
              :value="item.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度等级" required>
          <el-select v-model="reportForm.severityGrade" placeholder="A ~ E">
            <el-option
              v-for="item in SEVERITY_GRADE_OPTIONS"
              :key="item.code"
              :label="item.label"
              :value="item.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发生病区" required>
          <el-select v-model="reportForm.wardId">
            <el-option
              v-for="ward in WARD_OPTIONS"
              :key="ward.code"
              :label="ward.label"
              :value="ward.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发生时点" required>
          <input
            v-model="reportForm.occurredAt"
            type="datetime-local"
            class="ae-datetime"
            aria-label="发生时点"
          />
        </el-form-item>
        <el-form-item label="事件经过" required>
          <textarea
            v-model="reportForm.eventSummary"
            class="ae-textarea"
            rows="3"
            maxlength="1000"
            placeholder="客观描述事件经过（时间/地点/人物/过程/后果）"
            aria-label="事件经过"
          ></textarea>
        </el-form-item>
        <el-form-item label="处置记录">
          <textarea
            v-model="reportForm.handlingNote"
            class="ae-textarea"
            rows="2"
            maxlength="512"
            placeholder="现场即时处置（选填）"
            aria-label="处置记录"
          ></textarea>
        </el-form-item>
        <el-form-item label="就诊号">
          <el-input
            v-model="reportForm.visitId"
            placeholder="I 开头就诊号（选填）"
            class="ae-visit"
          />
        </el-form-item>
        <el-form-item label="匿名上报">
          <div class="ae-anonymous">
            <el-switch v-model="reportForm.isAnonymous" />
            <span class="ae-anonymous-hint">勾选后报告人身份对处理侧隐藏（非惩罚通道）</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="primary" :loading="reporting" :disabled="reporting" @click="onReport"
          >提交上报</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 工具条（token 取色禁自创色值；native select 与 EP 密度口径对齐——存量页同款） */
.ae-toolbar {
  align-items: center;
}
.ae-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}
.ae-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
.ae-filter {
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
}

/* 经过列（超长省略，title 悬停全览） */
.ae-summary {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.ae-no-deadline,
.ae-closed {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* 分页条（右对齐；首页不满页不渲染） */
.ae-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--fuy-space-3);
}

/* 上报表单（原生 datetime/textarea 与 EP 密度口径对齐） */
.ae-datetime {
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
}
.ae-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  resize: vertical;
}
.ae-visit {
  width: 220px;
}
.ae-anonymous {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ae-anonymous-hint {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
</style>
