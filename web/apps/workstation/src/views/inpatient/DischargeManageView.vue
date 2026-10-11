<script setup lang="ts">
// 出院管理页（/inpatient/discharge，M04 FU-M04-07 前端面 · 暖纸卷宗换脸重排 乙-5，
// 「换脸不换业务」：申请事务/双条件放行/BLOCKED 分叉/取消链/+08:00 钉面解析零变动）：
// 门牌页首（衬线标题 + 状态位「发起出院申请」墨实底钮 + 事务编排批注行）→ 四态签申请列表卡
// （REQUESTED 预审中/READY 待离院确认/BLOCKED 挂账审批中/COMPLETED 已离院，.fuy-tabs 卷宗签
// 替换 radio-button 组，签线朱砂为装饰走线）+ 申请发起弹窗（预出院时间/离院方式病案首页
// 词表，挂 .fuy-dialog）+ 清理与预审结果卡（.fuy-sign-divider 三段=清理三清单计数 → 医嘱
// 追踪清单 → 欠费额[分→元全站唯一件换算、>0 印泥朱状态语义]+结算标记+挂账凭证；挂账审批
// 引导 BLOCKED 走 M13 收费面既有页，凭 billing.arrears.approved 自动转 READY）+ 离院确认
// （双条件=预审 READY 且结算完成，与后端 GC19 前置同语义；不满禁用按钮+原因提示）。
// 成功反馈=钤印（「已提交/已离院」，契约 ⑧.5 v-if 挂载制）；BLOCKED 等业务拒绝=ElMessage
// 警示条非钤印（预审未通过不是成功态）。
// 说明：冻结 REST 面无出院申请列表 GET 端点，列表由本会话发起的申请单承载（后端补列表
// 端点后可平滑切换，PR 描述登记）。
// 全部写操作自带在途守卫（入口早退先于一切 await）；失败弹错归响应拦截器（AxiosError 防双弹）。
import { computed, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { discharge, DISCHARGE_STATUS_LABELS, DISCHARGE_WAY_OPTIONS } from '@/api/inpatient';
import type { ClearanceVO, DischargeRequestVO } from '@/api/inpatient';
import { useAuthStore } from '@/stores/auth';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { fenToYuanDisplay } from '@/utils/money';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

const auth = useAuthStore();

/* ---------- 门牌批注行：签认人（会话身份）· 时刻（本地时钟零出网，甲-3 先例同构） ---------- */
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

/** 四态 tab 词表（后端 DischargeRequestStatus 主链四态；CANCELLED 行在创建来源态内自然消隐） */
const STATUS_TABS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'REQUESTED', label: '预审中' },
  { code: 'READY', label: '待离院确认' },
  { code: 'BLOCKED', label: '挂账审批中' },
  { code: 'COMPLETED', label: '已离院' },
];

/* ==================== 钤印确认反馈（契约 ⑧.5） ==================== */
/** 钤印文案（动词结果「已提交/已离院」；失败/业务拒绝走 ElMessage 警示条不盖钤印） */
const stampText = ref('');
/** 钤印可见态（v-if 挂载制：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为常显空朱块） */
const stampVisible = ref(false);
/** 钤印重挂序号：递增强制重挂节点重放 fuy-stamp-in 落章动画 */
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印并 ≤1200ms 收场（动画收尾后 v-if 卸载，弹层不驻留遮挡工作面） */
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

