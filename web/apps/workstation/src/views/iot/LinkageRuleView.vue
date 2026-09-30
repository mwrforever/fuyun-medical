<script setup lang="ts">
// 联动规则页（/iot/linkage-rules，M16 联动治理面前端面）：规则列表（触发源三源徽标/动作类型
// 词表/目标病区/启用态）+ 规则 CRUD 弹窗（触发源与动作类型下拉走冻结词表；触发条件/动作配置
// JSON 显式 try/catch 校验禁裸 parse，非法零出网；enabled 缺省开）+ 执行日志（结果三态徽标/
// 重试计数/错误留痕；FAILED 行暴露重试按钮，重试计数累加由后端承载）。规则命中与动作投递
// 全归后端联动引擎，前端只承载治理面。
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  ACTION_TYPE_LABELS,
  LINKAGE_RESULT_LABELS,
  TRIGGER_SOURCE_LABELS,
  linkageLogs,
  linkageRules,
} from '@/api/iot';
import type { LinkageLogVO, LinkageRuleVO, SaveLinkageRuleRequest } from '@/api/iot';
import { WARD_OPTIONS } from '@/api/ward';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

/** 触发源中文词表反查（触发源列徽标） */
function triggerSourceLabel(code: string | undefined): string {
  return TRIGGER_SOURCE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 动作类型中文词表反查（动作类型列） */
function actionTypeLabel(code: string | undefined): string {
  return ACTION_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 触发源徽标状态类（fuy-rule-tag--{source} 契约类，色值经语义 token 承载） */
function triggerSourceClass(code: string | undefined): string {
  return `fuy-rule-tag--${(code ?? '').toLowerCase()}`;
}

/** 执行结果中文词表反查（结果列徽标） */
function resultLabel(code: string | undefined): string {
  return LINKAGE_RESULT_LABELS[code ?? ''] ?? code ?? '—';
}

/** 执行结果徽标状态类（fuy-linkage-tag--{result} 契约类） */
function resultClass(code: string | undefined): string {
  return `fuy-linkage-tag--${(code ?? '').toLowerCase()}`;
}

/* ==================== 规则列表 ==================== */
const rows = ref<LinkageRuleVO[]>([]);

/** 加载规则列表（量小全量直出，无分页）：loading 骨架经 useAsyncTask 收拢（EX-42 范式
 * 迁移，行为与迁移前一致——失败弹错归响应拦截器；驻留旧清单） */
const { loading: listLoading, run: loadList } = useAsyncTask(async () => {
  rows.value = await linkageRules.list();
});

/* ==================== 规则 CRUD 弹窗 ==================== */
const dialogVisible = ref(false);
const saving = ref(false);
/** 编辑目标（null=新建态；承载 id 供 update 整单替换） */
const editingId = ref<string | null>(null);
const form = ref({
  ruleName: '',
  triggerSource: 'ALARM_TRIGGERED',
  triggerConditionText: '{}',
  actionType: 'NOTIFY',
  actionConfigText: '',
  targetWardId: '',
  enabled: true,
});

/**
 * JSON 文本显式解析（禁裸 parse）：空串视为无配置（undefined）；非空须为合法 JSON 对象
 * （数组/标量拒绝）。
 *
 * @param text 文本框原文（用户输入，来源不可信）
 * @return 合法对象或 undefined（空串）；非法返回 null（调用方零出网拦截）
 */
function parseJsonObject(text: string): Record<string, unknown> | undefined | null {
  const trimmed = text.trim();
  if (trimmed === '') {
    return undefined;
  }
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** 打开新建弹窗（空表单，enabled 缺省开） */
function openCreate(): void {
  editingId.value = null;
  form.value = {
    ruleName: '',
    triggerSource: 'ALARM_TRIGGERED',
    triggerConditionText: '{}',
    actionType: 'NOTIFY',
    actionConfigText: '',
    targetWardId: '',
    enabled: true,
  };
  // 未保存草稿守卫基准（EX-46/FE-A2-06）：表单相对打开时的快照偏离即视为脏
  formSnapshot = JSON.stringify(form.value);
  dialogVisible.value = true;
}

/** 打开编辑弹窗（预填回显原规则值；JSON 对象格式化为两空格缩进便于编辑） */
function openEdit(row: LinkageRuleVO): void {
  editingId.value = row.id ?? null;
  form.value = {
    ruleName: row.ruleName ?? '',
    triggerSource: row.triggerSource ?? 'ALARM_TRIGGERED',
    triggerConditionText:
      row.triggerCondition === undefined ? '' : JSON.stringify(row.triggerCondition, null, 2),
    actionType: row.actionType ?? 'NOTIFY',
    actionConfigText:
      row.actionConfig === undefined ? '' : JSON.stringify(row.actionConfig, null, 2),
    targetWardId: row.targetWardId ?? '',
    enabled: row.enabled ?? true,
  };
  // 未保存草稿守卫基准（EX-46/FE-A2-06）
  formSnapshot = JSON.stringify(form.value);
  dialogVisible.value = true;
}

/** 弹窗打开时的表单快照（未保存草稿守卫比对基准；保存成功路径不走守卫直接关窗） */
let formSnapshot = '';

/** 关闭前未保存草稿守卫（EX-46/FE-A2-06）：表单相对打开时有改动 → 显式确认丢弃后才
 * 放行关闭，防误触（取消按钮/右上角 X）丢草稿；无改动直接放行零打扰。
 *
 * @return true=允许关闭（无改动或已确认丢弃）；false=驻留弹窗保留草稿
 */
async function confirmDiscardAndClose(): Promise<boolean> {
  if (JSON.stringify(form.value) === formSnapshot) {
    return true;
  }
  try {
    await ElMessageBox.confirm('规则表单已有未保存修改，关闭将丢弃这些内容', '未保存提醒', {
      type: 'warning',
      confirmButtonText: '丢弃并关闭',
      cancelButtonText: '继续编辑',
    });
    return true;
  } catch {
    // 拒绝丢弃：弹窗驻留，草稿保留
    return false;
  }
}

/** footer 取消按钮关闭入口（走未保存草稿守卫） */
async function onCancelDialog(): Promise<void> {
  if (await confirmDiscardAndClose()) {
    dialogVisible.value = false;
  }
}

/** 弹窗 before-close 入口（右上角 X/ESC/遮罩点击；与取消按钮同守卫） */
function onDialogBeforeClose(done: () => void): void {
  void confirmDiscardAndClose().then((allowed) => {
    if (allowed) {
      done();
    }
  });
}

/**
 * 保存规则：必填与 JSON 显式校验（零出网）→ 出网（新建/整单替换）→ 关窗刷新。
 */
async function onSave(): Promise<void> {
  if (saving.value) {
    return;
  }
  if (form.value.ruleName.trim() === '') {
    void ElMessage.warning('请填写规则名称');
    return;
  }
  const triggerCondition = parseJsonObject(form.value.triggerConditionText);
  if (triggerCondition === null) {
    void ElMessage.warning('触发条件须为合法 JSON 对象，请修正后再试');
    return;
  }
  const actionConfig = parseJsonObject(form.value.actionConfigText);
  if (actionConfig === null) {
    void ElMessage.warning('动作配置须为合法 JSON 对象，请修正后再试');
    return;
  }
  saving.value = true;
  const payload = {
    ruleName: form.value.ruleName.trim(),
    triggerSource: form.value.triggerSource as SaveLinkageRuleRequest['triggerSource'],
    triggerCondition: triggerCondition ?? {},
    actionType: form.value.actionType as SaveLinkageRuleRequest['actionType'],
    actionConfig,
    targetWardId:
      form.value.targetWardId.trim() === '' ? undefined : form.value.targetWardId.trim(),
    enabled: form.value.enabled,
  };
  try {
    if (editingId.value === null) {
      await linkageRules.create(payload);
      void ElMessage.success('规则已创建，命中后由联动引擎执行');
    } else {
      await linkageRules.update(editingId.value, payload);
      void ElMessage.success('规则已更新');
    }
    dialogVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    saving.value = false;
  }
}

/** 删除在途行锚点（null=无在途；行级守卫防双击重复提交删除） */
const removingId = ref<string | null>(null);

/**
 * 删除规则（规则删除后不再命中；历史执行日志保留由后端承载）：出网前显式确认带回显
 * 摘要（EX-46/FE-A2-05，范式对齐 AlarmRuleView.onRemoveRule）——删除为不可逆操作，
 * 误触不得直接出网；行级在途守卫（进入即判/finally 复位）覆盖确认弹窗期与出网期，
 * 防双击窗口内重复出网。
 *
 * @param row 删除目标规则行
 */
async function onRemove(row: LinkageRuleVO): Promise<void> {
  if (row.id === undefined || removingId.value !== null) {
    return;
  }
  // 在途锚点先于确认置位：确认弹窗打开期间的双击同样被守卫拦截
  removingId.value = row.id;
  try {
    try {
      await ElMessageBox.confirm(
        `即将删除规则「${row.ruleName ?? row.id}」，删除后该规则不再命中执行，确认？`,
        '规则删除确认',
        { confirmButtonText: '确认删除', cancelButtonText: '取消' },
      );
    } catch {
      // 用户取消：零出网，守卫经 finally 复位可再次发起
      return;
    }
    await linkageRules.remove(row.id);
    void ElMessage.success(`规则已删除：${row.ruleName ?? row.id}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    removingId.value = null;
  }
}

/* ==================== 执行日志 ==================== */
/** 结果筛选（空串=全部三态） */
const resultFilter = ref('');

/** 加载执行日志（ruleId/triggerSource/actionResult 过滤由后端承载）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错
 * 归响应拦截器；驻留旧清单） */
const {
  rows: logRows,
  loading: logLoading,
  fetch: loadLogs,
} = usePagedList({
  params: () => ({
    actionResult:
      resultFilter.value === '' ? undefined : (resultFilter.value as LinkageLogVO['actionResult']),
  }),
  fetcher: ({ actionResult, page, size }) => linkageLogs.page({ actionResult, page, size }),
  pageSize: 50,
});

/** 重试在途行锚点（null=无在途；行级守卫防双击窗口内重复投递联动动作——病区播报/护理任务
 * 类副作用真实发生、retryCount 双计） */
const retryingNo = ref<string | null>(null);

/**
 * 重试失败联动（按 linkageNo 重投动作；重试计数累加由后端承载）：行级在途守卫（进入即判/
 * finally 复位）防双击窗口内重复投递。
 *
 * @param row 重试目标日志行
 */
async function onRetry(row: LinkageLogVO): Promise<void> {
  if (row.linkageNo === undefined || retryingNo.value !== null) {
    return;
  }
  retryingNo.value = row.linkageNo;
  try {
    await linkageLogs.retry(row.linkageNo);
    void ElMessage.success(`联动已重试：${row.linkageNo}`);
    await loadLogs();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    retryingNo.value = null;
  }
}

onMounted(() => {
  void loadList();
  void loadLogs();
});
</script>

<template>
  <div class="fuy-page linkage-rule fuy-stagger">
    <!-- 页头：标题 + 提示 + 刷新 -->
    <header class="linkage-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="linkage-title">联动规则</h2>
      <span class="linkage-hint">触发源 × 动作类型治理 · 命中执行归联动引擎 · 失败可重试</span>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：规则列表 + CRUD -->
      <el-col :md="24" :lg="14">
        <el-card>
          <template #header>
            <div class="linkage-card-head">
              <span>规则列表（共 {{ rows.length }} 条）</span>
              <el-button type="primary" size="small" @click="openCreate">新建规则</el-button>
            </div>
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
              <el-table-column label="规则名称" min-width="150">
                <template #default="{ row }">{{ row.ruleName }}</template>
              </el-table-column>
              <el-table-column label="触发源" width="96">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    class="fuy-tag-aa"
                    :class="triggerSourceClass(row.triggerSource)"
                  >
                    {{ triggerSourceLabel(row.triggerSource) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="动作类型" width="96">
                <template #default="{ row }">{{ actionTypeLabel(row.actionType) }}</template>
              </el-table-column>
              <el-table-column label="目标病区" width="80">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.targetWardId ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="启用" width="70">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    class="fuy-tag-aa"
                    :class="row.enabled ? 'fuy-rule-tag--enabled' : 'fuy-rule-tag--disabled'"
                  >
                    {{ row.enabled ? '启用' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="110" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openEdit(row)"
                    >编辑</el-button
                  >
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :loading="removingId === row.id"
                    :disabled="removingId !== null"
                    @click="onRemove(row)"
                    >删除</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无联动规则" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：执行日志（结果徽标 + FAILED 行重试） -->
      <el-col :md="24" :lg="10">
        <el-card>
          <template #header>
            <div class="linkage-card-head">
              <span>执行日志（共 {{ logRows.length }} 条）</span>
              <select
                v-model="resultFilter"
                class="linkage-result-select"
                aria-label="执行结果筛选"
                @change="loadLogs"
              >
                <option value="">全部结果</option>
                <option v-for="(label, code) in LINKAGE_RESULT_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
          </template>
          <div v-loading="logLoading">
            <el-table v-if="logRows.length > 0" :data="logRows" class="fuy-dense" size="small">
              <el-table-column label="联动号" min-width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.linkageNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="触发源" width="88">
                <template #default="{ row }">{{ triggerSourceLabel(row.triggerSource) }}</template>
              </el-table-column>
              <el-table-column label="动作" width="88">
                <template #default="{ row }">{{ actionTypeLabel(row.actionType) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="72">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="resultClass(row.actionResult)">
                    {{ resultLabel(row.actionResult) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="重试" width="56">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.retryCount ?? 0 }}</span>
                </template>
              </el-table-column>
              <el-table-column label="执行时间" width="96">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.executedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="错误信息" min-width="110">
                <template #default="{ row }">
                  <span :title="row.errorMsg">{{ row.errorMsg ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="64" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button
                    v-if="row.actionResult === 'FAILED'"
                    link
                    type="primary"
                    size="small"
                    :loading="retryingNo === row.linkageNo"
                    :disabled="retryingNo !== null"
                    @click="onRetry(row)"
                    >重试</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无执行日志" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 规则 CRUD 弹窗（新建/编辑共用；触发条件与动作配置 JSON 显式校验；X/ESC/取消均走
         未保存草稿守卫 EX-46/FE-A2-06） -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建联动规则' : '编辑联动规则'"
      width="520px"
      :before-close="onDialogBeforeClose"
    >
      <label class="linkage-field-label">规则名称（必填）</label>
      <input
        v-model="form.ruleName"
        class="linkage-input"
        placeholder="如：危急告警转呼叫"
        aria-label="规则名称"
      />
      <div class="linkage-field-grid">
        <div class="linkage-field">
          <label class="linkage-field-label">触发源</label>
          <select v-model="form.triggerSource" class="linkage-input" aria-label="触发源">
            <option v-for="(label, code) in TRIGGER_SOURCE_LABELS" :key="code" :value="code">
              {{ label }}
            </option>
          </select>
        </div>
        <div class="linkage-field">
          <label class="linkage-field-label">动作类型</label>
          <select v-model="form.actionType" class="linkage-input" aria-label="动作类型">
            <option v-for="(label, code) in ACTION_TYPE_LABELS" :key="code" :value="code">
              {{ label }}
            </option>
          </select>
        </div>
      </div>
      <label class="linkage-field-label">触发条件 JSON（键值对形态）</label>
      <textarea
        v-model="form.triggerConditionText"
        class="linkage-input"
        rows="3"
        placeholder='如：{"level":"CRITICAL"}'
        aria-label="触发条件 JSON"
      ></textarea>
      <label class="linkage-field-label">动作配置 JSON（可空）</label>
      <textarea
        v-model="form.actionConfigText"
        class="linkage-input"
        rows="2"
        placeholder='如：{"priority":"high"}'
        aria-label="动作配置 JSON"
      ></textarea>
      <div class="linkage-field-grid">
        <div class="linkage-field">
          <label class="linkage-field-label">目标病区（可空=全院）</label>
          <select v-model="form.targetWardId" class="linkage-input" aria-label="目标病区">
            <option value="">全院</option>
            <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
              {{ ward.label }}
            </option>
          </select>
        </div>
        <div class="linkage-field">
          <label class="linkage-field-label">启用状态</label>
          <select v-model="form.enabled" class="linkage-input" aria-label="启用状态">
            <option :value="true">启用</option>
            <option :value="false">停用</option>
          </select>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="onCancelDialog">取消</el-button>
        <el-button type="primary" size="small" :loading="saving" :disabled="saving" @click="onSave"
          >保存规则</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 联动规则两栏布局：左规则右日志，token 取色禁自创色值 */
.linkage-toolbar {
  align-items: center;
}

.linkage-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.linkage-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.linkage-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

/* 结果筛选（native select 与 EP 密度口径对齐） */
.linkage-result-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 弹窗表单基元（native input/select/textarea 与 EP 密度口径对齐） */
.linkage-input {
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

.linkage-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.linkage-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 触发源三源徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-rule-tag--alarm_triggered {
  border-color: var(--fuy-color-rule-device-alarm);
  color: var(--fuy-color-rule-device-alarm);
}

.fuy-rule-tag--telemetry_anomaly {
  border-color: var(--fuy-color-rule-threshold);
  color: var(--fuy-color-rule-threshold);
}

.fuy-rule-tag--device_status {
  border-color: var(--fuy-color-rule-offline);
  color: var(--fuy-color-rule-offline);
}

/* 启用态两值徽标 */
.fuy-rule-tag--enabled {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-rule-tag--disabled {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

/* 执行结果三态徽标 */
.fuy-linkage-tag--success {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-linkage-tag--failed {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

.fuy-linkage-tag--pending {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}
</style>
