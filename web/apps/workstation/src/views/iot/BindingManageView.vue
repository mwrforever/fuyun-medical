<script setup lang="ts">
// 设备绑定页（/iot/bindings，M14 FU-M14-03 绑定生命周期前端面）：绑定列表（设备×患者×
// 就诊×床位×病区五元组快照列/绑定模式/状态三态徽标/解绑原因留痕）+ 绑定表单（visit_id
// 14 位 I 格式显式校验——I+8 位日期+5 位流水，M04 冻结口径；绑定校验设备未停用/患者在院/
// 床位有效等业务规则单点归后端把守，前端不复制拦截）+ 解绑原因强制弹窗（转床/消毒/维修/
// 出院/调拨口径，空原因零出网拦截）。绑定历史只增：解绑受理置 UNBINDING 由后端承载。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 显式校验零出网（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹），业务拒绝对象由 surfaceBizError 兜底展示。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { bindings, BIND_TYPE_LABELS, BINDING_STATUS_LABELS, IOT_WARD_OPTIONS } from '@/api/iot';
import type { BindingVO } from '@/api/iot';
import { usePagedList } from '@/composables/usePagedList';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

/** visit 号格式：I 前缀 + 13 位数字（I+8 位日期+5 位流水，共 14 字符，M04 冻结） */
const VISIT_NO_PATTERN = /^I\d{13}$/;

/* ==================== 绑定列表 ==================== */
/** 状态筛选（空串=全部三态） */
const statusFilter = ref('');

/** 加载绑定列表（deviceId/wardId/status 过滤由后端承载，前端按返回序直出）：页码/行集/
 * 加载态经 usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——
 * 失败弹错归响应拦截器；驻留旧清单） */
const {
  rows,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({
    status: statusFilter.value === '' ? undefined : (statusFilter.value as BindingVO['status']),
  }),
  fetcher: ({ status, page, size }) => bindings.list({ status, page, size }),
  pageSize: 50,
});

