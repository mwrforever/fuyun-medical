<script setup lang="ts">
// 告警规则页（/iot/alarm-rules，M14 FU-M14-08 告警引擎前端面，暖纸卷宗重排=丙-4 蓝图）：
// 门牌页首（衬线标题 + 活跃告警印泥朱胶囊——前端自告警行集过滤 ACTIVE 派生零出网 + 批注行
// 「谁·时刻」）→ 两栏主工作区（左 14 规则列表卷宗卡——三类源徽标 + 触发口径摘要列保留；
// 右 10 规则表单卡——按「触发口径/通知口径」两分区以 .fuy-sign-divider 文书分界重组：上段=
// 类型+设备+三口径分叉字段（既有 v-if 保留），下段=等级+升级时限+启用开关）→ 全宽告警处置
// 卡置底（等级徽标 CRITICAL=印泥朱实底强状态；确认/关闭闭环接口与 M05 工作台、PDA 共用；
// 原 hint「活跃或已确认均可关」语义落卡头副注不丢）+ 模拟回放弹窗（历史遥测回放验证规则
// 效果）+ 告警关闭弹窗（原因强制）。
// 换脸不换业务：三口径分叉 v-if/必填校验/EX-46 版本冲突比对/回放动态近 24h 默认窗/告警确认
// 关闭链零变动；全部写操作自带在途守卫（入口早退先于一切 await）+ 数值输入显式校验（禁裸
// parse）；失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError 兜底
// 展示；成功反馈=钤印（已保存/已确认/已关闭，G2 基线——钤印永不承失败）；删除维持
// ElMessageBox 中档确认与既有成功文案。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  alarmRules,
  alarms,
  ALARM_LEVEL_LABELS,
  ALARM_STATUS_LABELS,
  RULE_TYPE_LABELS,
} from '@/api/iot';
import type { AlarmRuleVO, AlarmVO, SimulateResultVO } from '@/api/iot';
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

/* ==================== 规则列表 ==================== */
const rules = ref<AlarmRuleVO[]>([]);

/** 页脚数据时点（页脚批注「数据时点」真值；成功分支回填，失败驻留旧时点——只叙事实） */
const loadedAt = ref<string | null>(null);

/** 加载规则列表（全量直出，前端按返回序展示）：loading 骨架经 useAsyncTask 收拢（EX-42
 * 范式迁移，行为与迁移前一致——失败弹错归响应拦截器；驻留旧清单；成功分支回填页脚数据时点） */
const { loading: rulesLoading, run: loadRules } = useAsyncTask(async () => {
  rules.value = (await alarmRules.list()) ?? [];
  loadedAt.value = new Date().toISOString();
});

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const loadedAtLabel = computed(() => (loadedAt.value === null ? '—' : formatTime(loadedAt.value)));

