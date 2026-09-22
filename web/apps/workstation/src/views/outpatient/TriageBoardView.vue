<script setup lang="ts">
// 分诊台页（FU-M03-04 前端面，设计文档 §3.2/§8.2）：操作条常驻（报到 visitId 输入 Enter 提交
// + 诊区切换 + 轮询状态点）→ 队列快照表（5s REST 轮询 merge 刷新，稳定 key 禁整表重挂）→
// 右列票务详情与分诊处置（调级/转队列/二次分诊，风险分档确认 §5.2）。动作在途守卫先于一切
// await（W-22⑥ 形态自带）；失败弹错归响应拦截器。状态 tag 流转 §6.5（120ms out-in）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（billing 三页同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  adjustTriage,
  callNext,
  checkIn,
  getQueueSnapshot,
  passTicket,
  recallTicket,
} from '@/api/outpatient';
import type { QueueTicketVO } from '@/api/outpatient';

/** 分诊台终端标识（报到发起端配置；与后端 DEFAULT_STATION_ID 同语义的分诊台常量） */
const STATION_ID = 'TRIAGE_DESK';

/** 老幼残优先级因子词表（后端词表校验词表外 OP-1019，前端仅透传勾选值） */
const PRIORITY_FACTOR_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'ELDERLY', label: '老年' },
  { code: 'CHILD', label: '幼童' },
  { code: 'DISABLED', label: '残障' },
];

/** 票据状态标签映射（设计文档 §4.3 唯一映射表：type/附类/文案三列原样落码） */
const TICKET_STATUS_META: Record<
  string,
  { type: 'primary' | 'warning' | 'success' | 'info'; text: string; aa?: boolean; strike?: boolean }
> = {
  WAITING: { type: 'primary', text: '候诊中' },
  CALLED: { type: 'warning', text: '已叫号', aa: true },
  SERVING: { type: 'success', text: '就诊中', aa: true },
  SERVED: { type: 'info', text: '已就诊' },
  PASSED: { type: 'warning', text: '已过号', aa: true, strike: true },
  CANCELLED: { type: 'info', text: '已取消', strike: true },
};

/** 分诊级别徽标文案（Ⅰ危/Ⅱ急/Ⅲ重/Ⅳ普，色值走 .fuy-triage-badge--l1..l4 token） */
const TRIAGE_LEVEL_LABELS: Record<number, string> = { 1: 'Ⅰ级', 2: 'Ⅱ级', 3: 'Ⅲ级', 4: 'Ⅳ级' };

/**
 * 级别徽标类（W-29 D-2 契约回补：QueueTicketVO.triageLevel=visit 权威分级随票出网）。
 * 1-4 越界防御：契约值域外不渲染徽标；可空=非分级流程票据，列内显示占位「—」。
 *
 * @param level 票面分诊级别（后端 Integer 可空）
 * @return 徽标 modifier 类；值域外返回 null（模板据此降级为占位文本）
 */
function triageBadgeClass(level: number | undefined): string | null {
  if (level === undefined || level === null || level < 1 || level > 4) {
    return null;
  }
  return `fuy-triage-badge--l${level}`;
}

/* ---------- 操作条：报到与诊区切换 ---------- */
const visitIdInput = ref('');
const checkingIn = ref(false);
/** 当前诊区编码（=队列标识 queue_id，快照接口路径参数；切换即重拉首屏） */
const deptCode = ref('DEPT-INT');

/** 就诊号显式格式预检（§8.2：O+yyyyMMdd+5 位流水，空值拦截 + 形态拦截，双 4xx 口径提示） */
function isVisitIdFormatValid(raw: string): boolean {
  return /^O\d{13}$/.test(raw);
}

/** 已勾选的优先级因子（分诊台人工判定录入，可空） */
const priorityFactors = ref<string[]>([]);

/**
 * 分诊报到：前置格式校验（空值/形态两道拦截，违规零出网）→ 出网 → 成功提示票号并重拉快照
 * （新行入场由快照 merge 承载）。入口在途早退守卫：重渲染前第二击零出网。
 */
