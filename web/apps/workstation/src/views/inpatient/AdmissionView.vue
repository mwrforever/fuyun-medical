<script setup lang="ts">
// 入院登记台页（/inpatient/admission，M04 FU-M04-01 前端面）：左=候床队列（状态筛选/
// 冻结排序标识透出/预约·登记·作废操作），右=住院证登记表单（患者搜索/医保类型/诊断摘要
// [脱敏提示]/开单医生）+ 候床证登记确认（医保类型登记，visit_id 签发由后端同事务承载）。
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 4xx 口径显式校验（禁裸 parse）；
// 失败弹错归响应拦截器（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底展示
// detail 原文——IP 域 4xx detail 已是中文业务口径）。患者身份按脱敏口径仅展示患者编号
// （后端 VO 契约即不含姓名），诊断摘要列标注脱敏提示。
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  ADMISSION_STATUS_OPTIONS,
  ADMISSION_TYPE_OPTIONS,
  admissions,
  beds,
  INSURANCE_TYPE_OPTIONS,
  SOURCE_TYPE_OPTIONS,
  WARD_OPTIONS,
} from '@/api/inpatient';
import type { AdmissionVO, BedMapVO } from '@/api/inpatient';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';

/** 住院证状态中文词表（CANCELLED/COMPLETED 兜底直显原文） */
const STATUS_LABELS: Record<string, string> = {
  WAITING: '候床中',
  SCHEDULED: '已预约',
  COMPLETED: '已入院',
  CANCELLED: '已作废',
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

/** 时点展示串（MM-dd HH:mm，建单时间列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/* ==================== 左栏：候床队列 ==================== */
const queueRows = ref<AdmissionVO[]>([]);
const queueTotal = ref(0);
const queueLoading = ref(false);
/** 状态筛选（空串=全部状态；常规视图传 WAITING/SCHEDULED） */
const statusFilter = ref('');

/** 加载候床队列（后端冻结排序=急诊优先＞预约时段＞候床时长，前端按返回序直出） */
async function loadQueue(): Promise<void> {
  queueLoading.value = true;
  try {
    const page = await admissions.list({
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      page: 0,
      size: 50,
    });
    queueRows.value = page.content ?? [];
    queueTotal.value = Number(page.total ?? '0');
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    queueLoading.value = false;
  }
}

/** 入院类型中文词表反查（行内类型列） */
function admissionTypeLabel(code: string | undefined): string {
  return ADMISSION_TYPE_OPTIONS.find((item) => item.code === code)?.label ?? code ?? '—';
}

/** 状态中文词表反查（行内状态 tag） */
function statusLabel(code: string | undefined): string {
  return STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/* ---------- 预约入院弹窗 ---------- */
const scheduleVisible = ref(false);
const scheduling = ref(false);
/** 预约目标候床证（弹窗上下文锚点） */
const scheduleTarget = ref<AdmissionVO | null>(null);
const scheduleForm = ref({ targetWardId: WARD_OPTIONS[0].code, targetBedId: '', expectDate: '' });
/** 目标病区空床清单（预约携床位时同事务联动预占） */
const freeBeds = ref<BedMapVO[]>([]);

/** 打开预约弹窗（仅 WAITING 态行暴露入口）并预载目标病区空床 */
async function openSchedule(row: AdmissionVO): Promise<void> {
  scheduleTarget.value = row;
  scheduleForm.value = { targetWardId: WARD_OPTIONS[0].code, targetBedId: '', expectDate: '' };
  scheduleVisible.value = true;
  try {
    const all = await beds.map(scheduleForm.value.targetWardId);
    freeBeds.value = all.filter((bed) => bed.bedStatus === 'FREE');
  } catch {
    // 失败弹错归响应拦截器；床位选择空表态
    freeBeds.value = [];
  }
}

/** 切换目标病区后重载空床清单（床位选择随之刷新） */
async function onScheduleWardChange(): Promise<void> {
  scheduleForm.value.targetBedId = '';
  try {
    const all = await beds.map(scheduleForm.value.targetWardId);
    freeBeds.value = all.filter((bed) => bed.bedStatus === 'FREE');
  } catch {
    freeBeds.value = [];
  }
}

/** 预约提交：预约时段显式校验（缺项零出网）→ 出网 → 成功关窗刷新队列。 */
async function onScheduleConfirm(): Promise<void> {
  if (scheduling.value) {
    return;
  }
  if (scheduleForm.value.expectDate === '') {
    void ElMessage.warning('请选择预约入院日期');
    return;
  }
  scheduling.value = true;
  try {
    await admissions.schedule(scheduleTarget.value?.admissionNo ?? '', {
      targetWardId: scheduleForm.value.targetWardId || undefined,
      targetBedId: scheduleForm.value.targetBedId || undefined,
      expectDate: scheduleForm.value.expectDate,
    });
    void ElMessage.success('预约已登记');
    scheduleVisible.value = false;
    await loadQueue();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    scheduling.value = false;
  }
}

/* ---------- 登记确认（右栏下半） ---------- */
const selectedAdmission = ref<AdmissionVO | null>(null);
const insuranceType = ref('');
const registering = ref(false);

/** 行内「登记」：选中候床证驱动右栏登记确认区 */
function selectForRegister(row: AdmissionVO): void {
  selectedAdmission.value = row;
  insuranceType.value = '';
}

/** 登记确认提交：未选证/未选医保显式校验零出网 → 出网 → 成功提示+队列刷新。
 * visit_id 签发与状态迁移全由后端同事务承载（红线 1），前端不本地拼装。 */
async function onRegister(): Promise<void> {
  if (registering.value) {
    return;
  }
  const target = selectedAdmission.value;
  if (target === null || target.admissionNo === undefined) {
    void ElMessage.warning('请先从候床队列选择住院证');
    return;
  }
  if (insuranceType.value === '') {
    void ElMessage.warning('请选择医保类型');
    return;
  }
  registering.value = true;
  try {
    const visit = await admissions.register(target.admissionNo, {
      insuranceType: insuranceType.value,
    });
    void ElMessage.success(`入院登记完成，visit 号 ${visit.visitId ?? ''}`);
    selectedAdmission.value = null;
    await loadQueue();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    registering.value = false;
  }
}

/* ---------- 作废 ---------- */
const cancelling = ref(false);

/** 住院证作废（中档确认带回显；终态证由后端 IP-1002 把守） */
async function onCancelAdmission(row: AdmissionVO): Promise<void> {
  if (cancelling.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `即将作废住院证 ${row.admissionNo ?? ''}，预约床位将联动释放，确认？`,
      '住院证作废确认',
      { confirmButtonText: '确认作废', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  cancelling.value = true;
  try {
    await admissions.cancel(row.admissionNo ?? '');
    void ElMessage.success(`住院证已作废：${row.admissionNo ?? ''}`);
    await loadQueue();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    cancelling.value = false;
  }
}

/* ==================== 右栏：住院证登记表单 ==================== */
const creating = ref(false);
/** 患者搜索态（检索词/脱敏结果行/选中患者） */
const patientKeyword = ref('');
const patientResults = ref<PatientVO[]>([]);
const selectedPatientId = ref('');
const createForm = ref({
  sourceType: SOURCE_TYPE_OPTIONS[0].code,
  admissionType: ADMISSION_TYPE_OPTIONS[0].code,
  expectDate: '',
  diagnosisSummary: '',
  issuedDoctorId: '',
});

/** 患者检索（脱敏分页；选中行回填患者编号） */
async function onSearchPatient(): Promise<void> {
  if (patientKeyword.value.trim() === '') {
    void ElMessage.warning('请输入检索词（姓名/证件号/手机号）');
    return;
  }
  try {
    const page = await searchPatients({ keyword: patientKeyword.value.trim(), page: 0, size: 20 });
    patientResults.value = page.content;
    if (patientResults.value.length === 0) {
      void ElMessage.warning('未检索到患者，请核对检索词');
    }
  } catch {
    // 失败弹错归响应拦截器
  }
}

/** 住院证创建：患者/开单医生显式校验零出网 → 出网 → 成功清表单+刷新队列。 */
async function onCreate(): Promise<void> {
  if (creating.value) {
    return;
  }
  if (selectedPatientId.value === '') {
    void ElMessage.warning('请先搜索并选择患者');
    return;
  }
  if (createForm.value.issuedDoctorId.trim() === '') {
    void ElMessage.warning('请填写开单医生工号');
    return;
  }
  creating.value = true;
  try {
    await admissions.create({
      patientId: selectedPatientId.value,
      sourceType: createForm.value.sourceType,
      admissionType: createForm.value.admissionType,
      expectDate: createForm.value.expectDate === '' ? undefined : createForm.value.expectDate,
      diagnosisSummary:
        createForm.value.diagnosisSummary.trim() === ''
          ? undefined
          : createForm.value.diagnosisSummary.trim(),
      issuedDoctorId: createForm.value.issuedDoctorId.trim(),
    });
    void ElMessage.success('住院证已创建，进入候床队列');
    selectedPatientId.value = '';
    patientResults.value = [];
    patientKeyword.value = '';
    createForm.value = {
      sourceType: SOURCE_TYPE_OPTIONS[0].code,
      admissionType: ADMISSION_TYPE_OPTIONS[0].code,
      expectDate: '',
      diagnosisSummary: '',
      issuedDoctorId: '',
    };
    await loadQueue();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/** 登记确认区回显（选中候床证摘要行） */
const selectedSummary = computed(() => {
  const row = selectedAdmission.value;
  if (row === null) {
    return null;
  }
  return `${row.admissionNo ?? ''} · 患者 ${row.patientId ?? '—'} · ${statusLabel(row.status)}`;
});

onMounted(() => {
  void loadQueue();
});
</script>

<template>
  <div class="fuy-page admission-view fuy-stagger">
    <!-- 页头：标题 + 冻结排序标识 + 刷新 -->
    <header class="admission-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="admission-title">入院登记台</h2>
      <span class="admission-sort-hint">排序：急诊优先 ＞ 预约时段 ＞ 候床时长</span>
      <el-button class="admission-refresh" :loading="queueLoading" @click="loadQueue"
        >刷新</el-button
      >
    </header>

    <el-row class="fuy-stagger" :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左栏：候床队列 -->
      <el-col :md="24" :lg="14">
        <el-card class="admission-queue-card">
          <template #header>
            <div class="admission-card-head">
              <span>候床队列（共 {{ queueTotal }} 条）</span>
              <el-radio-group v-model="statusFilter" size="small" @change="loadQueue">
                <el-radio-button label="">全部</el-radio-button>
                <el-radio-button
                  v-for="item in ADMISSION_STATUS_OPTIONS"
                  :key="item.code"
                  :label="item.code"
                  >{{ item.label }}</el-radio-button
                >
              </el-radio-group>
            </div>
          </template>
          <div v-loading="queueLoading">
            <el-table v-if="queueRows.length > 0" :data="queueRows" class="fuy-dense" size="small">
              <el-table-column label="住院证号" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.admissionNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="130">
                <template #default="{ row }">
                  {{ admissionTypeLabel(row.admissionType) }}
                  <!-- 急诊优先排序标识（后端置顶排序的前端透出，机器判据类） -->
                  <span v-if="row.admissionType === 'EMERGENCY'" class="admission-emergency-flag"
                    >急诊优先</span
                  >
                </template>
              </el-table-column>
              <el-table-column label="患者编号（脱敏）" min-width="150">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.patientId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="预约时段" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.expectDate ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="诊断摘要（脱敏）" min-width="150">
                <template #default="{ row }">
                  <span class="admission-diagnosis" :title="row.diagnosisSummary">{{
                    row.diagnosisSummary ?? '—'
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="建单" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'WAITING'"
                    link
                    type="primary"
                    size="small"
                    @click="openSchedule(row)"
                    >预约</el-button
                  >
                  <el-button
                    v-if="row.status === 'WAITING' || row.status === 'SCHEDULED'"
                    link
                    type="primary"
                    size="small"
                    @click="selectForRegister(row)"
                    >登记</el-button
                  >
                  <el-button
                    v-if="row.status === 'WAITING' || row.status === 'SCHEDULED'"
                    link
                    type="danger"
                    size="small"
                    :disabled="cancelling"
                    @click="onCancelAdmission(row)"
                    >作废</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="候床队列为空" />
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：住院证登记 + 登记确认 -->
      <el-col :md="24" :lg="10">
        <el-card class="admission-form-card">
          <template #header>住院证登记</template>
          <el-form label-position="top" size="small">
            <div class="admission-search-row">
              <input
                v-model="patientKeyword"
                class="admission-input"
                placeholder="姓名/证件号/手机号"
                @keyup.enter="onSearchPatient"
              />
              <el-button size="small" @click="onSearchPatient">搜索</el-button>
            </div>
            <label class="admission-field-label">患者（检索结果，档案按脱敏口径展示）</label>
            <select
              v-model="selectedPatientId"
              class="admission-input"
              aria-label="患者检索结果"
              :disabled="patientResults.length === 0"
            >
              <option value="">请选择患者</option>
              <option v-for="p in patientResults" :key="p.patientId" :value="p.patientId ?? ''">
                {{ p.name }}（{{ p.idCardNo ?? '证件未登记' }}）
              </option>
            </select>
            <div class="admission-field-grid">
              <div class="admission-field">
                <label class="admission-field-label">来源类型</label>
                <select v-model="createForm.sourceType" class="admission-input">
                  <option v-for="item in SOURCE_TYPE_OPTIONS" :key="item.code" :value="item.code">
                    {{ item.label }}
                  </option>
                </select>
              </div>
              <div class="admission-field">
                <label class="admission-field-label">入院类型</label>
                <select v-model="createForm.admissionType" class="admission-input">
                  <option
                    v-for="item in ADMISSION_TYPE_OPTIONS"
                    :key="item.code"
                    :value="item.code"
                  >
                    {{ item.label }}
                  </option>
                </select>
              </div>
              <div class="admission-field">
                <label class="admission-field-label">预期入院日期</label>
                <input
                  v-model="createForm.expectDate"
                  type="date"
                  class="admission-input fuy-num"
                />
              </div>
              <div class="admission-field">
                <label class="admission-field-label">开单医生工号</label>
                <input
                  v-model="createForm.issuedDoctorId"
                  class="admission-input"
                  placeholder="开单医生工号"
                />
              </div>
            </div>
            <label class="admission-field-label"
              >诊断摘要（敏感信息：展示与留存按脱敏口径，≤255 字）</label
            >
            <textarea
              v-model="createForm.diagnosisSummary"
              class="admission-input admission-textarea"
              rows="2"
              maxlength="255"
              placeholder="入院诊断摘要"
            ></textarea>
            <el-button
              type="primary"
              class="admission-submit"
              :loading="creating"
              :disabled="creating"
              @click="onCreate"
              >创建住院证</el-button
            >
          </el-form>
        </el-card>

        <el-card class="admission-form-card">
          <template #header>入院登记确认</template>
          <p class="admission-selected" :class="{ 'is-empty': selectedSummary === null }">
            {{ selectedSummary ?? '从候床队列点击「登记」选择住院证' }}
          </p>
          <label class="admission-field-label">医保类型（登记必填，visit 号由系统签发）</label>
          <select v-model="insuranceType" class="admission-input" aria-label="医保类型">
            <option value="">请选择医保类型</option>
            <option v-for="item in INSURANCE_TYPE_OPTIONS" :key="item.code" :value="item.code">
              {{ item.label }}
            </option>
          </select>
          <el-button
            type="primary"
            class="admission-submit"
            :loading="registering"
            :disabled="registering || selectedAdmission === null"
            @click="onRegister"
            >登记确认</el-button
          >
        </el-card>
      </el-col>
    </el-row>

    <!-- 预约入院弹窗（携床位时同事务联动预占） -->
    <el-dialog v-model="scheduleVisible" title="预约入院" width="420px">
      <p class="admission-schedule-target fuy-num">
        住院证 {{ scheduleTarget?.admissionNo ?? '' }}
      </p>
      <label class="admission-field-label">目标病区</label>
      <select
        v-model="scheduleForm.targetWardId"
        class="admission-input"
        @change="onScheduleWardChange"
      >
        <option v-for="ward in WARD_OPTIONS" :key="ward.code" :value="ward.code">
          {{ ward.label }}
        </option>
      </select>
      <label class="admission-field-label">目标床位（空床，可空=到院再签）</label>
      <select v-model="scheduleForm.targetBedId" class="admission-input">
        <option value="">暂不指定床位</option>
        <option v-for="bed in freeBeds" :key="bed.bedId" :value="bed.bedId ?? ''">
          {{ bed.bedNo }}
        </option>
      </select>
      <label class="admission-field-label">预约入院日期（必填）</label>
      <input
        v-model="scheduleForm.expectDate"
        type="date"
        class="admission-input fuy-num"
        aria-label="预约入院日期"
      />
      <template #footer>
        <el-button size="small" @click="scheduleVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="scheduling"
          :disabled="scheduling"
          @click="onScheduleConfirm"
          >确认预约</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 两栏登记台布局：左队列右表单，token 取色禁自创色值 */
.admission-toolbar {
  align-items: center;
}

.admission-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.admission-sort-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.admission-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

/* 急诊优先排序标识（后端冻结排序的前端透出） */
.admission-emergency-flag {
  margin-left: var(--fuy-space-1);
  padding: 0 var(--fuy-space-1);
  border-radius: var(--fuy-radius-sm);
  background: var(--fuy-color-triage-l1);
  color: #fff;
  font-size: var(--fuy-font-size-xs);
}

.admission-diagnosis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

/* 表单基元：native select/input 与 EP 密度口径对齐（存量页面同款） */
.admission-search-row {
  display: flex;
  gap: var(--fuy-space-2);
}

.admission-input {
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

.admission-textarea {
  resize: vertical;
}

.admission-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.admission-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.admission-submit {
  width: 100%;
  margin-top: var(--fuy-space-2);
}

.admission-form-card + .admission-form-card {
  margin-top: var(--fuy-space-4);
}

.admission-selected {
  margin: 0 0 var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-brand-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.admission-selected.is-empty {
  background: var(--fuy-palette-gray-50);
  color: var(--fuy-color-text-secondary);
}

.admission-schedule-target {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}
</style>
