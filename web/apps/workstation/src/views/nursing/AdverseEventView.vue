<script setup lang="ts">
// 护理不良事件上报页（/nursing/adverse-events，FU-M05-09 前端面 · 暖纸卷宗换脸重排 甲-3，
// 「换脸不换业务」：上报表单校验/匿名语义/处理退回关闭状态机零变动）四段纵向序：
// 门牌页首（衬线标题 + 非惩罚口径批注行——原 hint 升批注 + 「待处理 N」琥珀胶囊）→
// 今日概览统计带（三格=总报数/按时上报数/待处理数，HomeView stat-strip 同构竖发丝非卡片；
// 取自未消费统计端点 getAdverseEventStats 当日口径 + 待处理探针，单次出网手动刷新）→
// 事件清单卡（.fuy-dense 密排分页表 + 类别/状态筛选 + 上报入口）→ 上报道具弹窗
// （560px .fuy-dialog + .fuy-form，病区下拉 listOrgs({type:'WARD'}) 真数据三态）→ 页脚
// 统计时点批注。成功反馈=钤印（已上报/已处理/已关闭/已退回，匿名口径不带操作人——留痕归
// 后端）；失败弹错归响应拦截器。列表分页与表单状态归 useAdverseEvents（EX-47 拆分），视图只做组装。
import { computed, onMounted } from 'vue';
import 'element-plus/es/components/message/style/css';
import {
  ADVERSE_CATEGORY_OPTIONS,
  SEVERITY_CLASS_OPTIONS,
  SEVERITY_GRADE_OPTIONS,
} from '@/api/nursing';
import type { AdverseEventVO } from '@/api/nursing';
import { useAuthStore } from '@/stores/auth';
import { formatTime } from '@/utils/timeFormat';
import { formatCount } from '@/utils/workbenchDisplay';
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
  stampText,
  stampVisible,
  stampSeq,
  stats,
  statsLoading,
  statsError,
  refreshStats,
  pendingTotal,
  pendingLoading,
  refreshPending,
  wardOptions,
  wardLoading,
  wardError,
  wardDisabled,
  loadWards,
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

/** 时限标记（I/II 级 24h 强制上报时限：超时只留痕不拒绝——非惩罚原则；琥珀=警示留痕非危急，
 * 按时=确认绿，取值经 el-tag warning/success 映射层走 --fuy-* 语义色，禁印泥朱） */
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

/* ---------- 门牌页首批注行：签认人（会话身份） · 时刻（本地时钟零出网） ---------- */
/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ---------- 今日概览统计带（管理层扫读面） ---------- */
/** 统计带三格（三字段映射）：total→总报数 / deadlineMetCount→按时上报数（副注=deadlineTotal
 * 时限分母）/ 待处理探针 total→待处理数；计数 string 承载，空值 — 占位零伪数据 */
const pendingLabel = computed(() => formatCount(pendingTotal.value ?? undefined));

/** 统计日批注（页脚「统计时点」=后端统计日北京钟面真值，空值 — 占位） */
const statsDateLabel = computed(() => stats.value?.date ?? '—');

/** 统计面刷新（手动刷新取数：统计带 + 待处理探针一并重取，单次出网不轮询） */
function refreshOverview(): void {
  void refreshStats();
  void refreshPending();
}

/** 刷新/重试钮在途态（统计与探针任一在途即亮，防重复击发零出网双发） */
const overviewLoading = computed(() => statsLoading.value || pendingLoading.value);

onMounted(() => {
  void search();
  refreshOverview();
  void loadWards();
});
</script>

