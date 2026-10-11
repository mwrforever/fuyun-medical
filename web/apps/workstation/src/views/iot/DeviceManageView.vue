<script setup lang="ts">
// 设备管理页（/iot/devices，M14 FU-M14-03/04 前端面 · 暖纸卷宗换脸重排 丙-2，「换脸不换
// 业务」：注册一机一密校验/凭证重置一次性 secret 关窗即清/影子查询/停用状态机与文案零变动）
// 纵向序：门牌页首（衬线标题 + 设备概览胶囊组四枚——取自未消费 summary 端点的健康面，PM
// 重排裁决：设备管理员开场先看健康面 + 原 hint 升批注行「谁·时刻」）→ 两栏主工作区
// （左 15 设备列表卡 .fuy-dense 密排 + 状态五态徽标 + 状态筛选；右 9 设备注册卡 .fuy-form）→
// 页脚批注。secret 一次性弹窗（.fuy-dialog + 印泥朱警示头，关窗即清不落状态）+ 设备影子查询
// 抽屉（420→520 升档挂 .fuy-drawer，desired/reported 两区保留；查询失败=抽屉内错误小卡
// 不伪装空态）。注册成功反馈=钤印「已注册」（契约 ⑧.5，≤1200ms 收场）；停用维持 ElMessageBox
// 中档确认与既有成功文案。全部写操作自带在途守卫（入口早退先于一切 await）+ 结构必填显式
// 校验零出网（禁裸 parse）；失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由
// surfaceBizError 兜底展示 detail 原文。
import { computed, onMounted, onScopeDispose, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { devices, DEVICE_STATUS_LABELS, getIotDashboardSummary } from '@/api/iot';
import type { DashboardSummaryVO, DeviceShadowVO, DeviceVO } from '@/api/iot';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';
import { formatCount } from '@/utils/workbenchDisplay';

const auth = useAuthStore();

/* ==================== 门牌页首批注行（谁·时刻，本地时钟零出网） ==================== */
/** 签认人批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/* ==================== 设备概览（门牌健康面，未消费 summary 端点取用） ==================== */
/** 概览汇总三态（summary=null 且 error=null 即骨架态）：单次出网手动刷新不轮询 */
const summary = ref<DashboardSummaryVO | null>(null);
const summaryLoad = useAsyncTask(async () => {
  summary.value = await getIotDashboardSummary();
});
const summaryLoading = summaryLoad.loading;
const summaryError = summaryLoad.error;

/** 概览汇总读取（首拉与刷新联动共用入口；失败静默弹错归响应拦截器） */
function refreshSummary(): Promise<void> {
  return summaryLoad.run().then(() => undefined);
}

/* ==================== 设备列表 ==================== */
/** 状态筛选（空串=全部五态） */
const statusFilter = ref('');

/** 加载设备列表（status 过滤由后端承载，前端按返回序直出）：页码/行集/加载态经
 * usePagedList 收拢（EX-49 范式，固定首页 size 50 直出——失败弹错归响应拦截器；驻留旧清单） */
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

/** 刷新联动（蓝图 丙-2.6：概览带单次出网随刷新联动——清单与概览一并重取） */
function refreshAll(): void {
  void loadList();
  void refreshSummary();
}

/** 刷新钮在途态（清单与概览任一在途即亮，防重复击发零出网双发） */
const overviewLoading = computed(() => listLoading.value || summaryLoading.value);

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

/** 设备注册：结构必填显式校验（设备ID/产品ID/名称/类型，零出网）→ 出网 → 钤印「已注册」
 * 清表单刷新。 */
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
    // 成功反馈=钤印（契约 ⑧.5：动词结果文案；一机一密托管口径归门牌批注行承载）
    showStamp('已注册');
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

/* ==================== 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ==================== */
/** 钤印收场时长（契约 ⑧.5：≤1200ms 随弹层/页面收场） */
const STAMP_DURATION_MS = 1200;
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认；失败与业务拒绝归 message 批注条/拦截器，钤印永不承失败） */
function showStamp(text: string): void {
  stampText.value = text;
  stampSeq.value += 1;
  stampVisible.value = true;
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
  }
  // 收场定时器仅翻转内部展示 refs（零 DOM/出网副作用），卸载经 scope dispose 清理
  stampTimer = setTimeout(() => {
    stampVisible.value = false;
    stampTimer = null;
  }, STAMP_DURATION_MS);
}

