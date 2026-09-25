<script setup lang="ts">
// 病区床位图页（/inpatient/beds，M04 FU-M04-02 前端面）：病区选择 + 五态色标床位卡墙
// （空床绿/预占橙/占床蓝/消毒灰/维修红——--fuy-color-bed-* 语义 token 承载禁自创色值）+
// 床位操作下拉（按状态给出合法动作：预占/占床/释放/消毒完成/维修/恢复/转科转床）+
// 转科转床弹窗（同病区轻量转床 / 跨病区四阶段编排，提示文案冻结四阶段口径）。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 4xx 口径显式校验（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底展示
// detail 原文）。占用患者摘要仅展示就诊号与入科时点（姓名脱敏不出网，BedMapVO 契约
// 即不含姓名）。
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import { beds, BED_STATUS_LABELS, transfer, WARD_OPTIONS } from '@/api/inpatient';
import type { BedMapVO } from '@/api/inpatient';

/** visit 号格式：I 前缀 + 13 位数字（I+8 位日期+5 位流水，共 14 字符，M02 冻结） */
const VISIT_NO_PATTERN = /^I\d{13}$/;

/** 五态展示序与图例顺序（BED_STATUS_LABELS 键序冻结） */
const BED_STATE_ORDER = ['FREE', 'RESERVED', 'OCCUPIED', 'DISINFECTING', 'MAINTENANCE'] as const;

/** 床位状态 → 合法动作清单（后端状态机迁移表的前端镜像；value=动作语义键） */
const BED_ACTIONS: Record<string, ReadonlyArray<{ value: string; label: string }>> = {
  FREE: [
    { value: 'reserve', label: '预占' },
    { value: 'assign', label: '占床' },
    { value: 'maintain', label: '转维修' },
  ],
  RESERVED: [{ value: 'release', label: '释放预占' }],
  OCCUPIED: [{ value: 'transferDialog', label: '转科转床' }],
  DISINFECTING: [{ value: 'disinfectDone', label: '消毒完成' }],
  MAINTENANCE: [{ value: 'maintainDone', label: '维修恢复' }],
};

/** 业务失败兜底展示：AxiosError 已由响应拦截器弹错（防双弹）；其余形态（api 层直抛的
 * ProblemDetail 对象）在此展示 detail 原文 */
function surfaceBizError(error: unknown): void {
  if (axios.isAxiosError(error)) {
    return;
  }
  const detail = (error as { detail?: unknown } | null | undefined)?.detail;
  if (typeof detail === 'string' && detail.length > 0) {
    void ElMessage.error(detail);
  }
}

/** 时点展示串（MM-dd HH:mm，占用摘要入科时点共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/* ==================== 床位卡墙 ==================== */
/** 当前病区（默认演示病区 W01；切换即重载床位图） */
const wardId = ref(WARD_OPTIONS[0].code);
const bedList = ref<BedMapVO[]>([]);
const mapLoading = ref(false);
/** 在途守卫：同一时刻至多一个床位动作可发（防双击重复提交） */
const actingBedId = ref<string | null>(null);

/** 加载病区床位图（后端床号升序直出） */
async function loadMap(): Promise<void> {
  mapLoading.value = true;
  try {
    bedList.value = await beds.map(wardId.value);
  } catch {
    // 失败弹错归响应拦截器；驻留旧床位图
  } finally {
    mapLoading.value = false;
  }
}

function onWardChange(): void {
  void loadMap();
}

/** 五态色标状态类（fuy-bed-card--{state} 契约类，色值经语义 token 承载） */
function stateClass(bed: BedMapVO): string {
  return `fuy-bed-card--${(bed.bedStatus ?? '').toLowerCase()}`;
}

/** 状态中文词表反查（卡内状态标签与图例共用） */
function stateLabel(code: string | undefined): string {
  return BED_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 五态计数（图例行） */
const stateCounts = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = {};
  for (const state of BED_STATE_ORDER) {
    counts[state] = bedList.value.filter((bed) => bed.bedStatus === state).length;
  }
  return counts;
});

/** 床位卡动作清单（按状态给出合法迁移） */
function actionsOf(bed: BedMapVO): ReadonlyArray<{ value: string; label: string }> {
  return BED_ACTIONS[bed.bedStatus ?? ''] ?? [];
}

/** 床位操作 select 复位（动作完成后回「操作…」占位） */
function resetActionSelect(): void {
  actingBedId.value = null;
}

