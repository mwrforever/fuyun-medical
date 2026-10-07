<script setup lang="ts">
// 设备管理页（/iot/devices，M14 FU-M14-03/04 前端面）：设备列表（状态五态徽标/状态筛选/
// 停用操作）+ 设备注册表单（经 IoTDA 创建并签发一机一密凭证）+ 凭证重置（secret 明文
// 仅本次响应返回一次，弹窗展示关窗即清不落状态）+ 设备影子查询抽屉（desired/reported
// 两区，最后上报状态面）。停用按钮对 DISABLED 行隐藏（终态由后端 CAS 兜底）。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 结构必填显式校验零出网（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError 兜底展示 detail 原文。
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { devices, DEVICE_STATUS_LABELS } from '@/api/iot';
import type { DeviceShadowVO, DeviceVO } from '@/api/iot';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

/* ==================== 设备列表 ==================== */
/** 状态筛选（空串=全部五态） */
const statusFilter = ref('');

/** 加载设备列表（status/wardId 过滤由后端承载，前端按返回序直出）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败弹错
 * 归响应拦截器；驻留旧清单） */
const {
  rows,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({
    status: statusFilter.value === '' ? undefined : (statusFilter.value as DeviceVO['status']),
  }),
  fetcher: ({ status, page, size }) => devices.list({ status, page, size }),
  pageSize: 50,
});