/** 规则类型中文词表反查（类型列徽标） */
function ruleTypeLabel(code: string | undefined): string {
  return RULE_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 规则类型徽标状态类（fuy-rule-tag--{type} 契约类，色值经语义 token 承载） */
function ruleTypeClass(code: string | undefined): string {
  return `fuy-rule-tag--${(code ?? '').toLowerCase()}`;
}

/** 触发口径摘要（类型列旁的条件展示：阈值口径/离线时长/透传指标） */
function ruleDigest(row: AlarmRuleVO): string {
  if (row.ruleType === 'THRESHOLD') {
    return `${row.metricCode ?? ''} ${row.compareOp ?? ''} ${row.thresholdValue ?? ''}，持续 ${row.durationSecs ?? 0}s`;
  }
  if (row.ruleType === 'OFFLINE') {
    return `离线 ${row.offlineSecs ?? 0}s 触发`;
  }
  return row.metricCode ?? '—';
}

/* ==================== 活跃告警列表 ==================== */

/** 首拉落地标记（门牌活跃计数胶囊的骨架/内容切换：落地前以静态骨架占位禁伪 0 值） */
const alarmsLoaded = ref(false);

/** 加载告警列表（全状态直出，活跃行暴露确认/关闭入口）：页码/行集/加载态经 usePagedList
 * 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错归响应拦截器；
 * 驻留旧清单；成功分支置首拉落地标记） */
const {
  rows: alarmRows,
  loading: alarmsLoading,
  fetch: loadAlarms,
} = usePagedList({
  params: () => ({}),
  fetcher: ({ page, size }) => alarms.list({ page, size }),
  pageSize: 50,
  onSuccess: () => {
    alarmsLoaded.value = true;
  },
});

/** 活跃告警计数（蓝图丙-4.2：门牌印泥朱胶囊——前端自已加载行集过滤 ACTIVE 派生，零出网；
 * 确认/关闭闭环重拉后自然派生更新，活跃告警数免扫表） */
const activeAlarmCount = computed(
  () => alarmRows.value.filter((row) => row.status === 'ACTIVE').length,
);

/** 门牌刷新联动：规则与告警两列表一并重取（页脚「手动刷新取数」语义） */
function refreshAll(): void {
  void loadRules();
  void loadAlarms();
}

/** 门牌刷新钮在途态（两列表任一在途即亮，防重复击发零出网双发） */
const pageRefreshing = computed(() => rulesLoading.value || alarmsLoading.value);

/** 告警等级中文词表反查（等级列徽标） */
function levelLabel(code: string | undefined): string {
  return ALARM_LEVEL_LABELS[code ?? ''] ?? code ?? '—';
}

/** 告警等级徽标状态类（fuy-alarm-tag--{level} 契约类，色值经语义 token 承载） */
function levelClass(code: string | undefined): string {
  return `fuy-alarm-tag--${(code ?? '').toLowerCase()}`;
}

/** 告警状态中文词表反查（状态列） */
function alarmStatusLabel(code: string | undefined): string {
  return ALARM_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/* ==================== 规则表单（新建/编辑） ==================== */
const savingRule = ref(false);
/** 编辑中的规则 id（空串=新建态） */
const editingRuleId = ref('');
/** 编辑基线版本（EX-46/FE-A2-08 版本比对锚点）：openEdit 时锚定远端 updatedAt，
 * 保存前重拉比对——偏离基线即他人已改，冲突确认防无感覆盖（契约无 If-Match/版本号
 * 入参，前端以确认提示为最小防覆盖实现） */
let editingBaselineUpdatedAt: string | null = null;
const ruleForm = ref({
  ruleName: '',
  ruleType: 'THRESHOLD',
  deviceId: '',
  metricCode: '',
  compareOp: '>',
  thresholdValue: '',
  durationSecs: '',
  recoveryBand: '',
  silenceWindowSecs: '',
  offlineSecs: '',
  alarmLevel: 'WARNING',
  escalateAfterSecs: '',
  enabled: true,
});

/** 规则表单默认态（新建重置与保存成功清空共用） */
function emptyRuleForm() {
  return {
    ruleName: '',
    ruleType: 'THRESHOLD',
    deviceId: '',
    metricCode: '',
    compareOp: '>',
    thresholdValue: '',
    durationSecs: '',
    recoveryBand: '',
    silenceWindowSecs: '',
    offlineSecs: '',
    alarmLevel: 'WARNING',
    escalateAfterSecs: '',
    enabled: true,
  };
}

/** 编辑回填（数值字段以字符串承载回表单，提交时统一校验转换） */
function openEdit(row: AlarmRuleVO): void {
  editingRuleId.value = row.id ?? '';
  // 版本比对锚点（EX-46/FE-A2-08）：以打开时刻的远端 updatedAt 为基线
  editingBaselineUpdatedAt = row.updatedAt ?? null;
  ruleForm.value = {
    ruleName: row.ruleName ?? '',
    ruleType: row.ruleType ?? 'THRESHOLD',
    deviceId: row.deviceId ?? '',
    metricCode: row.metricCode ?? '',
    compareOp: row.compareOp ?? '>',
    thresholdValue: row.thresholdValue === undefined ? '' : String(row.thresholdValue),
    durationSecs: row.durationSecs === undefined ? '' : String(row.durationSecs),
    recoveryBand: row.recoveryBand === undefined ? '' : String(row.recoveryBand),
    silenceWindowSecs: row.silenceWindowSecs === undefined ? '' : String(row.silenceWindowSecs),
    offlineSecs: row.offlineSecs === undefined ? '' : String(row.offlineSecs),
    alarmLevel: row.alarmLevel ?? 'WARNING',
    escalateAfterSecs: row.escalateAfterSecs === undefined ? '' : String(row.escalateAfterSecs),
    enabled: row.enabled ?? true,
  };
}

/**
 * 数值输入显式校验转换（禁裸 parse）：空串返回 undefined（可空字段）；非数字警告并返回
 * null（调用方中止提交）；合法数字返回数值。
 *
 * @param raw 表单原始输入（字符串承载）
 * @param label 字段中文名（警告文案用）
 */
function readNumber(raw: string, label: string): number | undefined | null {
  if (raw.trim() === '') {
    return undefined;
  }
  const n = Number(raw);
  if (!Number.isFinite(n)) {
    void ElMessage.warning(`${label}需为数字`);
    return null;
  }
  return n;
}

/** 保存规则（新建 POST/编辑 PUT）：结构必填显式校验（THRESHOLD 必填持续时长/恢复带，
 * OFFLINE 必填离线时长，零出网）→ 出网 → 钤印「已保存」清表单刷新。 */
async function onSaveRule(): Promise<void> {
  if (savingRule.value) {
    return;
  }
  if (ruleForm.value.ruleName.trim() === '') {
    void ElMessage.warning('请填写规则名称');
    return;
  }
  // THRESHOLD 必填四项（指标/阈值/持续时长/恢复带）
  if (ruleForm.value.ruleType === 'THRESHOLD') {
    if (ruleForm.value.metricCode.trim() === '') {
      void ElMessage.warning('请填写指标编码');
      return;
    }
    if (ruleForm.value.thresholdValue.trim() === '') {
      void ElMessage.warning('请填写阈值');
      return;
    }
    if (ruleForm.value.durationSecs.trim() === '') {
      void ElMessage.warning('请填写持续时长（秒）');
      return;
    }
    if (ruleForm.value.recoveryBand.trim() === '') {
      void ElMessage.warning('请填写恢复带');
      return;
    }
  }
  // OFFLINE 必填离线时长
  if (ruleForm.value.ruleType === 'OFFLINE' && ruleForm.value.offlineSecs.trim() === '') {
    void ElMessage.warning('请填写离线时长（秒）');
    return;
  }
  // 数值字段显式转换校验（非数字中止提交）
  const thresholdValue = readNumber(ruleForm.value.thresholdValue, '阈值');
  if (thresholdValue === null) {
    return;
  }
  const durationSecs = readNumber(ruleForm.value.durationSecs, '持续时长');
  if (durationSecs === null) {
    return;
  }
  const recoveryBand = readNumber(ruleForm.value.recoveryBand, '恢复带');
  if (recoveryBand === null) {
    return;
  }
  const silenceWindowSecs = readNumber(ruleForm.value.silenceWindowSecs, '静默窗口');
  if (silenceWindowSecs === null) {
    return;
  }
  const offlineSecs = readNumber(ruleForm.value.offlineSecs, '离线时长');
  if (offlineSecs === null) {
    return;
  }
  const escalateAfterSecs = readNumber(ruleForm.value.escalateAfterSecs, '升级时限');
  if (escalateAfterSecs === null) {
    return;
  }
  const payload = {
    ruleName: ruleForm.value.ruleName.trim(),
    ruleType: ruleForm.value.ruleType as 'DEVICE_ALARM' | 'THRESHOLD' | 'OFFLINE',
    deviceId: ruleForm.value.deviceId.trim() === '' ? undefined : ruleForm.value.deviceId.trim(),
    metricCode:
      ruleForm.value.metricCode.trim() === '' ? undefined : ruleForm.value.metricCode.trim(),
    compareOp:
      ruleForm.value.ruleType === 'THRESHOLD' ? (ruleForm.value.compareOp as '>' | '<') : undefined,
    thresholdValue,
    durationSecs,
    recoveryBand,
    silenceWindowSecs,
    offlineSecs,
    alarmLevel: ruleForm.value.alarmLevel as 'INFO' | 'WARNING' | 'CRITICAL',
    escalateAfterSecs,
    enabled: ruleForm.value.enabled,
  };
  savingRule.value = true;
  try {
    if (editingRuleId.value === '') {
      await alarmRules.create(payload);
      // 成功反馈=钤印「已保存」（丙-4 蓝图第 4 节逐字，新建/更新共用一印）
      showStamp('已保存');
    } else {
      // 版本比对回写守卫（EX-46/FE-A2-08）：保存前重拉远端清单比对目标规则 updatedAt——
      // 偏离打开时基线=他人已改（读改写窗口冲突），确认提示防无感覆盖；取消即零出网。
      // 重拉失败放行走原保存链（失败弹错归拦截器，保存成败由后端终判）。
      const latest = await alarmRules.list().catch(() => [] as AlarmRuleVO[]);
      const remote = (latest ?? []).find((item) => item.id === editingRuleId.value);
      if (remote !== undefined && (remote.updatedAt ?? null) !== editingBaselineUpdatedAt) {
        try {
          await ElMessageBox.confirm(
            `规则「${ruleForm.value.ruleName.trim()}」已被他人修改（远端更新于 ${remote.updatedAt ?? '未知时间'}），继续保存将覆盖他人变更`,
            '版本冲突提醒',
            { type: 'warning', confirmButtonText: '仍要保存', cancelButtonText: '取消' },
          );
        } catch {
          // 取消覆盖：零出网，表单驻留可复核后再决定
          return;
        }
      }
      await alarmRules.update(editingRuleId.value, payload);
      showStamp('已保存');
    }
    editingRuleId.value = '';
    ruleForm.value = emptyRuleForm();
    await loadRules();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    savingRule.value = false;
  }
}

/* ==================== 规则删除 ==================== */
const removingRuleId = ref('');

/** 规则删除（中档确认；停用建议优先走 enabled 开关，删除仅限误建规则） */
async function onRemoveRule(row: AlarmRuleVO): Promise<void> {
  if (removingRuleId.value !== '') {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `即将删除规则「${row.ruleName ?? ''}」，删除后不可恢复，确认？`,
      '规则删除确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  removingRuleId.value = row.id ?? '';
  try {
    await alarmRules.remove(row.id ?? '');
    // 删除成功反馈维持既有 message 通道（域丙钤印族不含「已删除」，钤印只承蓝图枚举的成功确认）
    void ElMessage.success(`规则已删除：${row.ruleName ?? ''}`);
    await loadRules();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    removingRuleId.value = '';
  }
}

/* ==================== 模拟回放弹窗 ==================== */
const simulateVisible = ref(false);
const simulating = ref(false);
/** 回放目标规则（弹窗上下文锚点） */
const simulateTarget = ref<AlarmRuleVO | null>(null);
const simulateForm = ref({ from: '', to: '' });
const simulateResult = ref<SimulateResultVO | null>(null);

/** datetime-local 控件原生展示串（本地时区 YYYY-MM-DDTHH:mm；native Date 计算与
 * DischargeManageView datetime-local 先例同款口径） */
function toDatetimeLocal(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 打开模拟回放弹窗（默认动态近一日窗口：截止=当前时刻、起始=前推 24 小时——
 * 逐次打开按当下重算，不落固定日期字面量，防默认窗过期致回放恒未命中） */
function openSimulate(row: AlarmRuleVO): void {
  simulateTarget.value = row;
  simulateResult.value = null;
  const now = new Date();
  simulateForm.value = {
    from: toDatetimeLocal(new Date(now.getTime() - 24 * 3600 * 1000)),
    to: toDatetimeLocal(now),
  };
  simulateVisible.value = true;
}

/** 回放时间窗摘要行（弹窗上下文回显） */
const simulateSummary = computed(() => {
  const row = simulateTarget.value;
  if (row === null) {
    return '';
  }
  return `规则「${row.ruleName ?? ''}」按历史遥测回放验证触发效果`;
});

/** 开始回放：时间窗显式校验（缺项零出网）→ 出网 → 结果渲染。 */
async function onSimulate(): Promise<void> {
  if (simulating.value) {
    return;
  }
  if (simulateForm.value.from === '' || simulateForm.value.to === '') {
    void ElMessage.warning('请选择回放时间窗');
    return;
  }
  simulating.value = true;
  try {
    simulateResult.value = await alarmRules.simulate(simulateTarget.value?.id ?? '', {
      from: simulateForm.value.from,
      to: simulateForm.value.to,
    });
  } catch (error) {
    surfaceBizError(error);
  } finally {
    simulating.value = false;
  }
}

/* ==================== 告警确认与关闭 ==================== */
const ackingNo = ref('');
/** 关闭弹窗态（原因强制） */
const closeVisible = ref(false);
const closing = ref(false);
const closeTarget = ref<AlarmVO | null>(null);
const closeReason = ref('');

/** 告警确认（ACTIVE→ACKNOWLEDGED，确认人由后端取会话承载）；成功反馈=钤印「已确认」 */
async function onAcknowledge(row: AlarmVO): Promise<void> {
  if (ackingNo.value !== '') {
    return;
  }
  ackingNo.value = row.alarmNo ?? '';
  try {
    await alarms.acknowledge(row.alarmNo ?? '');
    // 成功反馈=钤印「已确认」（丙-4 蓝图第 4 节逐字；确认人留痕归后端）
    showStamp('已确认');
    await loadAlarms();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    ackingNo.value = '';
  }
}

/** 打开关闭弹窗 */
function openClose(row: AlarmVO): void {
  closeTarget.value = row;
  closeReason.value = '';
  closeVisible.value = true;
}

/** 确认关闭：原因强制显式校验（空原因零出网）→ 出网 → 钤印「已关闭」关窗刷新。 */
async function onClose(): Promise<void> {
  if (closing.value) {
    return;
  }
  if (closeReason.value.trim() === '') {
    void ElMessage.warning('请填写关闭原因');
    return;
  }
  closing.value = true;
  try {
    await alarms.close(closeTarget.value?.alarmNo ?? '', { reason: closeReason.value.trim() });
    // 成功反馈=钤印「已关闭」（丙-4 蓝图第 4 节逐字；关闭原因留痕归后端）
    showStamp('已关闭');
    closeVisible.value = false;
    await loadAlarms();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    closing.value = false;
  }
}

/* ==================== 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ==================== */
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=产品与物模型页先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认——已保存/已确认/已关闭；失败与业务拒绝归 message 批注条/
 * 拦截器，钤印永不承失败） */
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
  void loadRules();
  void loadAlarms();
});
</script>

<template>
  <div class="fuy-page alarm-rule">
    <!-- 页内级联容器（契约 ⑦.4）：页面根节点禁挂 .fuy-stagger——stagger 挂本层区块
         （product-flow 同构形态），本层承接 flex+gap 承担纵排与区块间距；
         级联序：门牌 0 / 两栏 1 / 告警卡 2 / 页脚 3 -->
    <div class="fuy-stagger alarm-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（活跃告警印泥朱
           胶囊=前端派生零出网 + 刷新钮）+「谁·时刻」批注行 + 2px 墨规收底（脸归全局族） -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">告警规则</h1>
          <span class="fuy-page-en" aria-hidden="true">ALARM RULES</span>
        </div>
        <div class="fuy-page-status">
          <!-- 活跃告警计数（蓝图丙-4.2：前端自告警行集过滤 ACTIVE 派生零出网）：
               首拉未落地以静态骨架占位（禁伪 0 值），落地后恒显诚实计数（含 0） -->
          <span v-if="!alarmsLoaded" class="fuy-sk alarm-count-sk" aria-hidden="true"></span>
          <span v-else class="fuy-status-pill fuy-status-pill--danger"
            >活跃告警 {{ activeAlarmCount }}</span
          >
          <el-button :loading="pageRefreshing" @click="refreshAll">刷新</el-button>
        </div>
        <p class="fuy-page-note">
          三类规则源 · 模拟回放验证后再上线 · 告警闭环：确认/关闭 · 签认人 {{ operatorName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- ② 两栏 lg 14/10（既有）：左规则列表卷宗卡 / 右规则表单卡（两分区重组） -->
      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：规则列表卡（.fuy-dense 密排挂卡容器构成后代关系；全量直出既有） -->
        <el-col :md="24" :lg="14">
          <section class="fuy-card fuy-dense rule-list-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">规则列表</h2>
              <span class="rule-list-total"
                >共 <span class="fuy-num">{{ rules.length }}</span> 条</span
              >
            </header>
            <div v-loading="rulesLoading" class="fuy-card-body">
              <el-table v-if="rules.length > 0" :data="rules" class="fuy-dense">
                <el-table-column label="规则名称" min-width="140">
                  <template #default="{ row }">
                    {{ row.ruleName }}
                  </template>
                </el-table-column>
                <el-table-column label="规则源" width="120">
                  <template #default="{ row }">
                    <el-tag size="small" class="fuy-tag-aa" :class="ruleTypeClass(row.ruleType)">
                      {{ ruleTypeLabel(row.ruleType) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="触发口径" min-width="180">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ ruleDigest(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="等级" width="70">
                  <template #default="{ row }">
                    {{ levelLabel(row.alarmLevel) }}
                  </template>
                </el-table-column>
                <el-table-column label="启用" width="60">
                  <template #default="{ row }">
                    {{ row.enabled ? '是' : '否' }}
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="200" class-name="fuy-ops-8">
                  <template #default="{ row }">
                    <!-- 规则编辑/删除（PR-4F #32，IOT_ADMIN 绑定）v-perm 直挂——无码 DOM 移除
                         （D-34）；「模拟回放」入口与右栏规则表单按盘点口径不挂码（回放与建档
                         出网口另行把守）；删除在途 :loading 数据态与权限判定两层正交 -->
                    <el-button link type="primary" size="small" @click="openSimulate(row)"
                      >模拟回放</el-button
                    >
                    <el-button
                      v-perm="'iot:alarm-rule:btn:manage'"
                      link
                      type="primary"
                      size="small"
                      @click="openEdit(row)"
                      >编辑</el-button
                    >
                    <el-button
                      v-perm="'iot:alarm-rule:btn:manage'"
                      link
                      type="danger"
                      size="small"
                      :loading="removingRuleId === row.id"
                      @click="onRemoveRule(row)"
                      >删除</el-button
                    >
                  </template>
                </el-table-column>
              </el-table>
              <!-- 空态脸（G6/契约 ⑥）：主句「暂无告警规则」+ 说明给下一步；默认纸箱插画在
                   .fuy-page 子树由全局段压制，本页走自绘空态脸零插画 -->
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无告警规则</p>
                <p class="fuy-empty-hint">从右侧表单新建规则，保存后经模拟回放验证再上线。</p>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：规则表单卡（新建/编辑共用；按「触发口径/通知口径」两分区以「签」字分界
             重组——蓝图丙-4.2 PM 重排裁决：13 字段单列长滚拆两段文书，分叉字段仍归上段） -->
        <el-col :md="24" :lg="10">
          <section class="fuy-card rule-form-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">
                {{ editingRuleId === '' ? '新建规则' : `编辑规则（${editingRuleId}）` }}
              </h2>
            </header>
            <div class="fuy-card-body">
              <el-form label-position="top" size="small" class="fuy-form">
                <!-- 上段：触发口径（名称/类型/设备 + 三口径分叉字段，既有 v-if 保留）；段标统一
                     挂全局类 .fuy-section-title（D-30 段标口径统一：契约 §④.1 黑体默认面） -->
                <div class="rule-form-section">
                  <p class="fuy-section-title">触发口径</p>
                  <label class="rule-field-label">规则名称（必填）</label>
                  <input
                    v-model="ruleForm.ruleName"
                    class="rule-input"
                    placeholder="如：心率超阈告警"
                    aria-label="规则名称"
                  />
                  <div class="rule-field-grid">
                    <div class="rule-field">
                      <label class="rule-field-label">规则类型（必填）</label>
                      <select v-model="ruleForm.ruleType" class="rule-input" aria-label="规则类型">
                        <option v-for="(label, code) in RULE_TYPE_LABELS" :key="code" :value="code">
                          {{ label }}
                        </option>
                      </select>
                    </div>
                    <div class="rule-field">
                      <label class="rule-field-label">设备 ID（可空=全设备）</label>
                      <input
                        v-model="ruleForm.deviceId"
                        class="rule-input fuy-num"
                        aria-label="设备 ID"
                      />
                    </div>
                  </div>
                  <!-- 阈值口径字段（THRESHOLD 专属；必填四项前端显式校验） -->
                  <template v-if="ruleForm.ruleType === 'THRESHOLD'">
                    <div class="rule-field-grid">
                      <div class="rule-field">
                        <label class="rule-field-label">指标编码（必填）</label>
                        <input
                          v-model="ruleForm.metricCode"
                          class="rule-input fuy-num"
                          placeholder="如：MDC_ECG_HEART_RATE"
                          aria-label="指标编码"
                        />
                      </div>
                      <div class="rule-field">
                        <label class="rule-field-label">比较符</label>
                        <select v-model="ruleForm.compareOp" class="rule-input" aria-label="比较符">
                          <option value=">">大于（＞）</option>
                          <option value="<">小于（＜）</option>
                        </select>
                      </div>
                    </div>
                    <div class="rule-field-grid">
                      <div class="rule-field">
                        <label class="rule-field-label">阈值（必填）</label>
                        <input
                          v-model="ruleForm.thresholdValue"
                          class="rule-input fuy-num"
                          aria-label="阈值"
                        />
                      </div>
                      <div class="rule-field">
                        <label class="rule-field-label">持续时长秒（必填）</label>
                        <input
                          v-model="ruleForm.durationSecs"
                          class="rule-input fuy-num"
                          aria-label="持续时长（秒）"
                        />
                      </div>
                    </div>
                    <div class="rule-field-grid">
                      <div class="rule-field">
                        <label class="rule-field-label">恢复带（必填，防抖动）</label>
                        <input
                          v-model="ruleForm.recoveryBand"
                          class="rule-input fuy-num"
                          aria-label="恢复带"
                        />
                      </div>
                      <div class="rule-field">
                        <label class="rule-field-label">静默窗口秒（可空）</label>
                        <input
                          v-model="ruleForm.silenceWindowSecs"
                          class="rule-input fuy-num"
                          aria-label="静默窗口（秒）"
                        />
                      </div>
                    </div>
                  </template>
                  <!-- 离线口径字段（OFFLINE 专属） -->
                  <template v-if="ruleForm.ruleType === 'OFFLINE'">
                    <label class="rule-field-label">离线时长秒（必填）</label>
                    <input
                      v-model="ruleForm.offlineSecs"
                      class="rule-input fuy-num"
                      aria-label="离线时长（秒）"
                    />
                  </template>
                  <!-- 透传口径字段（DEVICE_ALARM 专属） -->
                  <template v-if="ruleForm.ruleType === 'DEVICE_ALARM'">
                    <label class="rule-field-label">指标编码（可空=全部报警透传）</label>
                    <input
                      v-model="ruleForm.metricCode"
                      class="rule-input fuy-num"
                      aria-label="透传指标编码"
                    />
                  </template>
                </div>
                <!-- 「签」字分界（蓝图丙-4.2：触发口径/通知口径两分区文书分界，替代单列长滚） -->
                <div class="fuy-sign-divider rule-sign-divider" aria-hidden="true"></div>
                <!-- 下段：通知口径（等级/升级时限/启用开关 + 保存）；段标同上统一全局类 -->
                <div class="rule-form-section">
                  <p class="fuy-section-title">通知口径</p>
                  <div class="rule-field-grid">
                    <div class="rule-field">
                      <label class="rule-field-label">告警等级</label>
                      <select
                        v-model="ruleForm.alarmLevel"
                        class="rule-input"
                        aria-label="告警等级"
                      >
                        <option
                          v-for="(label, code) in ALARM_LEVEL_LABELS"
                          :key="code"
                          :value="code"
                        >
                          {{ label }}
                        </option>
                      </select>
                    </div>
                    <div class="rule-field">
                      <label class="rule-field-label">升级时限秒（可空）</label>
                      <input
                        v-model="ruleForm.escalateAfterSecs"
                        class="rule-input fuy-num"
                        aria-label="升级时限（秒）"
                      />
                    </div>
                  </div>
                  <label class="rule-enabled-label">
                    <input v-model="ruleForm.enabled" type="checkbox" aria-label="是否启用" />
                    启用（停用建议优先用开关而非删除）
                  </label>
                  <el-button
                    type="primary"
                    class="rule-submit"
                    :loading="savingRule"
                    :disabled="savingRule"
                    @click="onSaveRule"
                    >保存规则</el-button
                  >
                </div>
              </el-form>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- ③ 活跃告警处置卡（置底全宽，闭环操作面；等级徽标 CRITICAL=印泥朱实底强状态） -->
      <section class="fuy-card fuy-dense rule-alarms-card" :style="{ '--fuy-stagger-index': 2 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">告警列表</h2>
          <div class="rule-card-extra">
            <!-- 原 hint「活跃或已确认均可关」语义落卡头副注（附一.4：hint 归位不丢语义） -->
            <span class="rule-card-sub"
              >共 <span class="fuy-num">{{ alarmRows.length }}</span> 条 · 活跃或已确认均可关</span
            >
            <el-button size="small" :loading="alarmsLoading" @click="loadAlarms"
              >刷新告警</el-button
            >
          </div>
        </header>
        <div v-loading="alarmsLoading" class="fuy-card-body">
          <el-table v-if="alarmRows.length > 0" :data="alarmRows" class="fuy-dense">
            <el-table-column label="告警号" min-width="150">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.alarmNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="设备" min-width="100">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.deviceId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="等级" width="80">
              <template #default="{ row }">
                <el-tag size="small" class="fuy-tag-aa" :class="levelClass(row.alarmLevel)">
                  {{ levelLabel(row.alarmLevel) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="140">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.metricCode ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="触发值" width="80">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.triggerValue ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                {{ alarmStatusLabel(row.status) }}
              </template>
            </el-table-column>
            <el-table-column label="最后触发" width="100">
              <template #default="{ row }">
                <span class="fuy-num">{{ formatTime(row.lastTriggeredAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" class-name="fuy-ops-8">
              <template #default="{ row }">
                <!-- 告警确认/关闭（PR-4F #32 并入告警规则管理码——同族互斥两态并码为盘点
                     §1.1 既定裁定；IOT_ADMIN 绑定）v-perm 直挂——无码 DOM 移除（D-34）；
                     ACTIVE/ACKNOWLEDGED 状态机 v-if 数据态与权限判定两层正交；关闭弹窗
                     「确认关闭」随入口不可达免挂接 -->
                <el-button
                  v-if="row.status === 'ACTIVE'"
                  v-perm="'iot:alarm-rule:btn:manage'"
                  link
                  type="primary"
                  size="small"
                  :loading="ackingNo === row.alarmNo"
                  @click="onAcknowledge(row)"
                  >确认</el-button
                >
                <el-button
                  v-if="row.status === 'ACTIVE' || row.status === 'ACKNOWLEDGED'"
                  v-perm="'iot:alarm-rule:btn:manage'"
                  link
                  type="danger"
                  size="small"
                  @click="openClose(row)"
                  >关闭</el-button
                >
              </template>
            </el-table-column>
          </el-table>
          <!-- 空态脸（G6/契约 ⑥）：主句「暂无告警」+ 说明给下一步 -->
          <div v-else class="fuy-empty" role="status">
            <span class="fuy-empty-mark" aria-hidden="true">空</span>
            <p class="fuy-empty-title">暂无告警</p>
            <p class="fuy-empty-hint">当前无告警记录，可点「刷新告警」重取或稍后再看。</p>
          </div>
        </div>
      </section>

      <!-- ④ 页脚批注（G1 纵向序收尾，契约 ⑧.8）：数据时点=最近一次规则列表成功加载时点 +
           手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 3 }">
        数据时点 {{ loadedAtLabel }} · 手动刷新取数
      </footer>

      <!-- 模拟回放弹窗（历史遥测回放验证规则效果；挂 .fuy-dialog=衬线标题+头部发丝全局脸，
           字段容器挂 .fuy-form 表单脸） -->
      <el-dialog v-model="simulateVisible" title="模拟回放" width="640px" class="fuy-dialog">
        <p class="rule-simulate-summary">{{ simulateSummary }}</p>
        <div class="fuy-form">
          <div class="rule-field-grid">
            <div class="rule-field">
              <label class="rule-field-label">回放起始（必填）</label>
              <input
                v-model="simulateForm.from"
                type="datetime-local"
                class="rule-input fuy-num"
                aria-label="回放起始"
              />
            </div>
            <div class="rule-field">
              <label class="rule-field-label">回放截止（必填）</label>
              <input
                v-model="simulateForm.to"
                type="datetime-local"
                class="rule-input fuy-num"
                aria-label="回放截止"
              />
            </div>
          </div>
        </div>
        <!-- 开始回放（PR-4F #32「模拟」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
             DOM 移除（D-34），回放 POST 以本按钮为唯一出口：入口「模拟回放」按盘点口径
             不挂码，无码会话仅可开窗读摘要不可出网；:loading/:disabled 在途数据态与权限
             判定两层正交 -->
        <el-button
          v-perm="'iot:alarm-rule:btn:manage'"
          type="primary"
          size="small"
          :loading="simulating"
          :disabled="simulating"
          @click="onSimulate"
          >开始回放</el-button
        >
        <!-- 回放结果：扫描行数 + 命中触发清单 -->
        <template v-if="simulateResult !== null">
          <p class="rule-simulate-stat">
            扫描遥测 <span class="fuy-num">{{ simulateResult.scannedRows ?? '0' }}</span> 行 ·
            命中触发 <span class="fuy-num">{{ simulateResult.triggers?.length ?? 0 }}</span> 次
          </p>
          <el-table
            v-if="(simulateResult.triggers?.length ?? 0) > 0"
            :data="simulateResult.triggers"
            class="fuy-dense"
          >
            <el-table-column label="设备" min-width="100">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.deviceId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="140">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.metricCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="触发值" width="80">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.triggerValue }}</span>
              </template>
            </el-table-column>
            <el-table-column label="等级" width="70">
              <template #default="{ row }">
                {{ levelLabel(row.alarmLevel) }}
              </template>
            </el-table-column>
            <el-table-column label="触发时点" width="100">
              <template #default="{ row }">
                <span class="fuy-num">{{ formatTime(row.triggeredAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <!-- 零命中空态脸（G6）：主句保原文案语义 + 说明给下一步（可调窗重放） -->
          <div v-else class="fuy-empty" role="status">
            <span class="fuy-empty-mark" aria-hidden="true">空</span>
            <p class="fuy-empty-title">回放窗口内未命中触发</p>
            <p class="fuy-empty-hint">可调整回放时间窗后重新开始回放。</p>
          </div>
        </template>
        <template #footer>
          <el-button size="small" @click="simulateVisible = false">关闭</el-button>
        </template>
      </el-dialog>

      <!-- 告警关闭弹窗（原因强制；挂 .fuy-dialog 同上，表单容器挂 .fuy-form） -->
      <el-dialog v-model="closeVisible" title="告警关闭" width="420px" class="fuy-dialog">
        <p class="rule-simulate-summary fuy-num">
          告警 {{ closeTarget?.alarmNo ?? '' }}（{{ levelLabel(closeTarget?.alarmLevel) }}）
        </p>
        <div class="fuy-form">
          <label class="rule-field-label">关闭原因（强制）</label>
          <textarea
            v-model="closeReason"
            class="rule-input"
            rows="2"
            placeholder="如：误报，设备已重新标定"
            aria-label="关闭原因"
          ></textarea>
        </div>
        <template #footer>
          <el-button size="small" @click="closeVisible = false">取消</el-button>
          <el-button
            type="danger"
            size="small"
            :loading="closing"
            :disabled="closing"
            @click="onClose"
            >确认关闭</el-button
          >
        </template>
      </el-dialog>

      <!-- 钤印确认反馈（契约 ⑧.5：只承成功确认——已保存/已确认/已关闭；v-if 挂载制=
           产品与物模型页先例同构——防 stagger 误伤常驻泄漏，seq 递增重挂重放落章动画，
           ≤1200ms 收场） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——product-flow
   同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与区块间距
   （门牌 0 / 两栏 1 / 告警卡 2 / 页脚 3 四段级联序）由本层同值承接（.fuy-page 同构
   flex+gap），保证视觉零漂移 */
.alarm-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：活跃告警胶囊与刷新钮横排（device-flow 同构） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 活跃告警计数骨架（首拉落地前占位）：静态金洗纸胶囊形零 shimmer，定尺寸锁 CLS */
.alarm-count-sk {
  width: 88px;
  height: 24px;
  border-radius: var(--fuy-radius-full);
}

/* 列表卡头计数与告警卡头右挂工具位（卷宗卡卡头 extra 位；计数 12px 灰墨档——七页卡头
   副注统一 12px 档） */
.rule-list-total {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

.rule-card-extra {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
}

/* 告警卡头副注（原 hint「活跃或已确认均可关」语义落位）：12px 灰墨 */
.rule-card-sub {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 列表/告警卡体 CLS 锁（蓝图丙-4.6：骨架 min-height 240px + 告警卡 200px）：
   加载/空态切换零塌陷 */
.rule-list-card .fuy-card-body {
  box-sizing: border-box;
  min-height: 240px;
}

.rule-alarms-card .fuy-card-body {
  box-sizing: border-box;
  min-height: 200px;
}

/* 「签」分界与两分区呼吸间距（卡内文书分界，禁石规下沉）；两分区段标已统一挂全局类
   .fuy-section-title（D-30 段标口径统一：14px/600 浓墨黑体档归 element-plus.css 全局
   承载，原 12px 灰墨局部段标声明已删） */
.rule-sign-divider {
  margin: var(--fuy-space-3) 0;
}

/* 表单基元：native input/select/textarea 与 EP 密度口径对齐（存量页面同款）；围合取值直取
   --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边——住院域琢段统一口径，
   与卡面拉开一档），右栏表单与两弹窗（回放/关闭）共消费 */
.rule-input {
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

.rule-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.rule-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.rule-enabled-label {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  margin-bottom: var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.rule-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

.rule-simulate-summary {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

.rule-simulate-stat {
  margin: var(--fuy-space-3) 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

/* 规则三类源徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-rule-tag--device_alarm {
  border-color: var(--fuy-color-rule-device-alarm);
  color: var(--fuy-color-rule-device-alarm);
}

.fuy-rule-tag--threshold {
  border-color: var(--fuy-color-rule-threshold);
  color: var(--fuy-color-rule-threshold);
}

.fuy-rule-tag--offline {
  border-color: var(--fuy-color-rule-offline);
  color: var(--fuy-color-rule-offline);
}

/* 告警等级徽标（描边文本场景，色值复用语义 token；CRITICAL=印泥朱实底强状态——
   蓝图丙-4.2③：实底背景走 --fuy-color-danger-text 语义通道，反白字取最亮纸白语义通道
   --el-fill-color-blank（表单域最亮纸面同源），页内 scoped 零新 hex 字面量） */
.fuy-alarm-tag--info {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-alarm-tag--warning {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

.fuy-alarm-tag--critical {
  border-color: transparent;
  background: var(--fuy-color-danger-text);
  color: var(--el-fill-color-blank);
}
</style>