// 组件卸载时清理在途收场定时器（useAdverseEvents 钤印段同构口径）
onScopeDispose(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

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
 * （EX-42 范式——失败弹错归响应拦截器；失败时经 onError 注入数据复位，抽屉内以错误小卡
 * 「说问题给恢复」承载，不伪装空态） */
const {
  loading: shadowLoading,
  error: shadowError,
  run: loadShadow,
} = useAsyncTask(
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

/** 影子查询重试（错误小卡恢复入口：对当前锚定设备重新出网） */
function retryShadow(): void {
  if (shadowDevice.value !== null) {
    void loadShadow(shadowDevice.value.deviceId ?? '');
  }
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
  void refreshSummary();
});
</script>

<template>
  <div class="fuy-page device-manage">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——甲-3
         ae-flow 同构）：本层承接根级 flex+gap 纵排，三段级联序 header 0 / 两栏 1 / 页脚 2 -->
    <div class="fuy-stagger device-flow">
      <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 设备概览胶囊组（健康面，蓝图 丙-2.2 PM 重排裁决：
           设备管理员开场先看健康面）+ 批注行（原 hint 升批注，「谁·时刻」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">设备管理</h1>
          <span class="fuy-page-en" aria-hidden="true">DEVICE FLEET</span>
        </div>
        <div class="fuy-page-status">
          <!-- 概览胶囊组三态（蓝图 丙-2.7 锚点①：四字段映射 + 骨架/内容/错误） -->
          <Transition name="fuy-content-fade" mode="out-in">
            <!-- 内容态：四枚胶囊四色变体（中性=总数/success=在线/amber=离线/印泥朱=活跃告警），
               双通道铁律：胶囊必带文字；计数经 formatCount 千分位（缺失 — 占位零伪数据） -->
            <span v-if="summary !== null" key="ovw-content" class="device-overview-pills">
              <span class="fuy-status-pill fuy-status-pill--info"
                >总设备 {{ formatCount(summary.deviceTotal) }}</span
              >
              <span class="fuy-status-pill fuy-status-pill--success"
                >在线 {{ formatCount(summary.onlineCount) }}</span
              >
              <span class="fuy-status-pill fuy-status-pill--amber"
                >离线 {{ formatCount(summary.offlineCount) }}</span
              >
              <span class="fuy-status-pill fuy-status-pill--danger"
                >活跃告警 {{ formatCount(summary.activeAlarmCount) }}</span
              >
            </span>
            <!-- 错误态=说问题给恢复（G6：禁伪装空态；重试=墨 link 重取概览） -->
            <span
              v-else-if="summaryError !== null"
              key="ovw-error"
              class="device-overview-error"
              role="alert"
            >
              <span>设备概览加载失败，计数暂缺</span>
              <el-button
                link
                type="primary"
                size="small"
                :loading="summaryLoading"
                @click="refreshSummary"
                >重试</el-button
              >
            </span>
            <!-- 骨架态：静态金洗纸胶囊占位（零 shimmer，min 高锁 CLS） -->
            <span v-else key="ovw-skeleton" class="device-overview-skeleton" aria-hidden="true">
              <span v-for="index in 4" :key="index" class="fuy-sk device-sk-pill"></span>
            </span>
          </Transition>
          <el-button
            link
            type="primary"
            size="small"
            :loading="overviewLoading"
            :disabled="overviewLoading"
            @click="refreshAll"
            >刷新</el-button
          >
        </div>
        <p class="fuy-page-note">
          一机一密凭证托管 · 状态机经 IoTDA 驱动 · 签认人 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：设备列表卡（卷宗卡 + .fuy-dense 密排——挂卡容器构成后代关系） -->
        <el-col :md="24" :lg="15">
          <section class="fuy-card fuy-dense">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">设备列表</h2>
              <span class="device-list-total"
                >共 <span class="fuy-num">{{ rows.length }}</span> 条</span
              >
            </header>
            <div class="fuy-card-body">
              <!-- 状态筛选（契约 ⑧.3 .fuy-filter：label 疏排 + native select 32px 与默认钮同高，
                   jsdom 可测口径存量件） -->
              <div class="fuy-filter device-filter-row">
                <label for="device-status-filter">状态筛选</label>
                <select
                  id="device-status-filter"
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
              <!-- 列表体 min-height 280px 锁 CLS（蓝图 丙-2.6 骨架档） -->
              <div v-loading="listLoading" class="device-list-body">
                <el-table :data="rows">
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
                  <!-- 空态脸（契约 ⑥）：暂无语法 + 下一步指引，零默认纸箱插画 -->
                  <template #empty>
                    <div class="fuy-empty" role="status">
                      <span class="fuy-empty-mark" aria-hidden="true">空</span>
                      <p class="fuy-empty-title">暂无设备</p>
                      <p class="fuy-empty-hint">
                        当前筛选条件下暂无设备记录，可调整状态筛选或从右侧注册新设备。
                      </p>
                    </div>
                  </template>
                </el-table>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：设备注册卡（.fuy-form 表单脸；native input 与 EP 密度口径对齐存量件） -->
        <el-col :md="24" :lg="9">
          <section class="fuy-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">设备注册</h2>
            </header>
            <div class="fuy-card-body">
              <el-form label-position="top" size="small" class="fuy-form">
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
                <select
                  v-model="registerForm.accessMode"
                  class="device-input"
                  aria-label="接入模式"
                >
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
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- 页脚批注（契约 ⑧.8）：手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        概览与清单均为手动刷新取数，不自动轮询
      </footer>

      <!-- 一次性 secret 展示弹窗（.fuy-dialog 弹层脸 + 印泥朱警示头；关窗即清，secret 不落状态） -->
      <el-dialog
        :model-value="secretVisible"
        title="凭证重置成功"
        width="460px"
        class="fuy-dialog"
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

      <!-- 设备影子查询抽屉（desired/reported 两区；蓝图 丙-2.2：420→520 升档挂 .fuy-drawer，
           头部批注槽=查询人·时刻） -->
      <el-drawer
        :model-value="shadowVisible"
        class="fuy-drawer"
        size="520px"
        :title="`设备影子 — ${shadowDevice?.deviceName ?? ''}`"
        @update:model-value="shadowVisible = $event"
      >
        <p class="device-shadow-device fuy-num">{{ shadowDevice?.deviceId ?? '' }}</p>
        <p class="device-shadow-note">
          查询人 {{ signerName }} · <time>{{ todayLabel }}</time>
        </p>
        <div v-loading="shadowLoading">
          <!-- 查询失败=说问题给恢复错误小卡（G6：不伪装空态，不落 pre 空区；重试=墨实底主操作） -->
          <div
            v-if="shadowError !== null && shadowData === null"
            class="device-shadow-error"
            role="alert"
          >
            <p class="device-shadow-error-title">影子查询失败</p>
            <p class="device-shadow-error-hint">
              网络异常或设备暂不可达，影子两区暂缺；可重试，问题持续请联系系统管理员。
            </p>
            <el-button type="primary" size="small" :loading="shadowLoading" @click="retryShadow"
              >重新查询</el-button
            >
          </div>
          <template v-else>
            <label class="device-field-label">期望值（desired）</label>
            <pre class="device-shadow-zone fuy-num">{{ shadowJson(shadowData?.desired) }}</pre>
            <label class="device-field-label">上报值（reported，最后上报状态）</label>
            <pre class="device-shadow-zone fuy-num">{{ shadowJson(shadowData?.reported) }}</pre>
          </template>
        </div>
      </el-drawer>

      <!-- 钤印确认浮层（契约 ⑧.5：只承成功确认；v-if 挂载制 + seq :key 重挂重放落章——
           甲-3 同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤，挂载即 is-show
           根治；≤1200ms 收场归 showStamp 定时器） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 暖纸重排（丙-2）：门牌页首/概览胶囊组/卷宗卡两栏/页脚批注；取色全走 --fuy-* 语义
   别名（零新 hex），动效只消费 motion.css 全局 keyframes */

/* 页内级联容器（契约 ⑦.4）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与
   区块间距由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 */
.device-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：概览胶囊组 + 刷新钮同行（甲-3 同位语法） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 概览胶囊组（蓝图 丙-2.3）：横排四枚，暖底描边脸走全局 .fuy-status-pill 变体 */
.device-overview-pills {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 概览骨架态：四枚静态金洗纸胶囊占位（.fuy-sk 全局族，宽度对齐胶囊量级锁 CLS） */
.device-overview-skeleton {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

.device-sk-pill {
  width: 64px;
  height: 24px;
}

/* 概览错误态：说问题给恢复行内小卡（G6：不伪装空态；问题=印泥朱 12px，重试=墨 link） */
.device-overview-error {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

/* 列表卡体 min-height 280px 锁 CLS（蓝图 丙-2.6 骨架档） */
.device-list-body {
  min-height: 280px;
}

.device-list-total {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 筛选行（契约 ⑧.3）：与卡体拉开一档（下缘发丝=卡内细节规线，规线不越级） */
.device-filter-row {
  padding-bottom: var(--fuy-space-3);
  margin-bottom: var(--fuy-space-3);
  border-bottom: var(--fuy-border-hairline);
}

/* 状态筛选 native select（jsdom 可测口径存量件）：32px 与默认钮同高；围合取值直取
   --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边——住院域琢段统一口径） */
.device-status-select {
  height: 32px;
  padding: 0 var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 表单基元：native input/select 与 EP 密度口径对齐（存量页面同款）；围合取值直取
   --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边，与卡面拉开一档） */
.device-input {
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

/* 一次性 secret 弹窗警示头（印泥朱一次性语义）：左缘 2px 状态朱 + 印泥朱洗底
   （--el-color-danger-light-9 A-5 已批映射自动承继）+ 印泥朱文字 */
.device-secret-warn {
  margin: 0 0 var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-left: 2px solid var(--fuy-color-danger-text);
  border-radius: var(--fuy-radius-sm);
  background: var(--el-color-danger-light-9);
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

/* 影子抽屉（等宽 JSON 区块；头部批注槽=查询人·时刻走 .fuy-page-note 同构 12px 灰墨） */
.device-shadow-device {
  margin: 0 0 var(--fuy-space-2);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

.device-shadow-note {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text);
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

/* 影子查询失败错误小卡：说问题 + 给恢复（G6：重试=墨实底主操作，不伪装空态） */
.device-shadow-error {
  padding: var(--fuy-space-4);
  border: 1px dashed var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-lg);
  text-align: center;
}

.device-shadow-error-title {
  margin: 0;
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.device-shadow-error-hint {
  margin: var(--fuy-space-2) auto 0;
  max-width: 46ch;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

.device-shadow-error .el-button {
  margin-top: var(--fuy-space-3);
}

/* 概览三态（骨架/内容/错误）out-in 切换 fade leave 段：motion.css 仅定义 enter 段，
   甲-3/护士站/PDA 同款页内补齐——旧态 120ms 淡出后再进新态，防三态硬切 */
.fuy-content-fade-leave-active {
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit);
}

.fuy-content-fade-leave-to {
  opacity: 0;
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
