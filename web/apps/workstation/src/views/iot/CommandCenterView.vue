<script setup lang="ts">
// 命令中心页（/iot/commands，M16 命令下发安全面前端面）：下发台（设备选择 + 命令名 + 参数
// JSON + challenge 两步确认交互——第一步 confirm-challenge 签发一次性挑战标识，第二步携
// challengeId 下发，未取得挑战前第二步按钮前置禁用，两步门禁时序由前端禁用态强制、白名单
// 与参数校验由后端在两步把守）+ 命令白名单标注（日志行安全级徽标：安全级=查询展示类、
// 治疗级=给药/通气参数类；冻结 REST 面无产品命令白名单读取端点，标注以日志行安全级承载，
// 白名单维护入口在「产品与物模型」页命令登记）+ 命令日志（五态徽标/操作人/时间/错误留痕）。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 显式校验零出网（禁裸 parse——参数 JSON
// 显式 try/catch 收窄）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import { commands, devices, COMMAND_STATUS_LABELS, SAFETY_LEVEL_LABELS } from '@/api/iot';
import type { CommandLogVO, ConfirmChallengeVO, DeviceVO } from '@/api/iot';

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态在此展示 detail 原文 */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/** 时点展示串（MM-dd HH:mm，下发/结果时间列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 命令状态中文词表反查（状态列徽标） */
function statusLabel(code: string | undefined): string {
  return COMMAND_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 命令状态徽标状态类（fuy-command-tag--{status} 契约类，色值经语义 token 承载） */
function statusClass(code: string | undefined): string {
  return `fuy-command-tag--${(code ?? '').toLowerCase()}`;
}

/** 安全级中文词表反查（白名单标注列） */
function safetyLabel(code: string | undefined): string {
  return SAFETY_LEVEL_LABELS[code ?? ''] ?? code ?? '—';
}

/** 安全级徽标状态类（fuy-safety-tag--{level} 契约类：治疗级高亮提示） */
function safetyClass(code: string | undefined): string {
  return `fuy-safety-tag--${(code ?? '').toLowerCase()}`;
}

/* ==================== 下发台（challenge 两步确认） ==================== */
const deviceOptions = ref<DeviceVO[]>([]);
/** 下发表单（deviceId 来自设备选择，commandName 与 params 为人工输入） */
const form = ref({ deviceId: '', commandName: '', paramsText: '' });
/** 第一步在途守卫 */
const confirming = ref(false);
/** 第二步在途守卫 */
const issuing = ref(false);
/** 挑战态（null=未取得挑战，第二步禁用；取得后承载 challengeId 与有效期回显） */
const challenge = ref<ConfirmChallengeVO | null>(null);

/**
 * 参数 JSON 显式解析（禁裸 parse）：空串视为无参数（undefined）；非空须为合法 JSON 对象
 * （键值对形态，数组/标量拒绝）。
 *
 * @return 合法对象或 undefined（无参数）；非法返回 null（调用方零出网拦截）
 */
function parseParamsText(): Record<string, unknown> | undefined | null {
  const text = form.value.paramsText.trim();
  if (text === '') {
    return undefined;
  }
  try {
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** 加载设备选项（下发台设备选择数据源；全状态直出，停用设备下发由后端两步校验把守） */
async function loadDevices(): Promise<void> {
  try {
    const page = await devices.list({ page: 0, size: 50 });
    deviceOptions.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留空选项
  }
}

/**
 * 第一步·挑战确认：必填与参数 JSON 显式校验（零出网）→ 出网 confirm-challenge →
 * 挑战态落地展示 challengeId 与有效期。
 */
async function onConfirmChallenge(): Promise<void> {
  if (confirming.value || issuing.value) {
    return;
  }
  if (form.value.deviceId === '') {
    void ElMessage.warning('请选择设备');
    return;
  }
  if (form.value.commandName.trim() === '') {
    void ElMessage.warning('请填写命令名');
    return;
  }
  const params = parseParamsText();
  if (params === null) {
    void ElMessage.warning('参数须为合法 JSON 对象（键值对形态），请修正后再试');
    return;
  }
  confirming.value = true;
  try {
    challenge.value = await commands.confirmChallenge({
      deviceId: form.value.deviceId,
      commandName: form.value.commandName.trim(),
      params,
    });
  } catch (error) {
    surfaceBizError(error);
  } finally {
    confirming.value = false;
  }
}

/**
 * 第二步·确认下发：未取得挑战前置禁用（防御性兜底再拦一次）→ 出网携 challengeId 下发 →
 * 清挑战态回第一步并刷新命令日志。
 */
async function onIssue(): Promise<void> {
  if (issuing.value || challenge.value === null) {
    if (challenge.value === null) {
      void ElMessage.warning('请先完成第一步获取挑战标识');
    }
    return;
  }
  issuing.value = true;
  try {
    await commands.issue({
      challengeId: challenge.value.challengeId ?? '',
      deviceId: form.value.deviceId,
      commandName: form.value.commandName.trim(),
      params: parseParamsText() ?? undefined,
    });
    void ElMessage.success('命令已下发，执行结果见命令日志');
    challenge.value = null;
    form.value = { deviceId: form.value.deviceId, commandName: '', paramsText: '' };
    await loadLogs();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    issuing.value = false;
  }
}

/** 放弃本次挑战（回第一步；挑战有效期由后端承载，过期自然失效） */
function resetChallenge(): void {
  challenge.value = null;
}

/** 有效期展示串（挑战回显；后端 long→string 口径） */
const challengeExpiryText = computed(() => {
  const expires = challenge.value?.expiresIn;
  return expires === undefined || expires === '' ? '—' : `${expires}s`;
});

/* ==================== 命令日志 ==================== */
const logRows = ref<CommandLogVO[]>([]);
const logLoading = ref(false);
/** 状态筛选（空串=全部五态） */
const statusFilter = ref('');

/** 加载命令日志（status/deviceId 过滤由后端承载，前端按返回序直出） */
async function loadLogs(): Promise<void> {
  logLoading.value = true;
  try {
    const page = await commands.page({
      status:
        statusFilter.value === '' ? undefined : (statusFilter.value as CommandLogVO['status']),
      page: 0,
      size: 50,
    });
    logRows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    logLoading.value = false;
  }
}

onMounted(() => {
  void loadDevices();
  void loadLogs();
});
</script>

<template>
  <div class="fuy-page command-center fuy-stagger">
    <!-- 页头：标题 + 白名单口径提示 + 刷新 -->
    <header class="command-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="command-title">命令中心</h2>
      <span class="command-hint">
        challenge 两步安全门 · 治疗级命令须已登记产品命令白名单（维护入口：产品与物模型）·
        白名单校验由后端两步把守
      </span>
      <el-button :loading="logLoading" @click="loadLogs">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：下发台（challenge 两步确认交互） -->
      <el-col :md="24" :lg="9">
        <el-card>
          <template #header>命令下发台</template>
          <el-form label-position="top" size="small">
            <label class="command-field-label">设备（必选）</label>
            <select v-model="form.deviceId" class="command-input" aria-label="下发设备">
              <option value="" disabled>请选择设备</option>
              <option
                v-for="device in deviceOptions"
                :key="device.deviceId"
                :value="device.deviceId"
              >
                {{ device.deviceId }}（{{ device.deviceName }}）
              </option>
            </select>
            <label class="command-field-label">命令名（必填，须为产品命令白名单已登记项）</label>
            <input
              v-model="form.commandName"
              class="command-input fuy-num"
              placeholder="如：setVentilationParams"
              aria-label="命令名"
            />
            <label class="command-field-label">命令参数 JSON（可空，键值对形态）</label>
            <textarea
              v-model="form.paramsText"
              class="command-input"
              rows="3"
              placeholder='如：{"flow":5}'
              aria-label="命令参数 JSON"
            ></textarea>

            <!-- 两步门禁：第一步签发挑战 → 第二步携 challengeId 下发（两按钮常驻，
                 第二步未取得挑战前置禁用，禁绕过时序） -->
            <div class="command-challenge-step">
              <el-button
                type="primary"
                class="command-submit"
                :loading="confirming"
                :disabled="challenge !== null || confirming || issuing"
                @click="onConfirmChallenge"
                >第一步：获取挑战</el-button
              >
              <p v-if="challenge !== null" class="command-challenge-info fuy-num">
                挑战标识：<strong>{{ challenge.challengeId ?? '—' }}</strong> · 有效期
                {{ challengeExpiryText }}
              </p>
              <el-button
                type="primary"
                class="command-submit"
                :loading="issuing"
                :disabled="challenge === null || issuing"
                @click="onIssue"
                >第二步：确认下发</el-button
              >
              <el-button
                v-if="challenge !== null"
                link
                size="small"
                class="command-challenge-reset"
                @click="resetChallenge"
                >放弃本次挑战</el-button
              >
            </div>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右栏：命令日志（五态徽标 + 安全级白名单标注） -->
      <el-col :md="24" :lg="15">
        <el-card>
          <template #header>
            <div class="command-card-head">
              <span>命令日志（共 {{ logRows.length }} 条）</span>
              <select
                v-model="statusFilter"
                class="command-status-select"
                aria-label="命令状态筛选"
                @change="loadLogs"
              >
                <option value="">全部状态</option>
                <option v-for="(label, code) in COMMAND_STATUS_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
          </template>
          <div v-loading="logLoading">
            <el-table v-if="logRows.length > 0" :data="logRows" class="fuy-dense" size="small">
              <el-table-column label="命令号" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.commandNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="设备 ID" min-width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="命令名" min-width="140">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.commandName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="安全级" width="86">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="safetyClass(row.safetyLevel)">
                    {{ safetyLabel(row.safetyLevel) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="statusClass(row.status)">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作人" width="76">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.operator ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="下发时间" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.issuedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="结果时间" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.resultAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="错误信息" min-width="110">
                <template #default="{ row }">
                  <span :title="row.errorMsg">{{ row.errorMsg ?? '—' }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无命令日志" />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 命令中心两栏布局：左下发台右日志，token 取色禁自创色值 */
.command-toolbar {
  align-items: center;
}

.command-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.command-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.command-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

/* 状态筛选（native select 与 EP 密度口径对齐） */
.command-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 表单基元：native input/select/textarea 与 EP 密度口径对齐（存量页面同款） */
.command-input {
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

.command-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.command-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

/* 挑战回显（两步门禁中间态锚点） */
.command-challenge-info {
  margin: 0 0 var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.command-challenge-reset {
  margin-top: var(--fuy-space-2);
}

/* 命令状态五态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-command-tag--issued {
  border-color: var(--fuy-color-brand);
  color: var(--fuy-color-brand);
}

.fuy-command-tag--delivered {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-command-tag--success {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-command-tag--failed {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

.fuy-command-tag--timeout {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

/* 安全级白名单标注徽标：治疗级高亮（给药/通气参数类），安全级弱化 */
.fuy-safety-tag--safety {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}

.fuy-safety-tag--treatment {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}
</style>