<template>
  <div class="fuy-page adverse-event">
    <!-- 页内级联容器 ae-flow（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块；
         护士站 ward-board-flow 同构）：四段级联序 header 0 / 统计带 1 / 清单 2 / 页脚 3 -->
    <div class="fuy-stagger ae-flow">
      <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 状态位（待处理琥珀胶囊 + 刷新统计钮） +
         非惩罚口径批注行（原 hint 升批注——「谁·何时」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">不良事件上报</h1>
          <span class="fuy-page-en" aria-hidden="true">ADVERSE EVENTS</span>
        </div>
        <div class="fuy-page-status">
          <span class="fuy-status-pill fuy-status-pill--amber" role="status"
            >待处理 {{ pendingLabel }}</span
          >
          <el-button
            link
            type="primary"
            size="small"
            :loading="overviewLoading"
            :disabled="overviewLoading"
            @click="refreshOverview"
            >刷新统计</el-button
          >
        </div>
        <p class="fuy-page-note">
          非惩罚通道：主动报告免责 · 匿名可选 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- 今日概览统计带（蓝图 甲-3.2 PM 重排裁决：管理层扫读面；三态=骨架/内容/错误） -->
      <Transition name="fuy-content-fade" mode="out-in">
        <section
          v-if="stats !== null"
          key="stat-content"
          class="ae-stat-strip"
          :style="{ '--fuy-stagger-index': 1 }"
          aria-label="今日不良事件概览"
        >
          <div class="stat">
            <span class="stat-label">总报数</span>
            <span class="stat-value fuy-num">{{ formatCount(stats.total) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">按时上报数</span>
            <span class="stat-value fuy-num">{{ formatCount(stats.deadlineMetCount) }}</span>
            <span v-if="stats.deadlineTotal !== undefined" class="stat-delta">
              I/II 级 24h 时限共 {{ formatCount(stats.deadlineTotal) }} 件
            </span>
          </div>
          <div class="stat">
            <span class="stat-label">待处理数</span>
            <span class="stat-value fuy-num">{{ pendingLabel }}</span>
          </div>
        </section>
        <!-- 错误态=说问题给恢复小卡（G6：禁伪装空态） -->
        <div v-else-if="statsError !== null" key="stat-error" class="ae-stat-error" role="alert">
          <p class="ae-stat-error-title">今日概览加载失败</p>
          <p class="ae-stat-error-hint">
            网络异常或服务暂不可用，统计与待处理计数暂缺；可重试，问题持续请联系系统管理员。
          </p>
          <el-button type="primary" size="small" :loading="overviewLoading" @click="refreshOverview"
            >重试</el-button
          >
        </div>
        <!-- 骨架态：静态金洗纸块（min-height 72px 锁 CLS，甲-3.6） -->
        <div
          v-else
          key="stat-skeleton"
          class="ae-stat-strip ae-stat-strip--skeleton"
          aria-hidden="true"
        >
          <div v-for="index in 3" :key="index" class="stat">
            <span class="fuy-sk fuy-sk-label"></span>
            <span class="fuy-sk fuy-sk-value"></span>
          </div>
        </div>
      </Transition>

      <!-- 事件清单卡（卷宗卡 + .fuy-dense 密排——挂卡容器构成后代关系；筛选+分页表同卡） -->
      <section class="fuy-card fuy-dense ae-list-card" :style="{ '--fuy-stagger-index': 2 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">事件清单</h2>
          <span class="ae-list-total"
            >共 <span class="fuy-num">{{ total }}</span> 条</span
          >
        </header>
        <div class="fuy-card-body">
          <!-- 筛选区（契约 ⑧.3）：label 疏排 + native select（jsdom 可测口径，存量同款） +
             上报入口右挂（PR-4F #31，NURSE 绑定）v-perm 直挂；类别/状态筛选为读面过滤不挂码 -->
          <div class="fuy-filter ae-filter-row">
            <label for="ae-category-filter">事件类别</label>
            <select
              id="ae-category-filter"
              v-model="categoryFilter"
              class="ae-filter-select"
              aria-label="事件类别"
              @change="search"
            >
              <option value="">全部类别</option>
              <option v-for="item in ADVERSE_CATEGORY_OPTIONS" :key="item.code" :value="item.code">
                {{ item.label }}
              </option>
            </select>
            <label for="ae-status-filter">处理状态</label>
            <select
              id="ae-status-filter"
              v-model="statusFilter"
              class="ae-filter-select"
              aria-label="处理状态"
              @change="search"
            >
              <option value="">全部状态</option>
              <option value="REPORTED">待处理</option>
              <option value="HANDLING">处理中</option>
              <option value="CLOSED">已关闭</option>
            </select>
            <div class="fuy-filter-actions">
              <el-button
                v-perm="'nursing:adverse-event:btn:manage'"
                type="primary"
                @click="openReport"
                >上报事件</el-button
              >
            </div>
          </div>

          <!-- 列表（分页；单号/类别/分级+等级/病区/时点/经过/时限标记/状态+匿名标记/操作） -->
          <div v-loading="listLoading" class="ae-table-wrap">
            <el-table :data="rows">
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
                    :type="deadlineMeta(row)?.overdue ? 'warning' : 'success'"
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
              <!-- 空态脸（契约 ⑥）：暂无语法 + 下一步指引，零默认纸箱插画 -->
              <template #empty>
                <div class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无不良事件记录</p>
                  <p class="fuy-empty-hint">
                    当前筛选与时段内暂无上报，可调整筛选条件或点击「上报事件」主动报告。
                  </p>
                </div>
              </template>
            </el-table>
          </div>
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
      </section>

      <!-- 上报弹窗（蓝图 甲-3.3：560px 挂 .fuy-dialog，表单挂 .fuy-form；必填面与匿名开关
         既有语义零变动；显式 popper 一律 fuy-snap-popper） -->
      <el-dialog
        v-model="reportVisible"
        title="不良事件上报"
        width="560px"
        destroy-on-close
        class="fuy-dialog"
      >
        <el-form class="fuy-form" label-width="90px" size="small">
          <el-form-item label="事件类别" required>
            <el-select
              v-model="reportForm.category"
              placeholder="选择类别"
              popper-class="fuy-snap-popper"
            >
              <el-option
                v-for="item in ADVERSE_CATEGORY_OPTIONS"
                :key="item.code"
                :label="item.label"
                :value="item.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="严重度分级" required>
            <el-select
              v-model="reportForm.severityClass"
              placeholder="I 最重 → IV 最轻"
              popper-class="fuy-snap-popper"
            >
              <el-option
                v-for="item in SEVERITY_CLASS_OPTIONS"
                :key="item.code"
                :label="item.label"
                :value="item.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="严重度等级" required>
            <el-select
              v-model="reportForm.severityGrade"
              placeholder="A ~ E"
              popper-class="fuy-snap-popper"
            >
              <el-option
                v-for="item in SEVERITY_GRADE_OPTIONS"
                :key="item.code"
                :label="item.label"
                :value="item.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="发生病区" required>
            <div class="ae-ward-field">
              <!-- 病区下拉换真数据（甲-3.5：WARD_OPTIONS 假常量清零→listOrgs WARD 档）；
                 清单在途/空清单禁用——出网值必须锚定真实病区编码 -->
              <el-select
                v-model="reportForm.wardId"
                :loading="wardLoading"
                :disabled="wardDisabled"
                placeholder="选择发生病区"
                popper-class="fuy-snap-popper"
              >
                <el-option
                  v-for="org in wardOptions"
                  :key="org.orgCode"
                  :label="`${org.orgName}（${org.orgCode}）`"
                  :value="org.orgCode ?? ''"
                />
              </el-select>
              <!-- 清单失败=说问题给恢复（G4/G6：不伪装空选项） -->
              <div v-if="wardError !== null" class="ae-ward-error" role="alert">
                <span>病区清单加载失败，暂无法选择发生病区</span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :loading="wardLoading"
                  @click="loadWards"
                  >重试</el-button
                >
              </div>
            </div>
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

      <!-- 钤印确认浮层（契约 ⑧.5：只承成功确认；文案=动词结果不带操作人与单号——匿名口径；
         v-if 挂载制=护士站先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
         常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治；seq 递增重挂重放落章
         动画，≤1200ms 收场归 composable） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>

      <!-- 页脚批注（契约 ⑧.8）：统计时点=后端统计日真值 + 手动刷新取数语义（禁放操作钮） -->
      <footer v-if="stats !== null" class="fuy-page-foot" :style="{ '--fuy-stagger-index': 3 }">
        统计时点 {{ statsDateLabel }} · 手动刷新取数，不自动轮询
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* 门牌状态位：待处理胶囊 + 刷新统计钮同行（蓝图 甲-3.2；乙-1「状态位=刷新钮」同位语法） */
.fuy-page-status {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——护士站
   ward-board-flow 同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与
   区块间距由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 */
.ae-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* ===== 今日概览统计带：HomeView stat-strip 三格同构——竖发丝分隔直接坐纸上，非卡片 ===== */
.ae-stat-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-bottom: var(--fuy-border-panel);
}

.stat {
  min-width: 0;
  padding: var(--fuy-space-3) var(--fuy-space-5) var(--fuy-space-3) var(--fuy-space-4);
  border-left: var(--fuy-border-hairline);
}

.stat:first-child {
  border-left: 0;
  padding-left: var(--fuy-space-1);
}

.stat-label {
  display: block;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 关键计数 key-count：700 字重 + tabular 等宽（数字不跳宽 Rule） */
.stat-value {
  display: block;
  margin-top: var(--fuy-space-1);
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  line-height: 1.25;
  color: var(--fuy-color-text-emphasis);
}

.stat-delta {
  display: block;
  margin-top: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 骨架态 min-height 72px 锁 CLS（蓝图 甲-3.6）：骨架→内容 200ms fade 走全局过渡类 */
.ae-stat-strip--skeleton {
  min-height: 72px;
}

/* 错误态小卡：说问题 + 给恢复（重试=墨实底主操作，「墨即操作」；不伪装空态） */
.ae-stat-error {
  padding: var(--fuy-space-4);
  border: 1px dashed var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-lg);
  text-align: center;
}
.ae-stat-error-title {
  margin: 0;
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
.ae-stat-error-hint {
  margin: var(--fuy-space-2) auto 0;
  max-width: 46ch;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ae-stat-error .el-button {
  margin-top: var(--fuy-space-3);
}

/* ===== 事件清单卡 ===== */
.ae-list-total {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 筛选行：与卡体拉开一档（下缘发丝=卡内细节规线，规线不越级） */
.ae-filter-row {
  padding-bottom: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-3);
  border-bottom: var(--fuy-border-hairline);
}

/* 筛选 native select（jsdom 可测口径存量件）：最亮纸白 + 石规描边 + 32px 与默认钮同高 */
.ae-filter-select {
  height: 32px;
  padding: 0 var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--fuy-surface-input);
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

/* 分页条（右对齐经全局 .fuy-page .el-pagination 承载；首页不满页不渲染） */
.ae-pagination {
  margin-top: var(--fuy-space-2);
}

/* ===== 上报表单（原生 datetime/textarea 与 EP 密度口径对齐；底=最亮纸白） ===== */
.ae-datetime {
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--fuy-surface-input);
}
.ae-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--fuy-surface-input);
  resize: vertical;
}
.ae-visit {
  width: 220px;
}

/* 病区下拉列（下拉 + 失败恢复行纵排；错误=印泥朱 12px 说问题） */
.ae-ward-field {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  width: 100%;
}
.ae-ward-error {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
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

/* 统计带三态（内容/错误/骨架）out-in 切换 fade leave 段：motion.css 仅定义 enter 段，
   DoctorStation/护士站/PDA 同款页内补齐——旧态 120ms 淡出后再进新态，防三态硬切 */
.fuy-content-fade-leave-active {
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit);
}
.fuy-content-fade-leave-to {
  opacity: 0;
}
</style>