/** 占床（直接分配快速通道）：prompt 收 visit 号并显式校验 14 位格式零出网。 */
async function onAssign(bed: BedMapVO): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `为床位 ${bed.bedNo ?? ''} 办理占床，请扫描/输入住院就诊号`,
      '床位占床',
      {
        confirmButtonText: '确认占床',
        inputPlaceholder: 'visit 号（I 开头共 14 位）',
        inputValidator: (input: string) =>
          VISIT_NO_PATTERN.test(input.trim()) ? true : 'visit 号应以 I 开头共 14 位（I+日期+流水）',
      },
    );
    actingBedId.value = String(bed.bedId ?? '');
    await beds.assign(String(bed.bedId ?? ''), { visitId: value.trim() });
    void ElMessage.success(`床位已占床：${bed.bedNo ?? ''}`);
    await loadMap();
  } catch (error) {
    if (!axios.isAxiosError(error)) {
      return;
    }
    surfaceBizError(error);
  } finally {
    resetActionSelect();
  }
}

/** 床位卡动作分发：转科转床开弹窗；占床走 prompt；其余简单迁移走确认。 */
async function onBedAction(bed: BedMapVO, action: string): Promise<void> {
  if (action === '' || actingBedId.value !== null) {
    return;
  }
  if (action === 'transferDialog') {
    void openTransferDialog(bed);
    resetActionSelect();
    return;
  }
  if (action === 'assign') {
    await onAssign(bed);
    return;
  }
  const actionLabels: Record<string, string> = {
    reserve: '预占',
    release: '释放预占',
    disinfectDone: '消毒完成',
    maintain: '转维修',
    maintainDone: '维修恢复',
  };
  try {
    await ElMessageBox.confirm(
      `确认对床位 ${bed.bedNo ?? ''} 执行「${actionLabels[action] ?? action}」？`,
      '床位操作确认',
      { confirmButtonText: '确认', cancelButtonText: '取消' },
    );
  } catch {
    resetActionSelect();
    return;
  }
  actingBedId.value = String(bed.bedId ?? '');
  try {
    if (action === 'reserve') {
      // 预占不绑定就诊主体，契约形状固化空对象（BedReserveRequest）
      await beds.reserve(String(bed.bedId ?? ''), {});
    } else if (action === 'release') {
      await beds.release(String(bed.bedId ?? ''));
    } else if (action === 'disinfectDone') {
      await beds.disinfectDone(String(bed.bedId ?? ''));
    } else if (action === 'maintain') {
      await beds.maintain(String(bed.bedId ?? ''));
    } else if (action === 'maintainDone') {
      await beds.maintainDone(String(bed.bedId ?? ''));
    }
    void ElMessage.success(`床位操作已完成：${bed.bedNo ?? ''}`);
    await loadMap();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    resetActionSelect();
  }
}

/* ==================== 转科转床弹窗 ==================== */
const transferVisible = ref(false);
const transferring = ref(false);
/** 转科编排源床位（OCCUPIED，携占用就诊摘要） */
const transferBed = ref<BedMapVO | null>(null);
/** 编排模式：change=同病区转床轻量路径；transfer=跨病区四阶段编排 */
const transferMode = ref<'change' | 'transfer'>('change');
const transferForm = ref({ toWardId: WARD_OPTIONS[0].code, toBedId: '' });
/** 目标床位候选（转床=本病区空床；转科=目标病区空床+预占床——转科预占为计划目标） */
const targetBeds = ref<BedMapVO[]>([]);

/** 源占用就诊摘要（脱敏口径：仅就诊号+入科时点） */
const transferVisitId = computed(() => transferBed.value?.occupiedVisit?.visitId ?? '');

/** 打开转科转床弹窗：默认同病区转床，预载本病区可转入床位（FREE/RESERVED）。 */
async function openTransferDialog(bed: BedMapVO): Promise<void> {
  transferBed.value = bed;
  transferMode.value = 'change';
  transferForm.value = { toWardId: bed.wardId ?? WARD_OPTIONS[0].code, toBedId: '' };
  transferVisible.value = true;
  await loadTargetBeds(transferForm.value.toWardId);
}

/** 切换编排模式：转科模式展示目标病区选择（默认当前病区，跨病区由用户改选）。 */
function onTransferModeChange(): void {
  transferForm.value.toBedId = '';
}

/** 目标病区变更后重载可转入床位。 */
async function onTransferWardChange(): Promise<void> {
  transferForm.value.toBedId = '';
  await loadTargetBeds(transferForm.value.toWardId);
}

/** 装载目标床位候选：空床+预占床（转科预占语义，占床/消毒/维修不可选）。 */
async function loadTargetBeds(ward: string): Promise<void> {
  try {
    const all = await beds.map(ward);
    targetBeds.value = all.filter(
      (bed) => bed.bedStatus === 'FREE' || bed.bedStatus === 'RESERVED',
    );
  } catch {
    // 失败弹错归响应拦截器；候选空表态
    targetBeds.value = [];
  }
}

/** 转科转床提交：未选源床/目标床显式校验零出网 → 出网 → 成功关窗刷新床位图。
 * 四阶段编排（停嘱截断/在途三分/床位流转/事件链）与同病区判别全由后端承载。 */
