<script setup lang="ts">
// 告警规则页（/iot/alarm-rules，M14 FU-M14-08 告警引擎前端面）：规则列表（三类源徽标：
// 设备报警透传/平台阈值/离线）+ 规则表单（新建/编辑；THRESHOLD 必填持续时长/恢复带与
// OFFLINE 必填离线时长为前端显式校验零出网，阈值与评估业务规则单点归后端把守）+ 模拟
// 回放弹窗（历史遥测回放验证规则效果）+ 活跃告警列表（等级徽标/确认/关闭——闭环接口
// 与 M05 工作台、PDA 共用）。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 数值输入显式校验（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError 兜底展示。
import { computed, onMounted, ref } from 'vue';
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
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

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

/* ==================== 规则列表 ==================== */
const rules = ref<AlarmRuleVO[]>([]);

/** 加载规则列表（全量直出，前端按返回序展示）：loading 骨架经 useAsyncTask 收拢（EX-42
 * 范式迁移，行为与迁移前一致——失败弹错归响应拦截器；驻留旧清单） */
const { loading: rulesLoading, run: loadRules } = useAsyncTask(async () => {
  rules.value = (await alarmRules.list()) ?? [];
});

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

/** 保存规则（新建 POST/编辑 PUT）：结构必填显式校验（THRESHOLD 必填持续时长/恢复带，
 * OFFLINE 必填离线时长，零出网）→ 出网 → 清表单刷新。 */
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
      void ElMessage.success('告警规则已创建');
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
      void ElMessage.success('告警规则已更新');
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

/* ==================== 活跃告警列表 ==================== */

/** 加载告警列表（全状态直出，活跃行暴露确认/关闭入口）：页码/行集/加载态经 usePagedList
 * 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错归响应拦截器；
 * 驻留旧清单） */
const {
  rows: alarmRows,
  loading: alarmsLoading,
  fetch: loadAlarms,
} = usePagedList({
  params: () => ({}),
  fetcher: ({ page, size }) => alarms.list({ page, size }),
  pageSize: 50,
});

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

/* ==================== 告警确认与关闭 ==================== */
const ackingNo = ref('');
/** 关闭弹窗态（原因强制） */
const closeVisible = ref(false);
const closing = ref(false);
const closeTarget = ref<AlarmVO | null>(null);
const closeReason = ref('');

/** 告警确认（ACTIVE→ACKNOWLEDGED，确认人由后端取会话承载） */
async function onAcknowledge(row: AlarmVO): Promise<void> {
  if (ackingNo.value !== '') {
    return;
  }
  ackingNo.value = row.alarmNo ?? '';
  try {
    await alarms.acknowledge(row.alarmNo ?? '');
    void ElMessage.success(`告警已确认：${row.alarmNo ?? ''}`);
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

/** 确认关闭：原因强制显式校验（空原因零出网）→ 出网 → 关窗刷新。 */
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
    void ElMessage.success(`告警已关闭：${closeTarget.value?.alarmNo ?? ''}`);
    closeVisible.value = false;
    await loadAlarms();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    closing.value = false;
  }
}

onMounted(() => {
  void loadRules();
  void loadAlarms();
});
</script>

<template>
  <div class="fuy-page alarm-rule fuy-stagger">
    <!-- 页头：标题 + 提示 + 刷新 -->
    <header class="rule-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="rule-title">告警规则</h2>
      <span class="rule-hint"
        >三类规则源 · 模拟回放验证后再上线 · 告警闭环：确认/关闭（活跃或已确认均可关）</span
      >
      <el-button :loading="rulesLoading" @click="loadRules">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：规则列表 -->
      <el-col :md="24" :lg="14">
        <el-card>
          <template #header>
            <span>规则列表（共 {{ rules.length }} 条）</span>
          </template>
          <div v-loading="rulesLoading">
            <el-table v-if="rules.length > 0" :data="rules" class="fuy-dense" size="small">
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
                  <el-button link type="primary" size="small" @click="openSimulate(row)"
                    >模拟回放</el-button
                  >
                  <el-button link type="primary" size="small" @click="openEdit(row)"
                    >编辑</el-button
                  >
                  <el-button
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
            <el-empty v-else :image-size="72" description="暂无告警规则，从右侧新建" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：规则表单（新建/编辑共用） -->
      <el-col :md="24" :lg="10">
        <el-card>
          <template #header>
            {{ editingRuleId === '' ? '新建规则' : `编辑规则（${editingRuleId}）` }}
          </template>
          <el-form label-position="top" size="small">
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
            <div class="rule-field-grid">
              <div class="rule-field">
                <label class="rule-field-label">告警等级</label>
                <select v-model="ruleForm.alarmLevel" class="rule-input" aria-label="告警等级">
                  <option v-for="(label, code) in ALARM_LEVEL_LABELS" :key="code" :value="code">
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
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- 活跃告警列表（闭环操作面） -->
    <el-card class="rule-alarms-card fuy-stagger" :style="{ '--fuy-stagger-index': 2 }">
      <template #header>
        <div class="rule-card-head">
          <span>告警列表（共 {{ alarmRows.length }} 条）</span>
          <el-button size="small" :loading="alarmsLoading" @click="loadAlarms">刷新告警</el-button>
        </div>
      </template>
      <div v-loading="alarmsLoading">
        <el-table v-if="alarmRows.length > 0" :data="alarmRows" class="fuy-dense" size="small">
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
              <el-button
                v-if="row.status === 'ACTIVE'"
                link
                type="primary"
                size="small"
                :loading="ackingNo === row.alarmNo"
                @click="onAcknowledge(row)"
                >确认</el-button
              >
              <el-button
                v-if="row.status === 'ACTIVE' || row.status === 'ACKNOWLEDGED'"
                link
                type="danger"
                size="small"
                @click="openClose(row)"
                >关闭</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="72" description="暂无告警" />
      </div>
    </el-card>

    <!-- 模拟回放弹窗 -->
    <el-dialog v-model="simulateVisible" title="模拟回放" width="640px">
      <p class="rule-simulate-summary">{{ simulateSummary }}</p>
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
      <el-button
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
          size="small"
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
        <el-empty v-else :image-size="72" description="回放窗口内未命中触发" />
      </template>
      <template #footer>
        <el-button size="small" @click="simulateVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 告警关闭弹窗（原因强制） -->
    <el-dialog v-model="closeVisible" title="告警关闭" width="420px">
      <p class="rule-simulate-summary fuy-num">
        告警 {{ closeTarget?.alarmNo ?? '' }}（{{ levelLabel(closeTarget?.alarmLevel) }}）
      </p>
      <label class="rule-field-label">关闭原因（强制）</label>
      <textarea
        v-model="closeReason"
        class="rule-input"
        rows="2"
        placeholder="如：误报，设备已重新标定"
        aria-label="关闭原因"
      ></textarea>
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
  </div>
</template>

<style scoped>
/* 规则页布局：上（左列表右表单）下（告警列表），token 取色禁自创色值 */
.rule-toolbar {
  align-items: center;
}

.rule-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.rule-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.rule-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

.rule-alarms-card {
  margin-top: var(--fuy-space-4);
}

/* 表单基元：native input/select 与 EP 密度口径对齐（存量页面同款） */
.rule-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
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

/* 告警等级徽标（描边文本场景，色值复用语义 token） */
.fuy-alarm-tag--info {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-alarm-tag--warning {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

.fuy-alarm-tag--critical {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}
</style>
