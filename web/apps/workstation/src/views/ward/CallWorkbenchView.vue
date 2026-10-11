<script setup lang="ts">
// 呼叫工作台（/ward/call-workbench，M16 病区呼叫闭环面前端面 · 暖纸卷宗换脸重排 丁-2，
// 「换脸不换业务」：闭环五动作状态互斥/完成摘要强制留痕/行级在途守卫零变动）四段纵向序：
// 门牌页首（衬线标题 + 「待应答 N」琥珀胶囊自 rows 过滤 CREATED 派生 + 病区/状态筛选 + 刷新 +
// 声音提示缺位诚实注记批注行——原 hint 升批注保留）→ 呼叫列表卷宗卡（升级行=左缘分级橙 3px
// 规线与「已升级 N 次」徽标行级双通道——超时自动升级紧迫语义行级可扫）→ 完成弹窗
// （resultSummary 处置结果强制留痕）→ 页脚批注。状态机流转规则单点归后端把守，前端按行状态
// 暴露入口，非法流转由后端拒绝并弹错；成功反馈=钤印族（已应答/已开始处理/已完成/已转接/
// 已取消），失败与业务拒绝=message 批注条（钤印永不承失败）。声音提示本批次未接入（静默
// 提示面，后续补齐——批注行诚实注记留痕）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { CALL_SOURCE_LABELS, CALL_STATUS_LABELS, CALL_TYPE_LABELS, wardCalls } from '@/api/ward';
import type { WardCallVO } from '@/api/ward';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

const auth = useAuthStore();

/* ==================== 门牌页首批注行（「谁 · 时刻」） ==================== */

/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const operatorName = computed(() => auth.user?.displayName ?? '—');

/** 批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 呼叫状态/类型/来源词表反查 ==================== */

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

/** 病区筛选（G4：ward.ts WARD_OPTIONS 假常量清零 → listOrgs WARD 档真数据；空串=全部病区
 * 全院口径——蓝图明示空值选项保留，真数据源无恒定首项，默认全院起面） */
const wardFilter = ref('');
/** 状态筛选（默认待应答——工作台主战场；空串=全部六态） */
const statusFilter = ref('CREATED');

/** 页脚数据时点（页脚批注「数据时点」真值；成功分支回填，失败驻留旧时点——只叙事实） */
const loadedAt = ref<string | null>(null);

/** 加载呼叫列表（wardId/status 过滤由后端承载，前端按返回序直出）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式，固定首页 size 50 直出——失败弹错归响应拦截器；驻留旧
 * 清单；成功分支回填页脚数据时点） */
const {
  rows,
  total,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({
    wardId: wardFilter.value === '' ? undefined : wardFilter.value,
    status: statusFilter.value === '' ? undefined : (statusFilter.value as WardCallVO['status']),
  }),
  fetcher: ({ wardId, status, page, size }) => wardCalls.page({ wardId, status, page, size }),
  pageSize: 50,
  onSuccess: () => {
    loadedAt.value = new Date().toISOString();
  },
});

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const loadedAtLabel = computed(() => (loadedAt.value === null ? '—' : formatTime(loadedAt.value)));

/* ==================== 待应答计数胶囊（丁-2.2：前端自 rows 过滤 CREATED 派生，零出网） ==================== */

/** 待应答计数（当前页行集内 CREATED 数；默认筛选 CREATED 时即行集数——开场面计数胶囊，
 * 派生口径与列表同源，禁另起出网） */
const pendingCount = computed(() => rows.value.filter((row) => row.status === 'CREATED').length);

/* ==================== 病区清单（G4：listOrgs WARD 档真数据三态） ==================== */

/** 病区选项（listOrgs 真数据面，V1123 种子同链路；仅保留带 code 的机构行） */
const wardOptions = ref<OrgVO[]>([]);

/** 病区清单加载（useAsyncTask 三态）：加载中下拉禁用 / 失败批注行+重试 / 就绪可选；
 * 失败态只落 error 驱动页内说问题给恢复行，错误详情弹错归响应拦截器——页内不重弹
 * （执行工作台同款口径） */
const {
  loading: wardLoading,
  error: wardError,
  run: loadWards,
} = useAsyncTask(async () => {
  wardOptions.value = (await listOrgs({ type: 'WARD' })).filter((org) => org.orgCode);
});

/** 病区下拉禁用态：清单在途或失败待重试皆不可选（防误选空选项；「全部病区」全院口径不受影响
 * ——空值仍可筛，读面不因清单失败阻断） */
const wardDisabled = computed(() => wardLoading.value || wardError.value !== null);

/* ==================== 升级行级契约类（丁-2.2：左缘分级橙 3px 规线行级扫读） ==================== */