async function onTransferConfirm(): Promise<void> {
  if (transferring.value) {
    return;
  }
  const bed = transferBed.value;
  if (bed === null || transferVisitId.value === '') {
    void ElMessage.warning('请从占床床位发起转科转床');
    return;
  }
  if (transferForm.value.toBedId === '') {
    void ElMessage.warning('请选择目标床位');
    return;
  }
  transferring.value = true;
  try {
    if (transferMode.value === 'change') {
      await transfer.changeBed(transferVisitId.value, { toBedId: transferForm.value.toBedId });
    } else {
      await transfer.execute(transferVisitId.value, {
        toWardId: transferForm.value.toWardId,
        toBedId: transferForm.value.toBedId,
      });
    }
    void ElMessage.success(
      transferMode.value === 'change'
        ? `转床完成：${bed.bedNo ?? ''} 已迁移`
        : `转科编排完成：${transferVisitId.value}`,
    );
    transferVisible.value = false;
    await loadMap();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    transferring.value = false;
  }
}

onMounted(() => {
  void loadMap();
});
</script>

<template>
  <div class="fuy-page bed-map-view fuy-stagger">
    <!-- 页头：病区选择 + 五态图例计数 + 刷新 -->
    <header class="bed-map-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="bed-map-title">病区床位图</h2>
      <select v-model="wardId" class="bed-map-ward" aria-label="病区选择" @change="onWardChange">
        <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
          {{ ward.label }}
        </option>
      </select>
      <div class="bed-map-legend">
        <span
          v-for="state in BED_STATE_ORDER"
          :key="state"
          class="bed-map-legend-item"
          :class="`bed-map-legend--${state.toLowerCase()}`"
        >
          <i class="bed-map-legend-dot" />{{ stateLabel(state) }}
          <span class="fuy-num">{{ stateCounts[state] }}</span>
        </span>
      </div>
      <el-button class="bed-map-refresh" :loading="mapLoading" @click="loadMap">刷新</el-button>
    </header>

    <!-- 五态色标床位卡墙 -->
    <el-card class="bed-map-wall-card" :style="{ '--fuy-stagger-index': 1 }">
      <template #header>床位卡墙（{{ wardId }}）</template>
      <div v-loading="mapLoading" class="bed-map-wall">
        <div v-for="bed in bedList" :key="bed.bedId" class="bed-card" :class="stateClass(bed)">
          <div class="bed-card-head">
            <span class="fuy-num bed-card-no">{{ bed.bedNo }}</span>
            <span class="bed-card-state">{{ stateLabel(bed.bedStatus) }}</span>
          </div>
          <div class="bed-card-meta">
            <span>{{
              bed.allowGender === 'MALE'
                ? '限男'
                : bed.allowGender === 'FEMALE'
                  ? '限女'
                  : '不限性别'
            }}</span>
            <span v-if="bed.bedAttr && bed.bedAttr !== 'NORMAL'" class="fuy-num">{{
              bed.bedAttr
            }}</span>
          </div>
          <!-- 占用患者摘要（脱敏口径：就诊号+入科时点，姓名不出网） -->
          <div v-if="bed.bedStatus === 'OCCUPIED' && bed.occupiedVisit" class="bed-card-occupied">
            <span class="fuy-num">在院 {{ bed.occupiedVisit.visitId }}</span>
            <span class="bed-card-occupied-time"
              >入科 {{ formatTime(bed.occupiedVisit.admittedAt) }}</span
            >
          </div>
          <select
            v-if="actionsOf(bed).length > 0"
            class="bed-card-action"
            :aria-label="`床位 ${bed.bedNo ?? ''} 操作`"
            :disabled="actingBedId !== null"
            @change="onBedAction(bed, ($event.target as HTMLSelectElement).value)"
          >
            <option value="">操作…</option>
            <option v-for="action in actionsOf(bed)" :key="action.value" :value="action.value">
              {{ action.label }}
            </option>
          </select>
        </div>
        <el-empty v-if="bedList.length === 0" :image-size="72" description="当前病区暂无床位" />
      </div>
    </el-card>

    <!-- 转科转床弹窗（同病区轻量路径 / 跨病区四阶段编排） -->
    <el-dialog v-model="transferVisible" title="转科转床" width="440px">
      <p class="bed-transfer-patient fuy-num">
        在院 {{ transferVisitId }}
        <span v-if="transferBed?.bedNo" class="bed-transfer-from"
          >现床位 {{ transferBed?.bedNo }}</span
        >
      </p>
      <p class="bed-transfer-patient-hint">患者信息按脱敏口径展示，仅显示就诊号</p>
      <div class="bed-transfer-mode">
        <label class="bed-transfer-mode-item">
          <input
            v-model="transferMode"
            type="radio"
            value="change"
            @change="onTransferModeChange"
          />
          同病区转床（轻量，无停嘱）
        </label>
        <label class="bed-transfer-mode-item">
          <input
            v-model="transferMode"
            type="radio"
            value="transfer"
            @change="onTransferModeChange"
          />
          跨病区转科（四阶段编排）
        </label>
      </div>
      <template v-if="transferMode === 'transfer'">
        <label class="bed-transfer-label">目标病区</label>
        <select
          v-model="transferForm.toWardId"
          class="bed-transfer-input"
          aria-label="目标病区"
          @change="onTransferWardChange"
        >
          <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
            {{ ward.label }}
          </option>
        </select>
      </template>
      <label class="bed-transfer-label">目标床位（空床/预占床）</label>
      <select v-model="transferForm.toBedId" class="bed-transfer-input" aria-label="目标床位">
        <option value="">请选择目标床位</option>
        <option v-for="bed in targetBeds" :key="bed.bedId" :value="bed.bedId ?? ''">
          {{ bed.bedNo }}（{{ stateLabel(bed.bedStatus) }}）
        </option>
      </select>
      <!-- 四阶段提示文案（转科编排骨训口径；同病区转床仅床位流转） -->
      <p class="bed-transfer-stages">
        转科按四阶段编排执行：① 医嘱停嘱截断 → ② 在途执行三分 → ③ 床位流转 → ④
        事件通知；同病区转床仅执行床位流转（无停嘱）。
      </p>
      <template #footer>
        <el-button size="small" @click="transferVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="transferring"
          :disabled="transferring"
          @click="onTransferConfirm"
          >确认转科</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 床位图页布局：头部图例 + 卡墙 grid，五态色值全部经语义 token（禁自创色值） */