async function onCheckIn(): Promise<void> {
  if (checkingIn.value) {
    return;
  }
  const raw = visitIdInput.value.trim();
  if (raw === '') {
    void ElMessage.warning('请输入就诊号');
    return;
  }
  if (!isVisitIdFormatValid(raw)) {
    void ElMessage.warning('就诊号应为 O 开头加 13 位数字（O+yyyyMMdd+5 位流水），请核对后重试');
    return;
  }
  checkingIn.value = true;
  try {
    const ticket = await checkIn({
      visitId: raw,
      stationId: STATION_ID,
      priorityFactors: priorityFactors.value.length > 0 ? priorityFactors.value : undefined,
    });
    void ElMessage.success(`报到成功，票号 ${ticket.ticketNo ?? ''}`);
    visitIdInput.value = '';
    priorityFactors.value = [];
    await refreshSnapshot();
  } catch {
    // 失败弹错归响应拦截器；输入驻留供纠正重试
  } finally {
    checkingIn.value = false;
  }
}

/* ---------- 队列快照：5s 轮询 + merge 更新（§6.2 禁整表重挂） ---------- */
const tickets = ref<QueueTicketVO[]>([]);
/** 首拉遮罩开关：v-loading 仅承载首拉（§4.4「刷新一律 v-loading」不适用于轮询——
 * 5s 周期遮罩闪现破坏 §7.1「轮询刷新无整表闪烁」预算，后续轮询静默 merge） */
const snapshotBooting = ref(true);
/** 轮询状态点三态（§8.2）：ok=正常刷新 / paused=页面隐藏暂停 / error=上次刷新失败 */
const pollHealth = ref<'ok' | 'paused' | 'error'>('ok');
/** 轮询定时器句柄（visibilitychange 暂停/恢复共用；onBeforeUnmount 必清理） */
let pollTimer: ReturnType<typeof setInterval> | null = null;

const POLL_INTERVAL_MS = 5000;

/**
 * 拉取队列快照并 merge（按票 id 稳定键，同 id 行复用旧引用原位同步全量可变字段——行级 DOM
 * 不重挂 §6.2，状态 tag 流转由模板 :key=状态承载）。同状态但内容变化（如二次分诊 RE_TRIAGE
 * 改派 doctorId）必须同步，否则叫号携旧 doctorId 出网必 4xx——Task 15 Step6 真机 D-3：
 * 旧实现「同 id 同状态保留旧行」漏同步该场景。增删行自然触发列表 diff。
 * 失败置 error 态（轮询状态点转红）并驻留旧数据。
 */
async function refreshSnapshot(): Promise<void> {
  try {
    const latest = await getQueueSnapshot({ queueId: deptCode.value });
    const current = new Map(tickets.value.map((item) => [item.id, item]));
    tickets.value = latest.map((item) => {
      const existing = current.get(item.id);
      if (existing === undefined) {
        return item;
      }
      // 同 id 行保留引用、原位并入最新字段（doctorId/priorityScore/queueTime 等全量同步）
      Object.assign(existing, item);
      return existing;
    });
    pollHealth.value = 'ok';
  } catch {
    // 上次刷新失败：状态点转红，旧快照驻留（弹错归响应拦截器）
    pollHealth.value = 'error';
  } finally {
    snapshotBooting.value = false;
  }
}

/** 页面隐藏暂停轮询、可见恢复（§7.1 轮询刷新条款：visibilityState 隐藏时暂停） */
function onVisibilityChange(): void {
  if (document.hidden) {
    pollHealth.value = 'paused';
    return;
  }
  void refreshSnapshot();
  pollHealth.value = 'ok';
}

onMounted(() => {
  void refreshSnapshot();
  pollTimer = setInterval(() => {
    if (!document.hidden) {
      void refreshSnapshot();
    }
  }, POLL_INTERVAL_MS);
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  document.removeEventListener('visibilitychange', onVisibilityChange);
});

/** 诊区切换：清空选中行并立即重拉首屏（手动刷新走 v-loading 遮罩，§4.4 二分口径） */
function onDeptChange(): void {
  selectedTicket.value = null;
  snapshotBooting.value = true;
  void refreshSnapshot();
}

/* ---------- 队列表格展示辅助 ---------- */

/** 等待时长（分钟）：queueTime 距今；≥30 分钟文字转预警色（§8.2 无动画） */
function waitingMinutes(row: QueueTicketVO): number {
  if (!row.queueTime) {
    return 0;
  }
  return Math.max(0, Math.floor((Date.now() - new Date(row.queueTime).getTime()) / 60000));
}

function isWaitLong(row: QueueTicketVO): boolean {
  return waitingMinutes(row) >= 30;
}

function statusMeta(row: QueueTicketVO): {
  type: 'primary' | 'warning' | 'success' | 'info';
  text: string;
  aa?: boolean;
  strike?: boolean;
} {
  return TICKET_STATUS_META[row.status ?? ''] ?? { type: 'info', text: row.status ?? '—' };
}