/** 行级契约类：升级行（escalationCount>0）=左缘分级橙 3px 规线（行级扫读双通道，与「已升级
 * N 次」徽标同义——A-3 分级橙族紧迫语义，超时自动升级由后端承载） */
function callRowClassName({ row }: { row: WardCallVO }): string {
  return (row.escalationCount ?? 0) > 0 ? 'call-escalated-row' : '';
}

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

/** 应答（CREATED→ANSWERED；应答人取会话由后端承载；成功=钤印「已应答」） */
async function onAnswer(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.answer(row.callNo);
    showStamp('已应答');
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/** 处理（ANSWERED→IN_PROGRESS，开始到场处置；成功=钤印「已开始处理」） */
async function onProgress(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.progress(row.callNo);
    showStamp('已开始处理');
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/** 转接（转接目标链路归后端路由引擎；转接后状态置 TRANSFERRED；成功=钤印「已转接」） */
async function onTransfer(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.transfer(row.callNo);
    showStamp('已转接');
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    releasePending(row.callNo);
  }
}

/** 取消（非终态可取消，终态拒绝归后端把守；成功=钤印「已取消」） */
async function onCancel(row: WardCallVO): Promise<void> {
  if (row.callNo === undefined || guardPending(row.callNo)) {
    return;
  }
  try {
    await wardCalls.cancel(row.callNo);
    showStamp('已取消');
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

/** 确认完成：摘要强制显式校验（空摘要零出网）→ 出网 → 盖钤印「已完成」→ 关窗刷新。 */
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
    showStamp('已完成');
    completeVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    completing.value = false;
  }
}

/* ==================== 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ==================== */

/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=入院登记台先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认——已应答/已开始处理/已完成/已转接/已取消；失败与业务拒绝归
 * message 批注条/拦截器，钤印永不承失败） */
function showStamp(text: string): void {
  stampText.value = text;
  stampSeq.value += 1;
  stampVisible.value = true;
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
  stampTimer = setTimeout(() => {
    stampVisible.value = false;
    stampTimer = null;
  }, 1200);
}

onBeforeUnmount(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
});

onMounted(() => {
  void loadList();
  void loadWards();
});
</script>