/** 状态中文词表反查（状态列徽标） */
function statusLabel(code: string | undefined): string {
  return DEVICE_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 状态徽标状态类（fuy-device-tag--{status} 契约类，色值经语义 token 承载） */
function statusClass(code: string | undefined): string {
  return `fuy-device-tag--${(code ?? '').toLowerCase()}`;
}

/* ==================== 设备注册 ==================== */
const registering = ref(false);
const registerForm = ref({
  deviceId: '',
  nodeId: '',
  productId: '',
  deviceName: '',
  deviceType: '',
  accessMode: 'A',
});

/** 设备注册：结构必填显式校验（设备ID/产品ID/名称/类型，零出网）→ 出网 → 清表单刷新。 */
async function onRegister(): Promise<void> {
  if (registering.value) {
    return;
  }
  if (registerForm.value.deviceId.trim() === '') {
    void ElMessage.warning('请填写设备 ID');
    return;
  }
  if (registerForm.value.productId.trim() === '') {
    void ElMessage.warning('请填写产品 ID');
    return;
  }
  if (registerForm.value.deviceName.trim() === '') {
    void ElMessage.warning('请填写设备名称');
    return;
  }
  if (registerForm.value.deviceType.trim() === '') {
    void ElMessage.warning('请填写设备类型');
    return;
  }
  registering.value = true;
  try {
    await devices.register({
      deviceId: registerForm.value.deviceId.trim(),
      nodeId:
        registerForm.value.nodeId.trim() === '' ? undefined : registerForm.value.nodeId.trim(),
      productId: registerForm.value.productId.trim(),
      deviceName: registerForm.value.deviceName.trim(),
      deviceType: registerForm.value.deviceType.trim(),
      accessMode: registerForm.value.accessMode as 'A' | 'B' | 'C' | 'D',
    });
    void ElMessage.success('设备已注册，一机一密凭证由系统托管');
    registerForm.value = {
      deviceId: '',
      nodeId: '',
      productId: '',
      deviceName: '',
      deviceType: '',
      accessMode: 'A',
    };
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    registering.value = false;
  }
}

/* ==================== 凭证重置（一次性 secret 展示） ==================== */
const resetBusyId = ref('');
/** 一次性 secret 弹窗态（凭证重置结果；关窗即清不落任何持久状态） */
const secretVisible = ref(false);
const secretResult = ref<{ deviceId: string; credentialRef: string; secret: string } | null>(null);

/** 凭证重置（一机一密重签）：确认弹窗 → 出网 → 一次性 secret 弹窗展示。 */
async function onResetCredential(row: DeviceVO): Promise<void> {
  if (resetBusyId.value !== '') {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `即将重置设备 ${row.deviceName ?? ''} 的接入凭证，原凭证立即失效，确认？`,
      '凭证重置确认',
      { confirmButtonText: '确认重置', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  resetBusyId.value = row.deviceId ?? '';
  try {
    const result = await devices.resetCredential(row.deviceId ?? '');
    secretResult.value = {
      deviceId: result.deviceId ?? row.deviceId ?? '',
      credentialRef: result.credentialRef ?? '',
      secret: result.secret ?? '',
    };
    secretVisible.value = true;
  } catch (error) {
    surfaceBizError(error);
  } finally {
    resetBusyId.value = '';
  }
}

/** 关闭一次性 secret 弹窗（立即清空结果态，secret 不残留页面） */
function closeSecretDialog(): void {
  secretVisible.value = false;
  secretResult.value = null;
}

/* ==================== 设备影子查询抽屉 ==================== */
const shadowVisible = ref(false);
/** 影子查询目标设备（抽屉上下文锚点） */
const shadowDevice = ref<DeviceVO | null>(null);
const shadowData = ref<DeviceShadowVO | null>(null);

/** 影子查询出网（IoTDA desired/reported 两区直通展示）：loading 骨架经 useAsyncTask 收拢
 * （EX-42 范式迁移，行为与迁移前一致——失败弹错归响应拦截器；抽屉驻留空态经 onError
 * 注入数据复位） */
const { loading: shadowLoading, run: loadShadow } = useAsyncTask(
  async (deviceId: string) => {
    shadowData.value = await devices.shadow(deviceId);
  },
  {
    onError: () => {
      shadowData.value = null;
    },
  },
);

/** 打开影子抽屉（前置锚定目标设备/清空旧影子/开抽屉）后发起查询 */
async function openShadow(row: DeviceVO): Promise<void> {
  shadowDevice.value = row;
  shadowData.value = null;
  shadowVisible.value = true;
  await loadShadow(row.deviceId ?? '');
}

/** 影子区 JSON 展示串（空区显示占位） */
function shadowJson(zone: Record<string, unknown> | undefined): string {
  if (zone === undefined || Object.keys(zone).length === 0) {
    return '（空）';
  }
  return JSON.stringify(zone);
}

/* ==================== 停用 ==================== */
const disablingId = ref('');

/** 设备停用（中档确认；DISABLED 置位 CAS 由后端把守，前端按确认出网） */
async function onDisable(row: DeviceVO): Promise<void> {
  if (disablingId.value !== '') {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `即将停用设备 ${row.deviceName ?? ''}，停用后遥测拒收，确认？`,
      '设备停用确认',
      { confirmButtonText: '确认停用', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  disablingId.value = row.deviceId ?? '';
  try {
    await devices.disable(row.deviceId ?? '');
    void ElMessage.success(`设备已停用：${row.deviceName ?? ''}`);
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    disablingId.value = '';
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page device-manage fuy-stagger">
    <!-- 页头：标题 + 提示 + 刷新 -->
    <header class="device-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="device-title">设备管理</h2>
      <span class="device-hint">一机一密凭证托管 · 状态机经 IoTDA 设备状态源驱动</span>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：设备列表 -->
      <el-col :md="24" :lg="15">
        <el-card>
          <template #header>
            <div class="device-card-head">
              <span>设备列表（共 {{ rows.length }} 条）</span>
              <select
                v-model="statusFilter"
                class="device-status-select"
                aria-label="设备状态筛选"
                @change="loadList"
              >
                <option value="">全部状态</option>
                <option v-for="(label, code) in DEVICE_STATUS_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
              <el-table-column label="设备 ID" min-width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="设备名称" min-width="120">
                <template #default="{ row }">
                  {{ row.deviceName }}
                </template>
              </el-table-column>
              <el-table-column label="产品" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.productId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="接入模式" width="80">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.accessMode }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="statusClass(row.status)">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="最后在线" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.lastOnlineAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="220" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <!-- 凭证重置/停用（PR-4F #35，IOT_ADMIN 绑定）v-perm 直挂——无码 DOM
                       移除（D-34）；「影子」为只读查询抽屉入口不挂码；停用 v-if 状态机
                       与在途 :loading 数据态和权限判定两层正交 -->
                  <el-button link type="primary" size="small" @click="openShadow(row)"
                    >影子</el-button
                  >
                  <el-button
                    v-perm="'iot:device:btn:manage'"
                    link
                    type="primary"
                    size="small"
                    :loading="resetBusyId === row.deviceId"
                    @click="onResetCredential(row)"
                    >凭证重置</el-button
                  >
                  <el-button
                    v-if="row.status !== 'DISABLED'"
                    v-perm="'iot:device:btn:manage'"
                    link
                    type="danger"
                    size="small"
                    :loading="disablingId === row.deviceId"
                    @click="onDisable(row)"
                    >停用</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无设备，先从右侧注册" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：设备注册表单 -->
      <el-col :md="24" :lg="9">
        <el-card>
          <template #header>设备注册</template>
          <el-form label-position="top" size="small">
            <label class="device-field-label">设备 ID（必填，全局唯一）</label>
            <input
              v-model="registerForm.deviceId"
              class="device-input fuy-num"
              placeholder="如：dev-006"
              aria-label="设备 ID"
            />
            <label class="device-field-label">产品 ID（必填，须为已上架产品）</label>
            <input
              v-model="registerForm.productId"
              class="device-input fuy-num"
              placeholder="如：prod-pump-001"
              aria-label="产品 ID"
            />
            <div class="device-field-grid">
              <div class="device-field">
                <label class="device-field-label">设备名称（必填）</label>
                <input
                  v-model="registerForm.deviceName"
                  class="device-input"
                  placeholder="如：6 床输液泵"
                  aria-label="设备名称"
                />
              </div>
              <div class="device-field">
                <label class="device-field-label">设备类型（必填）</label>
                <input
                  v-model="registerForm.deviceType"
                  class="device-input"
                  placeholder="如：InfusionPump"
                  aria-label="设备类型"
                />
              </div>
            </div>
            <label class="device-field-label">网关节点 ID（网关子设备必填）</label>
            <input
              v-model="registerForm.nodeId"
              class="device-input fuy-num"
              placeholder="留空=直连设备"
              aria-label="网关节点 ID"
            />
            <label class="device-field-label">接入模式</label>
            <select v-model="registerForm.accessMode" class="device-input" aria-label="接入模式">
              <option value="A">A 直连</option>
              <option value="B">B 网关直挂</option>
              <option value="C">C 网关子设备</option>
              <option value="D">D 对端移交</option>
            </select>
            <!-- 注册设备（PR-4F #35「注册」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
                 DOM 移除（D-34）；右栏注册表单常驻（非弹窗），注册 POST 以本按钮为唯一
                 出口，无码会话表单可填不可出网；:loading/:disabled 在途数据态与权限判定
                 两层正交 -->
            <el-button
              v-perm="'iot:device:btn:manage'"
              type="primary"
              class="device-submit"
              :loading="registering"
              :disabled="registering"
              @click="onRegister"
              >注册设备</el-button
            >
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- 一次性 secret 展示弹窗（关窗即清，secret 不落状态） -->
    <el-dialog
      :model-value="secretVisible"
      title="凭证重置成功"
      width="460px"
      @update:model-value="closeSecretDialog"
    >
      <template v-if="secretResult !== null">
        <p class="device-secret-warn">
          新接入密钥仅展示一次，请立即保存到设备侧；关闭本弹窗后不再显示。
        </p>
        <p class="device-secret-row">
          设备 ID：<span class="fuy-num">{{ secretResult.deviceId }}</span>
        </p>
        <p class="device-secret-row">
          凭证引用：<span class="fuy-num">{{ secretResult.credentialRef }}</span>
        </p>
        <p class="device-secret-row">
          接入密钥：<span class="device-secret-value fuy-num">{{ secretResult.secret }}</span>
        </p>
      </template>
      <template #footer>
        <el-button type="primary" size="small" @click="closeSecretDialog">我已保存</el-button>
      </template>
    </el-dialog>

    <!-- 设备影子查询抽屉（desired/reported 两区） -->
    <el-drawer
      :model-value="shadowVisible"
      :title="`设备影子 — ${shadowDevice?.deviceName ?? ''}`"
      size="420px"
      @update:model-value="shadowVisible = $event"
    >
      <p class="device-shadow-device fuy-num">{{ shadowDevice?.deviceId ?? '' }}</p>
      <div v-loading="shadowLoading">
        <label class="device-field-label">期望值（desired）</label>
        <pre class="device-shadow-zone fuy-num">{{ shadowJson(shadowData?.desired) }}</pre>
        <label class="device-field-label">上报值（reported，最后上报状态）</label>
        <pre class="device-shadow-zone fuy-num">{{ shadowJson(shadowData?.reported) }}</pre>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 设备管理两栏布局：左列表右注册表单，token 取色禁自创色值 */
.device-toolbar {
  align-items: center;
}

.device-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.device-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.device-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

/* 状态筛选（native select 与 EP 密度口径对齐） */
.device-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 表单基元：native input/select 与 EP 密度口径对齐（存量页面同款） */
.device-input {
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

.device-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.device-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.device-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

/* 一次性 secret 弹窗（警示口径：告警红文本） */
.device-secret-warn {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-danger-text);
}

.device-secret-row {
  margin: 0 0 var(--fuy-space-2);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

.device-secret-value {
  font-weight: 700;
  color: var(--fuy-color-text-emphasis);
  word-break: break-all;
}

/* 影子抽屉（等宽 JSON 区块） */
.device-shadow-device {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

.device-shadow-zone {
  margin: 0 0 var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
  white-space: pre-wrap;
  word-break: break-all;
}

/* 设备五态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-device-tag--inactive {
  border-color: var(--fuy-color-device-inactive);
  color: var(--fuy-color-device-inactive);
}

.fuy-device-tag--online {
  border-color: var(--fuy-color-device-online);
  color: var(--fuy-color-device-online);
}

.fuy-device-tag--offline {
  border-color: var(--fuy-color-device-offline);
  color: var(--fuy-color-device-offline);
}

.fuy-device-tag--abnormal {
  border-color: var(--fuy-color-device-abnormal);
  color: var(--fuy-color-device-abnormal);
}

.fuy-device-tag--disabled {
  border-color: var(--fuy-color-device-disabled);
  color: var(--fuy-color-device-disabled);
}
</style>