/* ---------- 行选中与右列票务详情 ---------- */
const selectedTicket = ref<QueueTicketVO | null>(null);

function onSelectRow(row: QueueTicketVO): void {
  selectedTicket.value = row;
}

/* ---------- 行内动作：叫号 / 过号 / 重呼（低风险档单击直达 §5.2） ---------- */
/** 行动作在途标志：三动作互斥（同票同时至多一个可发），共用一标志全覆盖 */
const acting = ref(false);

/**
 * 叫号（WAITING 行）：按行诊区+医生出网叫队首；叫中票以提示回显（队首可能与所点行不同——
 * 叫号语义是「叫出队首」而非指定票，与后端 pollTop CAS 一致），成功后重拉快照。
 */
async function onCall(row: QueueTicketVO): Promise<void> {
  if (acting.value) {
    return;
  }
  acting.value = true;
  try {
    const called = await callNext({ deptCode: deptCode.value, doctorId: row.doctorId ?? '' });
    void ElMessage.success(
      called === null ? '队列暂无可叫票据' : `已叫号：${called.ticketNo ?? ''}`,
    );
    await refreshSnapshot();
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    acting.value = false;
  }
}

/** 过号（CALLED 行，§5.2 中风险档：confirm 带回显摘要） */
async function onPass(row: QueueTicketVO): Promise<void> {
  if (acting.value) {
    return;
  }
  acting.value = true;
  try {
    try {
      await ElMessageBox.confirm(
        `即将为 ${row.ticketNo ?? ''} ${row.patientName ?? ''} 过号（降级重排不改号），确认？`,
        '过号确认',
      );
    } catch {
      return;
    }
    await passTicket(row.id ?? '');
    void ElMessage.success('已过号');
    await refreshSnapshot();
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    acting.value = false;
  }
}

/** 重呼（PASSED 行，中风险档：confirm 带回显摘要） */
async function onRecall(row: QueueTicketVO): Promise<void> {
  if (acting.value) {
    return;
  }
  acting.value = true;
  try {
    try {
      await ElMessageBox.confirm(
        `即将重呼 ${row.ticketNo ?? ''} ${row.patientName ?? ''}，确认？`,
        '重呼确认',
      );
    } catch {
      return;
    }
    await recallTicket(row.id ?? '');
    void ElMessage.success('重呼完成');
    await refreshSnapshot();
  } catch {
    // 失败弹错归响应拦截器
  } finally {
    acting.value = false;
  }
}

/* ---------- 分诊处置（调级/转队列/二次分诊，风险分档 §5.2） ---------- */
/** 处置动作词表（与后端 TriageAction 枚举同源） */
const ADJUST_ACTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'LEVEL_ADJUST', label: '调级' },
  { code: 'QUEUE_TRANSFER', label: '转队列' },
  { code: 'RE_TRIAGE', label: '二次分诊（定医生）' },
];

const adjustAction = ref('LEVEL_ADJUST');
const adjustLevel = ref(3);
const targetQueue = ref('');
const targetDoctorId = ref('');
/** 动作理由（W-29 D-9 契约消费：落 triage_record.reason 供质控回溯；调级必填其余选填） */
const adjustReason = ref('');
const adjusting = ref(false);

/** 分诊级别选项（1 危 → 4 普，色值语义见级别徽标） */
const LEVEL_OPTIONS = [1, 2, 3, 4];

/**
 * 分诊处置提交：调级=中风险（confirm 带回显摘要）；转队列=高风险（danger 确认，目标队列必填
 * 前置拦截）；二次分诊=中风险（目标医生必填前置拦截）。调级理由为 LEVEL_ADJUST 必填（前端先
 * 校验空值禁提交零出网，与后端服务层同语义双保险），其余动作选填透传留痕。成功后重拉快照。
 */