<template>
  <div class="fuy-page call-workbench">
    <!-- 页内级联容器（契约 ⑦.4）：页面根节点禁挂 .fuy-stagger——stagger 挂本层区块
         （ward-board-flow/product-flow 同构形态），本层承接 flex+gap 承担纵排与区块间距；
         级联序：门牌 0 / 列表卡 1 / 页脚 2 -->
    <div class="fuy-stagger call-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（待应答琥珀胶囊 +
           病区/状态筛选 + 刷新）+ 声音缺位诚实注记批注行 + 2px 墨规收底（脸归全局族） -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">病区呼叫工作台</h1>
          <span class="fuy-page-en" aria-hidden="true">CALL DESK</span>
        </div>
        <div class="fuy-page-status">
          <!-- 待应答计数胶囊（丁-2.2）：琥珀双通道（色+文字），计数自 rows 过滤 CREATED
               派生零出网——待应答量开场面，禁行级过度着色（CREATED 行零特殊底色） -->
          <span class="fuy-status-pill fuy-status-pill--amber" role="status"
            >待应答 {{ pendingCount }}</span
          >
          <!-- 病区筛选换真数据（G4：WARD_OPTIONS 假常量清零 → listOrgs WARD 档）；
               「全部病区」空值选项保留=全院口径 -->
          <select
            v-model="wardFilter"
            class="call-filter-select"
            aria-label="病区筛选"
            :disabled="wardDisabled"
            @change="loadList"
          >
            <option value="">全部病区</option>
            <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
              {{ org.orgName }}（{{ org.orgCode }}）
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
          <el-button :loading="listLoading" @click="loadList">刷新</el-button>
        </div>
        <!-- 病区清单失败=说问题给恢复（G6：不伪装空选项；全院空值仍可筛不阻断读面） -->
        <div v-if="wardError !== null" class="call-ward-error" role="alert">
          <span>病区清单加载失败，暂无法按病区筛选</span>
          <el-button link type="primary" size="small" :loading="wardLoading" @click="loadWards"
            >重试</el-button
          >
        </div>
        <p class="fuy-page-note">
          闭环操作按行状态暴露 · 流转规则归后端把守 · 声音提示本批次未接入（静默提示面，后续补齐）·
          签认人 {{ operatorName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- ② 呼叫列表卡（卷宗卡 + .fuy-dense 密排——挂卡容器构成后代关系；升级行左缘分级橙
           规线行级扫读，50/页 usePagedList） -->
      <section class="fuy-card fuy-dense call-list-card" :style="{ '--fuy-stagger-index': 1 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">呼叫列表</h2>
          <!-- 计数取后端 total（后端筛选口径全量条数）而非当页行集——50/页下 rows.length
               只是当页截断值，卡头「共 N 条」必须是全量真值（G6 禁伪数据） -->
          <span class="call-list-total"
            >共 <span class="fuy-num">{{ total }}</span> 条</span
          >
        </header>
        <div v-loading="listLoading" class="fuy-card-body">
          <el-table
            v-if="rows.length > 0"
            :data="rows"
            class="fuy-dense"
            :row-class-name="callRowClassName"
          >
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
          <!-- 空态脸（G6/契约 ⑥）：主句「暂无呼叫记录」+ 说明给下一步；默认纸箱插画在
               .fuy-page 子树由全局段压制，本页走自绘空态脸零插画 -->
          <div v-else class="fuy-empty" role="status">
            <span class="fuy-empty-mark" aria-hidden="true">空</span>
            <p class="fuy-empty-title">暂无呼叫记录</p>
            <p class="fuy-empty-hint">可切换病区或状态筛选，或点击右上角刷新重取。</p>
          </div>
        </div>
      </section>

      <!-- ③ 完成弹窗（resultSummary 处置结果强制留痕；挂 .fuy-dialog=衬线标题+头部发丝
           全局脸，表单容器挂 .fuy-form 承文书脸） -->
      <el-dialog v-model="completeVisible" title="完成呼叫" width="420px" class="fuy-dialog">
        <div class="fuy-form call-complete-form">
          <p class="call-complete-target fuy-num">
            {{ completeTarget?.callNo ?? '' }} · {{ completeTarget?.bedId ?? '—' }} 床
          </p>
          <label class="call-field-label">处置结果（强制：处置方式与结果留痕）</label>
          <textarea
            v-model="resultSummary"
            class="call-input"
            rows="2"
            placeholder="如：已到场更换输液，患者无不适"
            aria-label="处置结果"
          ></textarea>
        </div>
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

      <!-- ④ 页脚批注（G1 纵向序收尾，契约 ⑧.8）：数据时点 + 手动刷新取数语义（零实时
           订阅——轮询刷新口径；禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        数据时点 {{ loadedAtLabel }} · 呼叫清单 50 条/页 · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：只承成功确认——已应答/已开始处理/已完成/已转接/已取消；
           v-if 挂载制=入院登记台先例同构，挂载即 is-show（stamp-in 特异性压过 rise），seq
           递增重挂重放落章动画，≤1200ms 收场） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——ward-board-flow
   同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与区块间距
   （门牌 0 / 列表卡 1 / 页脚 2 三段级联序）由本层同值承接（.fuy-page 同构 flex+gap），
   保证视觉零漂移 */
.call-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：待应答胶囊 + 病区/状态筛选 + 刷新右挂（丁-1/丁-3 同位语法——flex+gap 承载
   控件间距，flex-wrap 窄窗换行；全局 .fuy-page-status 仅 margin-left:auto，flex 面归页内
   承载，三页口径一致） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 门牌状态位筛选基元：native select 与 EP 密度口径对齐（存量页面同款，jsdom 可测口径）；
   围合取值直取 --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边，与卡面拉开一档） */
.call-filter-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 病区清单失败恢复行（说问题给恢复：状态朱文字+重试动作，G6） */
.call-ward-error {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 列表卡头计数（卷宗卡卡头右挂工具位，12px 灰墨档——卡头副注统一 12px 档） */
.call-list-total {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* 列表卡体 CLS 锁（蓝图 丁-2.6：骨架 min-height 240px）：加载/空态切换零塌陷 */
.call-list-card .fuy-card-body {
  box-sizing: border-box;
  min-height: 240px;
}

/* 升级行左缘分级橙 3px 规线（行级扫读双通道，与「已升级 N 次」徽标同义）：inset 盒影承载
   左缘规线，取色走 --fuy-color-triage-l2 分级橙语义通道（A-3 族，零新 hex）。规线落首列
   td 层（inset 盒影绘制于该 td 自身背景之上）——EP 行悬停底画在 td 背景层，规线若画在
   tr 层会被 td 背景盖住（转抄工作台 transfer-highrisk-row 同构先例），td 层承载则悬停态
   规线恒在上层可见；CREATED 行零特殊底色（待应答由计数胶囊+默认筛选承载，禁过度着色） */
.call-list-card :deep(tr.call-escalated-row td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--fuy-color-triage-l2);
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
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
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

/* 升级标记徽标（超时自动升级紧迫语义）：A-3 分级橙族与行级规线双通道同义（原印泥朱改
   分级橙——升级=紧迫升级非危急错误，双红分工下错误通道不承紧迫语义） */
.fuy-call-escalation {
  border-color: var(--fuy-color-triage-l2);
  color: var(--fuy-color-triage-l2);
}
</style>