.bed-map-toolbar {
  align-items: center;
}

.bed-map-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.bed-map-ward {
  padding: 5px var(--fuy-space-2);
  border: 1px solid #dcdfe6;
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: #fff;
}

/* 五态图例（圆点取对应状态语义 token，计数 tabular-nums） */
.bed-map-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-3);
}

.bed-map-legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.bed-map-legend-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--fuy-radius-sm);
}

.bed-map-legend--free .bed-map-legend-dot {
  background: var(--fuy-color-bed-free);
}

.bed-map-legend--reserved .bed-map-legend-dot {
  background: var(--fuy-color-bed-reserved);
}

.bed-map-legend--occupied .bed-map-legend-dot {
  background: var(--fuy-color-bed-occupied);
}

.bed-map-legend--disinfecting .bed-map-legend-dot {
  background: var(--fuy-color-bed-disinfecting);
}

.bed-map-legend--maintenance .bed-map-legend-dot {
  background: var(--fuy-color-bed-maintenance);
}

/* 卡墙：临床一屏可见行数优先的紧凑网格 */
.bed-map-wall {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--fuy-space-3);
  min-height: 120px;
}

.bed-card {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  padding: var(--fuy-space-3);
  border-radius: var(--fuy-radius-lg);
  color: #fff;
}

.bed-card.fuy-bed-card--free {
  background: var(--fuy-color-bed-free);
}

.bed-card.fuy-bed-card--reserved {
  background: var(--fuy-color-bed-reserved);
}

.bed-card.fuy-bed-card--occupied {
  background: var(--fuy-color-bed-occupied);
}

.bed-card.fuy-bed-card--disinfecting {
  background: var(--fuy-color-bed-disinfecting);
}

.bed-card.fuy-bed-card--maintenance {
  background: var(--fuy-color-bed-maintenance);
}

.bed-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.bed-card-no {
  font-size: var(--fuy-font-size-2xl);
  font-weight: 700;
}

.bed-card-state {
  font-size: var(--fuy-font-size-xs);
  opacity: 0.92;
}

.bed-card-meta {
  display: flex;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  opacity: 0.85;
}

.bed-card-occupied {
  display: flex;
  flex-direction: column;
  font-size: var(--fuy-font-size-xs);
}

.bed-card-occupied-time {
  opacity: 0.85;
}

/* 床位动作 select（实底卡上白底承载，禁用态弱化） */
.bed-card-action {
  margin-top: auto;
  padding: 3px var(--fuy-space-1);
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-emphasis);
  background: #fff;
}

.bed-transfer-patient {
  margin: 0 0 var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-brand-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.bed-transfer-from {
  margin-left: var(--fuy-space-2);
}

.bed-transfer-patient-hint {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.bed-transfer-mode {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  margin-bottom: var(--fuy-space-3);
}

.bed-transfer-mode-item {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.bed-transfer-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.bed-transfer-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid #dcdfe6;
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: #fff;
}

.bed-transfer-stages {
  margin: 0;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-left: 3px solid var(--fuy-color-brand);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-xs);
  line-height: 1.6;
  color: var(--fuy-color-text-secondary);
}
</style>