async function onAdjust(): Promise<void> {
  if (adjusting.value || selectedTicket.value === null) {
    return;
  }
  const row = selectedTicket.value;
  if (adjustAction.value === 'QUEUE_TRANSFER' && targetQueue.value.trim() === '') {
    void ElMessage.warning('请填写目标诊区编码');
    return;
  }
  if (adjustAction.value === 'RE_TRIAGE' && targetDoctorId.value.trim() === '') {
    void ElMessage.warning('请填写目标医生 ID');
    return;
  }
  // 调级理由前置校验（D-9 呈现面）：空白即拦截在确认弹窗之前，违规零出网
  const reason = adjustReason.value.trim();
  if (adjustAction.value === 'LEVEL_ADJUST' && reason === '') {
    void ElMessage.warning('请填写调级理由');
    return;
  }
  adjusting.value = true;
  try {
    // 回显摘要（§4.4 禁裸确认）：按动作组装「即将为 X 做 Y」语义
    const summary =
      adjustAction.value === 'LEVEL_ADJUST'
        ? `即将为 ${row.ticketNo ?? ''} ${row.patientName ?? ''} 调至 ${TRIAGE_LEVEL_LABELS[adjustLevel.value] ?? adjustLevel.value}，确认？`
        : adjustAction.value === 'QUEUE_TRANSFER'
          ? `即将把 ${row.ticketNo ?? ''} ${row.patientName ?? ''} 转至诊区 ${targetQueue.value.trim()}（跨诊区高风险操作），确认？`
          : `即将把 ${row.ticketNo ?? ''} ${row.patientName ?? ''} 二次分诊至医生 ${targetDoctorId.value.trim()}，确认？`;
    try {
      await ElMessageBox.confirm(summary, '分诊处置确认', {
        type: adjustAction.value === 'QUEUE_TRANSFER' ? 'warning' : 'info',
        confirmButtonText: adjustAction.value === 'QUEUE_TRANSFER' ? '确认转队列' : '确认调整',
        // 转队列跨诊区属 §5.2 高风险档：确认按钮 danger 红样式承载不可逆警示
        confirmButtonClass:
          adjustAction.value === 'QUEUE_TRANSFER' ? 'el-button--danger' : undefined,
      });
    } catch {
      return;
    }
    await adjustTriage({
      visitId: row.visitId ?? '',
      action: adjustAction.value,
      triageLevel: adjustAction.value === 'LEVEL_ADJUST' ? adjustLevel.value : undefined,
      targetQueue: adjustAction.value === 'QUEUE_TRANSFER' ? targetQueue.value.trim() : undefined,
      doctorId: adjustAction.value === 'RE_TRIAGE' ? targetDoctorId.value.trim() : undefined,
      // 理由留痕出网（D-9）：调级必填已前置拦截，其余动作空串不携带（选填语义）
      reason: reason === '' ? undefined : reason,
    });
    void ElMessage.success('分诊处置完成');
    await refreshSnapshot();
  } catch {
    // 失败弹错归响应拦截器；表单驻留供重试
  } finally {
    adjusting.value = false;
  }
}

/** 轮询状态点提示文字（§8.2：点旁 12px「每 5 秒自动刷新」+ 三态补充语义） */
const pollText = computed(() => {
  if (pollHealth.value === 'paused') {
    return '页面隐藏已暂停';
  }
  if (pollHealth.value === 'error') {
    return '上次刷新失败，自动重试中';
  }
  return '每 5 秒自动刷新';
});

/* ---------- 快捷键（§5.3 分诊台档：Alt+R 叫出队首） ---------- */
/** 快捷键句柄（onMounted 注册 / onBeforeUnmount 移除，§5.3 实现口径） */
function onHotkeyKeydown(event: KeyboardEvent): void {
  if (!event.altKey || event.key.toLowerCase() !== 'r') {
    return;
  }
  // 输入控件聚焦时让位（§5.3「输入框聚焦时快捷键让位」条款）
  const target = event.target as HTMLElement | null;
  if (target !== null && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
    return;
  }
  // 在途互斥（报到/行动作/处置任一在途不叠加触发）
  if (checkingIn.value || acting.value || adjusting.value) {
    return;
  }
  const firstWaiting = tickets.value.find((row) => row.status === 'WAITING');
  if (firstWaiting === undefined) {
    return;
  }
  event.preventDefault();
  void onCall(firstWaiting);
}

onMounted(() => {
  window.addEventListener('keydown', onHotkeyKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onHotkeyKeydown);
});
</script>