onUnmounted(() => {
  // 收场定时器仅翻转内部展示 ref（零 DOM/出网副作用），卸载清理防迟触发
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

/* ==================== 申请列表（会话内承载） ==================== */
/** 本会话发起的申请单（无列表 GET 端点，详见文件头说明） */
const requests = ref<DischargeRequestVO[]>([]);
const statusFilter = ref('REQUESTED');

/** 当前 tab 过滤后的申请行 */
const filteredRequests = computed(() =>
  requests.value.filter((row) => (row.status ?? '') === statusFilter.value),
);

/** 状态中文词表反查（行状态列） */
function statusLabel(code: string | undefined): string {
  return DISCHARGE_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 离院方式中文词表反查（行离院方式列） */
function dischargeWayLabel(code: string | undefined): string {
  return DISCHARGE_WAY_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/* ---------- 申请发起弹窗 ---------- */
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref({ visitId: '', expectDischargeAt: '', dischargeWay: '1' });

/** 打开申请弹窗（复位表单） */
function openCreate(): void {
  createForm.value = { visitId: '', expectDischargeAt: '', dischargeWay: '1' };
  createVisible.value = true;
}

/**
 * 提交出院申请：显式校验（就诊号/预出院时间，零出网）→ 出网（在途清理+费用预审单事务
 * 归后端编排）→ 申请单落会话列表并自动切至返回实态 tab（READY/BLOCKED 预审实态）。
 * 入口在途早退守卫防双击重复申请。
 */
async function onCreate(): Promise<void> {
  if (creating.value) {
    return;
  }
  if (createForm.value.visitId.trim() === '') {
    void ElMessage.warning('请填写在院就诊号');
    return;
  }
  const rawTime = createForm.value.expectDischargeAt;
  // W-70①：钉北京钟面防时区漂移——datetime-local 值是用户录入的北京钟面墙钟（HIS 用户面
  // 语义），裸交 new Date 会按运行环境本地时区解析致时点漂移；显式拼 +08:00 偏移钉面
  // （中国无夏令时，偏移恒定，北京时区客户端行为不变），跨时区客户端出网时点唯一
  // （Task 17 修复环 formatWith 同款北京钉面口径）
  const parsed = rawTime === '' ? null : new Date(`${rawTime}+08:00`);
  if (parsed === null || Number.isNaN(parsed.getTime())) {
    void ElMessage.warning('请选择预出院时间');
    return;
  }
  creating.value = true;
  try {
    // 北京钟面墙钟（+08:00 钉面解析）→ ISO date-time（OffsetDateTime 契约可解析形态）
    const saved = await discharge.create(createForm.value.visitId.trim(), {
      expectDischargeAt: parsed.toISOString(),
      dischargeWay: createForm.value.dischargeWay,
    });
    requests.value.push(saved);
    statusFilter.value = saved.status ?? 'REQUESTED';
    createVisible.value = false;
    if (saved.status === 'BLOCKED') {
      // BLOCKED=业务拒绝实态（预审未通过不是成功态）：ElMessage 警示条，不盖钤印
      void ElMessage.warning(
        `出院申请 ${saved.requestNo ?? ''} 已提交：预审未通过（欠费挂账审批中）`,
      );
    } else {
      // 成功=钤印「已提交」（蓝图乙-5.4：文案=动词结果，≤1200ms 收场）
      showStamp('已提交');
    }
  } catch (error) {
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ---------- 取消申请（仅 REQUESTED 态，回在院医嘱不复活） ---------- */
const cancelling = ref(false);

/** 取消出院申请（中档确认；状态机由后端 IP-1017 把守） */
async function onCancel(row: DischargeRequestVO): Promise<void> {
  if (cancelling.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `即将取消出院申请 ${row.requestNo ?? ''}（就诊回在院，医嘱不复活），确认？`,
      '取消出院申请',
      { confirmButtonText: '确认取消', cancelButtonText: '返回' },
    );
  } catch {
    return;
  }
  cancelling.value = true;
  try {
    const saved = await discharge.cancel(row.requestNo ?? '');
    row.status = saved.status;
    void ElMessage.success(`出院申请已取消：${saved.requestNo ?? ''}`);
  } catch (error) {
    surfaceBizError(error);
  } finally {
    cancelling.value = false;
  }
}

/* ==================== 清理与预审结果面板 ==================== */
/** 面板锚定申请单（清理预审/离院确认共用） */
const panelRequest = ref<DischargeRequestVO | null>(null);
const clearance = ref<ClearanceVO | null>(null);

/** 拉取清理与预审结果（清理三清单+追踪清单+欠费额+结算标记+挂账凭证）：loading 骨架经
 * useAsyncTask 收拢（EX-42 范式迁移，行为与迁移前一致——失败弹错归响应拦截器、驻留旧
 * 面板）；任务体首行先锚定 panelRequest，保持「发起新请求前先锚定」时序（面板头与离院
 * 确认共吃该锚点） */
const { loading: clearanceLoading, run: loadClearance } = useAsyncTask(
  async (row: DischargeRequestVO) => {
    // 发起前先锚定面板申请单（时序红线：锚定先于出网）
    panelRequest.value = row;
    // 归一化兜底：异常空响应收敛 null，防 undefined 穿透守卫致渲染面空引用
    clearance.value = (await discharge.clearance(row.requestNo ?? '')) ?? null;
  },
);

/** 欠费额展示（分→元，全站唯一件换算；0 或空=无欠费） */
const arrearsDisplay = computed(() => {
  const raw = clearance.value?.arrearsAmount;
  if (raw === undefined || raw === null) {
    return null;
  }
  return fenToYuanDisplay(raw);
});

/** 欠费额>0 判定（仅作展示语义开关——>0 印泥朱/0 中性；符号比对非金额运算，
 * 分→元换算仍归 utils/money 全站唯一件） */
const arrearsPositive = computed(() => {
  const raw = clearance.value?.arrearsAmount;
  // 正整数字符串形态收窄后 BigInt 符号比对（'0'/'00'/负值/非整串均归中性态）
  return raw !== undefined && raw !== null && /^\d+$/.test(String(raw)) && BigInt(raw) > 0n;
});

/** 预审 BLOCKED（挂账审批引导可见性） */
const isBlocked = computed(() => clearance.value?.status === 'BLOCKED');

/** 离院确认双条件：预审 READY 且结算完成（后端 GC19 前置同语义，前端禁用为同态预检） */
const canConfirm = computed(
  () =>
    clearance.value?.status === 'READY' &&
    clearance.value?.settlementCompletedAt !== undefined &&
    clearance.value?.settlementCompletedAt !== null,
);

/** 双条件不满原因提示（逐条件判定位） */
const confirmBlockReason = computed(() => {
  if (!clearance.value || canConfirm.value) {
    return '';
  }
  if (clearance.value.status === 'BLOCKED') {
    return '预审未通过（欠费挂账审批中），不可确认离院';
  }
  if (clearance.value.status !== 'READY') {
    return `预审状态非 READY（当前=${statusLabel(clearance.value.status)}），不可确认离院`;
  }
  return '出院结算未完成，不可确认离院';
});

/* ---------- 离院确认 ---------- */
const confirming = ref(false);

/**
 * 离院确认（双条件放行；床位终末消毒/出院带药放行/随访生成由后端联动）。
 * 随访三参数缺省=7 日/电话/「出院随访」由后端承载，前端不录（Spec 缺省语义直传空体）。
 */
async function onConfirm(): Promise<void> {
  if (confirming.value || panelRequest.value === null || !canConfirm.value) {
    return;
  }
  confirming.value = true;
  try {
    const saved = await discharge.confirm(panelRequest.value.requestNo ?? '', {});
    panelRequest.value.status = saved.status;
    // 成功=钤印「已离院」（蓝图乙-5.4 钤印族）；随后重拉预审结果刷新面板实态
    showStamp('已离院');
    await loadClearance(panelRequest.value);
  } catch (error) {
    // 双条件后端兜底 IP-1017：detail 原文透出
    surfaceBizError(error);
  } finally {
    confirming.value = false;
  }
}
</script>

<template>
  <!-- 页内级联容器 discharge-flow（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内
       区块；护士站 ae-flow 同构）：三段级联序 页首 0 / 两栏主区 1 / 页脚 2 -->
  <div class="fuy-page discharge-manage">
    <div class="fuy-stagger discharge-flow">
      <!-- 门牌页首（蓝图乙-5.2/契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（发起出院
         申请墨实底钮）+ 事务编排批注行（原 hint 升批注——「谁·时刻」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">出院管理</h1>
          <span class="fuy-page-en" aria-hidden="true">DISCHARGE</span>
        </div>
        <div class="fuy-page-status">
          <!-- 发起出院申请（PR-4F #16）：医生管理码 v-perm 直挂——弹窗提交随本入口同码
               不可达（入口隐藏则弹窗打不开），免重复挂接 -->
          <el-button
            v-perm="'inpatient:discharge:btn:manage'"
            type="primary"
            :disabled="creating"
            @click="openCreate"
            >发起出院申请</el-button
          >
        </div>
        <p class="fuy-page-note">
          在途清理 + 费用预审单事务编排 · 双条件离院放行 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左：四态签申请列表卡（蓝图乙-5.2：卷宗签 .fuy-tabs 替换 radio-button 组） -->
        <el-col :md="24" :lg="14">
          <section class="fuy-card fuy-dense discharge-list-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">出院申请单</h2>
              <span class="discharge-card-note">本会话发起的申请单</span>
            </header>
            <div class="fuy-card-body">
              <!-- 卷宗签语法（契约 ⑤#8）：激活签顶部 2px 朱砂签线 + 浓墨 600 由 .fuy-tabs
                   承载；签作纯过滤器（会话列表单表复用，签=状态筛选非内容分区） -->
              <el-tabs v-model="statusFilter" class="fuy-tabs">
                <el-tab-pane
                  v-for="tab in STATUS_TABS"
                  :key="tab.code"
                  :name="tab.code"
                  :label="tab.label"
                />
              </el-tabs>
              <el-table
                v-if="filteredRequests.length > 0"
                :data="filteredRequests"
                class="fuy-dense"
              >
                <el-table-column label="申请单号" min-width="120">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.requestNo }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="就诊号" min-width="120">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.visitId }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="离院方式" width="110">
                  <template #default="{ row }">
                    {{ dischargeWayLabel(row.dischargeWay) }}
                  </template>
                </el-table-column>
                <el-table-column label="预出院时间" width="110">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ formatTime(row.expectDischargeAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" class="fuy-tag-aa">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="150" class-name="fuy-ops-8">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="loadClearance(row)"
                      >清理预审</el-button
                    >
                    <!-- 取消申请（PR-4F #16）：管理码 v-perm 直挂（GET 清理预审只读不挂）——
                         与 REQUESTED 状态数据态 v-if 正交（状态不符不渲染，无码 DOM 移除） -->
                    <el-button
                      v-if="row.status === 'REQUESTED'"
                      v-perm="'inpatient:discharge:btn:manage'"
                      link
                      type="danger"
                      size="small"
                      :disabled="cancelling"
                      @click="onCancel(row)"
                      >取消</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（契约 ⑥）：主句=暂无〈业务客体〉+ 说明给下一步 + 会话承载降级口径 -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无出院申请</p>
                <p class="fuy-empty-hint">
                  点击「发起出院申请」提交后，本会话内提交的申请单在此查看（冻结 REST 面无列表 GET
                  端点，列表由本会话发起的申请单承载）。
                </p>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右：清理与预审结果卡（蓝图乙-5.2 三段：清理三清单 → 医嘱追踪清单 → 欠费与结算） -->
        <el-col :md="24" :lg="10">
          <section class="fuy-card discharge-panel-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">清理与预审结果</h2>
              <span v-if="panelRequest !== null" class="discharge-card-note"
                >申请单 <span class="fuy-num">{{ panelRequest.requestNo }}</span></span
              >
            </header>
            <div v-loading="clearanceLoading" class="fuy-card-body discharge-panel-body">
              <template v-if="clearance">
                <!-- 段一：清理三清单计数 -->
                <el-descriptions :column="3" border size="small" class="discharge-desc">
                  <el-descriptions-item label="长期医嘱停止">
                    <span class="fuy-num">{{ clearance.stoppedLongCount ?? 0 }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="作废计划数">
                    <span class="fuy-num">{{ clearance.cancelledPlanCount ?? 0 }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="预审状态">
                    {{ statusLabel(clearance.status) }}
                  </el-descriptions-item>
                </el-descriptions>

                <!-- 「签」字分界（蓝图乙-5.3：文书分界语义替代 h4 平铺，三段各自成文） -->
                <div class="fuy-sign-divider" aria-hidden="true"></div>

                <!-- 段二：医嘱追踪清单（段标统一挂全局类 .fuy-section-title——D-30 段标口径
                     统一：契约 §④.1 段标属黑体默认继承面，14px/600 浓墨档） -->
                <p class="fuy-section-title">医嘱追踪清单</p>
                <el-table
                  v-if="(clearance.trackedOrders ?? []).length > 0"
                  :data="clearance.trackedOrders"
                  class="fuy-dense"
                >
                  <el-table-column label="医嘱号" min-width="130">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.orderNo }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="分类" width="70">
                    <template #default="{ row }">
                      {{ row.orderClass === 'LONG' ? '长期' : '临时' }}
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="90">
                    <template #default="{ row }">
                      {{ row.status }}
                    </template>
                  </el-table-column>
                </el-table>
                <p v-else class="discharge-empty-line">追踪清单为空（在途医嘱已全部终态）</p>

                <div class="fuy-sign-divider" aria-hidden="true"></div>

                <!-- 段三：欠费与结算 + 挂账引导 + 确认离院行 -->
                <el-descriptions :column="2" border size="small" class="discharge-desc">
                  <el-descriptions-item label="欠费额">
                    <span class="fuy-num" :class="{ 'discharge-arrears': arrearsPositive }"
                      >{{ arrearsDisplay ?? '0.00'
                      }}<span class="discharge-arrears-unit">元</span></span
                    >
                  </el-descriptions-item>
                  <el-descriptions-item label="结算完成">
                    <span class="fuy-num">{{
                      clearance.settlementCompletedAt
                        ? formatTime(clearance.settlementCompletedAt)
                        : '未完成'
                    }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="挂账凭证">
                    <span class="fuy-num">{{ clearance.approvalNo ?? '—' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="申请状态">
                    {{ statusLabel(clearance.status) }}
                  </el-descriptions-item>
                </el-descriptions>

                <!-- 挂账审批引导（BLOCKED：走 M13 收费面既有页，凭事件自动转 READY；
                     警示洗底经 A-5 已批 EP light-9 语义通道） -->
                <p v-if="isBlocked" class="discharge-blocked-guide">
                  预审未通过（欠费 {{ arrearsDisplay ?? '0.00' }} 元）：须走计费域「欠费挂账审批」
                  （收费面既有页）提交审批，审批通过凭 billing.arrears.approved
                  自动转「待离院确认」。
                </p>

                <!-- 离院确认（双条件不满禁用 + 原因提示，文案逐字冻结） -->
                <div class="discharge-confirm-row">
                  <span
                    v-if="!canConfirm && panelRequest !== null"
                    class="discharge-confirm-reason"
                    >{{ confirmBlockReason }}</span
                  >
                  <!-- 离院确认（PR-4F #16）：管理码 v-perm 直挂——与双条件 canConfirm 数据态
                       :disabled 正交叠加（权限决定在不在 DOM，欠费/结算未结决定可不可点） -->
                  <el-button
                    v-perm="'inpatient:discharge:btn:manage'"
                    type="danger"
                    plain
                    :loading="confirming"
                    :disabled="confirming || !canConfirm"
                    @click="onConfirm"
                    >确认离院</el-button
                  >
                </div>
              </template>
              <!-- 面板未锚定空态脸：主句=暂无〈客体〉+ 说明给下一步（替代原 el-empty 兜底） -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无清理预审结果</p>
                <p class="fuy-empty-hint">从左侧申请单点击「清理预审」查看结果。</p>
              </div>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- 申请发起弹窗（G2：挂 .fuy-dialog 衬线标题 + 头部发丝；表单容器挂 .fuy-form，
           原生 input/select 保留 jsdom 可测口径与 aria 标注） -->
      <el-dialog v-model="createVisible" title="发起出院申请" width="420px" class="fuy-dialog">
        <div class="fuy-form discharge-form">
          <label class="discharge-field-label">在院就诊号</label>
          <input
            v-model="createForm.visitId"
            class="discharge-input fuy-num"
            placeholder="I 型 14 位就诊号"
            aria-label="在院就诊号"
          />
          <label class="discharge-field-label">预出院时间（必填）</label>
          <input
            v-model="createForm.expectDischargeAt"
            type="datetime-local"
            class="discharge-input fuy-num"
            aria-label="预出院时间"
          />
          <label class="discharge-field-label">离院方式（病案首页词表）</label>
          <select v-model="createForm.dischargeWay" class="discharge-input" aria-label="离院方式">
            <option v-for="item in DISCHARGE_WAY_OPTIONS" :key="item.code" :value="item.code">
              {{ item.label }}
            </option>
          </select>
        </div>
        <template #footer>
          <el-button size="small" @click="createVisible = false">取消</el-button>
          <el-button
            type="primary"
            size="small"
            :loading="creating"
            :disabled="creating"
            @click="onCreate"
            >提交申请</el-button
          >
        </template>
      </el-dialog>

      <!-- 钤印确认浮层（契约 ⑧.5）：只承成功确认——「已提交/已离院」；BLOCKED 等业务拒绝走
           ElMessage 警示条不盖钤印；v-if 挂载制=护士站先例同构，挂载即 is-show（stamp-in
           特异性压过 rise），seq 递增重挂重放落章动画，≤1200ms 收场 -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>

      <!-- 页脚批注（契约 ⑧.8）：会话列表降级口径逐字保留（数据语义，禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        列表由本会话发起的申请单承载（冻结 REST 面无出院申请列表 GET
        端点，后端补列表端点后可平滑切换）· 清理与预审结果随「清理预审」查询刷新
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4）：页面根不挂 .fuy-stagger 后根级 flex gap 失效，纵排与区块间距
   由本层同值承接（.fuy-page 同构 flex+gap，护士站 ae-flow 同构），保证视觉零漂移 */
.discharge-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：发起申请墨实底钮右挂（蓝图乙-5.2；甲-3 状态位同位语法） */
.fuy-page-status {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 卡头副注：会话列表降级口径/面板锚定申请单（12px 灰墨，G1 卡头副注归位） */
.discharge-card-note {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
}

/* 预审面板主体 min-height 锁 CLS（蓝图乙-5.6：骨架 280px） */
.discharge-panel-body {
  min-height: 280px;
}

.discharge-desc {
  margin-bottom: var(--fuy-space-3);
}

/* 「签」分界与上下段间距（蓝图乙-5.3：.fuy-sign-divider 三段文书分界）；段二分区标题已
   统一挂全局类 .fuy-section-title（D-30 段标口径统一：14px/600 浓墨黑体档归
   element-plus.css 全局承载，原 12px 灰墨局部段标声明已删） */
.discharge-panel-body .fuy-sign-divider {
  margin: var(--fuy-space-3) 0;
}

.discharge-empty-line {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 欠费额>0：印泥朱状态语义（蓝图乙-5.3——状态朱只承危急/欠费状态语，不作装饰）；
   0/空为中性纸墨。「元」单位随值同色弱字重（数字主体 tabular 强调） */
.discharge-arrears {
  font-weight: 700;
  color: var(--fuy-color-danger-text);
}

.discharge-arrears-unit {
  margin-left: 2px;
  font-weight: 400;
}

/* 挂账审批引导（BLOCKED：warning 语义左缘 + 洗底经 A-5 已批 EP light-9 语义通道，零新色值） */
.discharge-blocked-guide {
  margin: var(--fuy-space-3) 0;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--el-color-warning-light-9);
  border-left: 3px solid var(--fuy-color-warning-text);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
}

/* 离院确认行：原因提示居左（印泥朱），按钮居右 */
.discharge-confirm-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--fuy-space-3);
}

.discharge-confirm-reason {
  flex: 1;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 弹窗表单基元（token 取色） */
.discharge-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 弹窗表单基元：围合取值直取 --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边，
   弹窗卡面亮纸底上输入域亮起一档；石规与 EP border 映射同源，五页表单基元同形） */
.discharge-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}
</style>