/** 绑定状态中文词表反查（状态列徽标） */
function statusLabel(code: string | undefined): string {
  return BINDING_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 绑定状态徽标状态类（fuy-binding-tag--{status} 契约类，色值经语义 token 承载） */
function statusClass(code: string | undefined): string {
  return `fuy-binding-tag--${(code ?? '').toLowerCase()}`;
}

/** 绑定模式中文词表反查（模式列） */
function bindTypeLabel(code: string | undefined): string {
  return BIND_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/* ==================== 绑定表单 ==================== */
const binding = ref(false);
const bindForm = ref({
  deviceId: '',
  patientId: '',
  visitId: '',
  bedId: '',
  wardId: IOT_WARD_OPTIONS[0].code,
  bindType: 'FIXED',
  bindReason: '',
});

/** 绑定提交：结构必填显式校验（设备/患者/就诊号 14 位格式，零出网）→ 出网 → 清表单刷新。 */
async function onBind(): Promise<void> {
  if (binding.value) {
    return;
  }
  if (bindForm.value.deviceId.trim() === '') {
    void ElMessage.warning('请填写设备 ID');
    return;
  }
  if (bindForm.value.patientId.trim() === '') {
    void ElMessage.warning('请填写患者号');
    return;
  }
  if (!VISIT_NO_PATTERN.test(bindForm.value.visitId.trim())) {
    void ElMessage.warning('就诊号应以 I 开头共 14 位（I+日期+流水）');
    return;
  }
  binding.value = true;
  try {
    await bindings.bind({
      deviceId: bindForm.value.deviceId.trim(),
      patientId: bindForm.value.patientId.trim(),
      visitId: bindForm.value.visitId.trim(),
      bedId: bindForm.value.bedId.trim() === '' ? undefined : bindForm.value.bedId.trim(),
      wardId: bindForm.value.wardId,
      bindType: bindForm.value.bindType as 'FIXED' | 'MOBILE',
      bindReason:
        bindForm.value.bindReason.trim() === '' ? undefined : bindForm.value.bindReason.trim(),
    });
    void ElMessage.success('绑定已生效，遥测将按新归属落库');
    bindForm.value = {
      deviceId: '',
      patientId: '',
      visitId: '',
      bedId: '',
      wardId: IOT_WARD_OPTIONS[0].code,
      bindType: 'FIXED',
      bindReason: '',
    };
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    binding.value = false;
  }
}

/* ==================== 解绑弹窗（原因强制） ==================== */
const unbindVisible = ref(false);
const unbinding = ref(false);
/** 解绑目标设备（弹窗上下文锚点） */
const unbindTarget = ref<BindingVO | null>(null);
const unbindReason = ref('');

/** 打开解绑弹窗（仅 BOUND 态行暴露入口） */
function openUnbind(row: BindingVO): void {
  unbindTarget.value = row;
  unbindReason.value = '';
  unbindVisible.value = true;
}

/** 解绑目标摘要行（弹窗回显锚点） */
const unbindSummary = computed(() => {
  const row = unbindTarget.value;
  if (row === null) {
    return '';
  }
  return `${row.deviceId ?? ''} · ${row.patientId ?? ''} · ${row.visitId ?? ''}`;
});

/** 确认解绑：原因强制显式校验（空原因零出网）→ 出网 → 关窗刷新。 */
async function onUnbind(): Promise<void> {
  if (unbinding.value) {
    return;
  }
  if (unbindReason.value.trim() === '') {
    void ElMessage.warning('请填写解绑原因');
    return;
  }
  unbinding.value = true;
  try {
    await bindings.unbind(unbindTarget.value?.deviceId ?? '', {
      reason: unbindReason.value.trim(),
    });
    void ElMessage.success(`解绑已受理：${unbindTarget.value?.deviceId ?? ''}`);
    unbindVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    unbinding.value = false;
  }
}

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page binding-manage fuy-stagger">
    <!-- 页头：标题 + 提示 + 刷新 -->
    <header class="binding-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="binding-title">设备绑定</h2>
      <span class="binding-hint">五元组快照权威源 · 绑定历史只增 · 解绑原因强制留痕</span>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：绑定列表 -->
      <el-col :md="24" :lg="15">
        <el-card>
          <template #header>
            <div class="binding-card-head">
              <span>绑定列表（共 {{ rows.length }} 条）</span>
              <select
                v-model="statusFilter"
                class="binding-status-select"
                aria-label="绑定状态筛选"
                @change="loadList"
              >
                <option value="">全部状态</option>
                <option v-for="(label, code) in BINDING_STATUS_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
          </template>
          <div v-loading="listLoading">
            <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
              <el-table-column label="设备 ID" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="患者号" min-width="150">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.patientId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="就诊号" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.visitId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="床位" width="70">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.bedId ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="病区" width="70">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.wardId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="模式" width="80">
                <template #default="{ row }">
                  {{ bindTypeLabel(row.bindType) }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="statusClass(row.status)">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="解绑原因" min-width="100">
                <template #default="{ row }">
                  <span :title="row.unbindReason">{{ row.unbindReason ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="绑定时间" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.boundAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'BOUND'"
                    link
                    type="danger"
                    size="small"
                    @click="openUnbind(row)"
                    >解绑</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无绑定记录" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：绑定表单 -->
      <el-col :md="24" :lg="9">
        <el-card>
          <template #header>设备绑定</template>
          <el-form label-position="top" size="small">
            <label class="binding-field-label">设备 ID（必填，须为未停用设备）</label>
            <input
              v-model="bindForm.deviceId"
              class="binding-input fuy-num"
              placeholder="如：dev-009"
              aria-label="设备 ID"
            />
            <label class="binding-field-label">患者号（必填，患者在院校验归后端）</label>
            <input
              v-model="bindForm.patientId"
              class="binding-input fuy-num"
              placeholder="患者档案编号"
              aria-label="患者号"
            />
            <label class="binding-field-label">就诊号（必填，I 开头共 14 位）</label>
            <input
              v-model="bindForm.visitId"
              class="binding-input fuy-num"
              placeholder="如：I2026092600009"
              aria-label="就诊号"
            />
            <div class="binding-field-grid">
              <div class="binding-field">
                <label class="binding-field-label">病区</label>
                <select v-model="bindForm.wardId" class="binding-input" aria-label="病区">
                  <option v-for="ward in IOT_WARD_OPTIONS" :key="ward.code" :value="ward.code">
                    {{ ward.label }}
                  </option>
                </select>
              </div>
              <div class="binding-field">
                <label class="binding-field-label">床位号（固定式建议填写）</label>
                <input
                  v-model="bindForm.bedId"
                  class="binding-input fuy-num"
                  placeholder="床位有效性校验归后端"
                  aria-label="床位号"
                />
              </div>
            </div>
            <label class="binding-field-label">绑定模式</label>
            <select v-model="bindForm.bindType" class="binding-input" aria-label="绑定模式">
              <option value="FIXED">固定式（设备↔床位，患者随床位推导）</option>
              <option value="MOBILE">移动式（设备↔患者直绑）</option>
            </select>
            <label class="binding-field-label">绑定原因</label>
            <textarea
              v-model="bindForm.bindReason"
              class="binding-input"
              rows="2"
              placeholder="如：术后监护"
              aria-label="绑定原因"
            ></textarea>
            <el-button
              type="primary"
              class="binding-submit"
              :loading="binding"
              :disabled="binding"
              @click="onBind"
              >确认绑定</el-button
            >
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- 解绑原因强制弹窗 -->
    <el-dialog v-model="unbindVisible" title="设备解绑" width="420px">
      <p class="binding-unbind-target fuy-num">{{ unbindSummary }}</p>
      <label class="binding-field-label">解绑原因（强制：转床/消毒/维修/出院/调拨）</label>
      <textarea
        v-model="unbindReason"
        class="binding-input"
        rows="2"
        placeholder="解绑原因强制留痕，绑定历史只增"
        aria-label="解绑原因"
      ></textarea>
      <template #footer>
        <el-button size="small" @click="unbindVisible = false">取消</el-button>
        <el-button
          type="danger"
          size="small"
          :loading="unbinding"
          :disabled="unbinding"
          @click="onUnbind"
          >确认解绑</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 绑定管理两栏布局：左列表右表单，token 取色禁自创色值 */
.binding-toolbar {
  align-items: center;
}

.binding-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.binding-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.binding-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

/* 状态筛选（native select 与 EP 密度口径对齐） */
.binding-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 表单基元：native input/select 与 EP 密度口径对齐（存量页面同款） */
.binding-input {
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

.binding-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.binding-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.binding-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

.binding-unbind-target {
  margin: 0 0 var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

/* 绑定状态三态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-binding-tag--bound {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-binding-tag--unbinding {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

.fuy-binding-tag--unbound {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}
</style>