<template>
  <div class="fuy-page triage-board">
    <!-- 操作条 56px 常驻（§3.2）：报到输入 240px + 优先因子（报到参数，与报到钮同域）+
         报到钮 + 诊区切换 160px + Alt+R kbd 提示（§5.3）+ 轮询状态点 -->
    <div class="triage-board-toolbar">
      <el-input
        v-model="visitIdInput"
        class="triage-board-visit-input"
        placeholder="就诊号（O+yyyyMMdd+5 位流水）"
        autofocus
        @keyup.enter="onCheckIn"
      />
      <!-- 老幼残优先因子：报到请求参数（checkIn.priorityFactors），与报到动作同域呈现；
           原置于右列处置表单受 selectedTicket 禁用态误困（Task 16 归位修正） -->
      <el-checkbox-group
        v-model="priorityFactors"
        class="triage-board-factors"
        aria-label="报到优先因子"
      >
        <el-checkbox
          v-for="factor in PRIORITY_FACTOR_OPTIONS"
          :key="factor.code"
          :value="factor.code"
          >{{ factor.label }}</el-checkbox
        >
      </el-checkbox-group>
      <el-button type="primary" :loading="checkingIn" :disabled="checkingIn" @click="onCheckIn"
        >分诊报到</el-button
      >
      <el-select v-model="deptCode" class="triage-board-dept" @change="onDeptChange">
        <el-option label="内科（DEPT-INT）" value="DEPT-INT" />
        <el-option label="外科（DEPT-SUR）" value="DEPT-SUR" />
        <el-option label="儿科（DEPT-PED）" value="DEPT-PED" />
      </el-select>
      <span class="triage-board-kbd" aria-hidden="true">
        <kbd>Alt</kbd>+<kbd>R</kbd> 叫出队首
      </span>
      <span class="triage-board-poll">
        <span class="triage-board-poll-dot" :class="`is-${pollHealth}`" aria-hidden="true"></span>
        <span class="triage-board-poll-text">{{ pollText }}</span>
      </span>
    </div>

    <el-row :gutter="16" class="fuy-stagger">
      <!-- 左：队列快照表（17/7 分栏，列宽照 §3.2） -->
      <el-col :md="24" :lg="17" :style="{ '--fuy-stagger-index': 0 }">
        <el-card>
          <template #header>候诊队列（{{ deptCode }}）</template>
          <!-- 遮罩仅首拉（§7.1 轮询无闪烁）：后续 5s 轮询静默 merge，不再整表遮罩 -->
          <div v-loading="snapshotBooting" class="triage-board-table-wrap">
            <el-table
              :data="tickets"
              class="fuy-dense"
              row-key="id"
              highlight-current-row
              @row-click="onSelectRow"
            >
              <el-table-column prop="ticketNo" label="票号" width="80">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.ticketNo }}</span>
                </template>
              </el-table-column>
              <!-- 列宽照 §3.2 结构树（姓名 100 / 级别徽标 64 / 优先级 80）：Task 13 自定 140/90
                   回归设计值；级别徽标为 W-29 D-2 契约回补列（原「禁虚构契约」注记随出网字段在位删除） -->
              <el-table-column prop="patientName" label="姓名" width="100" />
              <el-table-column label="级别" width="64">
                <template #default="{ row }">
                  <span
                    v-if="triageBadgeClass(row.triageLevel) !== null"
                    class="fuy-triage-badge"
                    :class="triageBadgeClass(row.triageLevel)"
                    >{{ TRIAGE_LEVEL_LABELS[row.triageLevel ?? 0] ?? '—' }}</span
                  >
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column prop="priorityScore" label="优先级" width="80" align="right">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.priorityScore }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <!-- 状态 tag 流转（§6.5）：120ms out-in，:key=状态值 -->
                  <Transition name="fuy-tag-flip" mode="out-in">
                    <el-tag
                      :key="row.status"
                      size="small"
                      :type="statusMeta(row).type"
                      :class="{
                        'fuy-tag-aa': statusMeta(row).aa === true,
                        'fuy-tag-strike': statusMeta(row).strike === true,
                      }"
                      >{{ statusMeta(row).text }}</el-tag
                    >
                  </Transition>
                </template>
              </el-table-column>
              <el-table-column label="等待" width="96" align="right">
                <template #default="{ row }">
                  <span class="fuy-num" :class="{ 'is-long-wait': isWaitLong(row) }"
                    >{{ waitingMinutes(row) }} 分钟</span
                  >
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200">
                <template #default="{ row }">
                  <!-- 操作按钮 size=small 间距 8px（§8.2）；三动作按票据状态互斥启停 -->
                  <div class="triage-board-actions">
                    <el-button
                      size="small"
                      type="primary"
                      link
                      :disabled="acting || row.status !== 'WAITING'"
                      @click.stop="onCall(row)"
                      >叫号</el-button
                    >
                    <el-button
                      size="small"
                      type="warning"
                      link
                      :disabled="acting || row.status !== 'CALLED'"
                      @click.stop="onPass(row)"
                      >过号</el-button
                    >
                    <el-button
                      size="small"
                      type="info"
                      link
                      :disabled="acting || row.status !== 'PASSED'"
                      @click.stop="onRecall(row)"
                      >重呼</el-button
                    >
                  </div>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty :image-size="72" description="当前诊区候诊队列为空" />
              </template>
            </el-table>
          </div>
        </el-card>
      </el-col>

      <!-- 右：票务详情 + 分诊处置（stagger index 1） -->
      <el-col :md="24" :lg="7" :style="{ '--fuy-stagger-index': 1 }">
        <el-card class="triage-board-side-card">
          <template #header>票务详情</template>
          <el-descriptions v-if="selectedTicket !== null" :column="1" border size="small">
            <el-descriptions-item label="票号">
              <span class="fuy-num">{{ selectedTicket.ticketNo }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="姓名">{{
              selectedTicket.patientName
            }}</el-descriptions-item>
            <el-descriptions-item label="就诊号">
              <span class="fuy-num">{{ selectedTicket.visitId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">{{
              statusMeta(selectedTicket).text
            }}</el-descriptions-item>
            <el-descriptions-item label="已叫次数">
              <span class="fuy-num">{{ selectedTicket.calledCount ?? 0 }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else :image-size="72" description="点击队列行查看票据详情" />
        </el-card>

        <el-card class="triage-board-side-card">
          <template #header>分诊处置</template>
          <el-form label-position="right" label-width="96px" :disabled="selectedTicket === null">
            <el-form-item label="处置动作">
              <el-select v-model="adjustAction">
                <el-option
                  v-for="item in ADJUST_ACTIONS"
                  :key="item.code"
                  :label="item.label"
                  :value="item.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="adjustAction === 'LEVEL_ADJUST'" label="目标级别">
              <el-select v-model="adjustLevel">
                <el-option
                  v-for="level in LEVEL_OPTIONS"
                  :key="level"
                  :label="`${TRIAGE_LEVEL_LABELS[level]}（${level}）`"
                  :value="level"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="adjustAction === 'QUEUE_TRANSFER'" label="目标诊区">
              <el-input v-model="targetQueue" placeholder="目标诊区编码" />
            </el-form-item>
            <el-form-item v-if="adjustAction === 'RE_TRIAGE'" label="目标医生">
              <el-input v-model="targetDoctorId" placeholder="目标医生 ID" />
            </el-form-item>
            <!-- 动作理由（W-29 D-9 契约消费）：落 triage_record.reason 质控回溯列；调级必填
                 前端先校验，转队列/二次分诊选填；maxLength=255 与列宽 VARCHAR(255) 对齐 -->
            <el-form-item label="理由">
              <el-input
                v-model="adjustReason"
                type="textarea"
                :rows="2"
                maxlength="255"
                placeholder="动作理由（调级必填，≤255 字）"
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="adjusting"
                :disabled="adjusting || selectedTicket === null"
                @click="onAdjust"
                >提交处置</el-button
              >
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 操作条 56px 常驻（§3.2/§8.2） */
.triage-board-toolbar {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  min-height: 56px;
}
.triage-board-visit-input {
  width: 240px;
}
.triage-board-dept {
  width: 160px;
}

/* 报到优先因子组（checkIn.priorityFactors 参数域）：checkbox 间距收紧贴合同条控件节奏 */
.triage-board-factors {
  display: inline-flex;
  align-items: center;
}

/* 快捷键 kbd 提示（§5.3：kbd 底 var(--el-fill-color) 圆角 2px、12px 字号） */
.triage-board-kbd {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.triage-board-kbd kbd {
  padding: 1px 5px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-sm);
  background: var(--el-fill-color);
  font-family: inherit;
  font-size: var(--fuy-font-size-xs);
  color: var(--el-text-color-regular);
}

/* 轮询状态点三色：绿=正常、灰=暂停、红=刷新失败（点旁 12px 文字） */
.triage-board-poll {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-left: auto;
}
.triage-board-poll-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-color-success);
}
.triage-board-poll-dot.is-paused {
  background: var(--el-text-color-disabled);
}
.triage-board-poll-dot.is-error {
  background: var(--el-color-danger);
}
.triage-board-poll-text {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.triage-board-table-wrap {
  min-height: 240px;
}
.triage-board-side-card {
  margin-bottom: var(--fuy-space-3);
}
.triage-board-actions {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 等待 ≥30 分钟转预警色（§8.2：文字色变化，无动画） */
.is-long-wait {
  color: var(--fuy-color-warning-text);
}
</style>
