<script setup lang="ts">
// 护士工作站页（/nursing/ward，M05 前端面，设计文档 §3 八区块）：病区选择与入区登记 →
// 床位序患者卡墙（选中驱动全页患者上下文）→ 患者详情/责任护士分配 → 体征录入与待复核 →
// 体温单渲染（§5 符号契约）与特殊事件 → 护理评估（量表打分判级）→ 护理任务与交接班双签。
// EX-47 巨型组件拆分（web 宪法 B.2-6）：状态与业务逻辑按作业面下沉 composables/
// （卡墙底座/入区登记/最新体征/分配/体征/体温单/特殊事件/评估/任务/交接班/出入量），
// 视图只做组装与跨面联动编排；全部写操作自带在途守卫（入口早退先于一切 await）+
// 4xx 口径显式校验（禁裸 parse，数值字段一律文本承载经正则+范围双验）；失败弹错归响应
// 拦截器（AxiosError 防双弹，非 AxiosError 的业务拒绝对象由 surfaceBizError 兜底展示
// detail 原文）。体温单坐标计算在 tempChart.ts 纯函数（spec 双层断言），视图仅做 SVG
// 映射渲染。
import { computed, onMounted } from 'vue';
// ElMessage/ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import {
  CONDITION_TAG_OPTIONS,
  NURSING_LEVEL_OPTIONS,
  SHIFT_OPTIONS,
  SPECIAL_EVENT_OPTIONS,
  TEMP_SITE_OPTIONS,
  WARD_OPTIONS,
} from '@/api/nursing';
import { useAuthStore } from '@/stores/auth';
import { AXIS_WIDTH, DAILY_ROWS } from './tempChart';
import { formatTime, splitTags } from './wardBoardShared';
import { tempSiteMark, useLatestVitals } from './composables/useLatestVitals';
import { ASSIGNMENT_TYPE_OPTIONS, useAssignments } from './composables/useAssignments';
import { useHandover } from './composables/useHandover';
import { IO_TYPE_OPTIONS, useIoRecords } from './composables/useIoRecords';
import {
  RISK_LEVEL_META,
  SCALE_TYPE_LABELS,
  useNursingAssessment,
} from './composables/useNursingAssessment';
import { TASK_TYPE_LABELS, useNursingTasks } from './composables/useNursingTasks';
import { useSpecialEvents } from './composables/useSpecialEvents';
import { useTempChart } from './composables/useTempChart';
import { useVitalSigns } from './composables/useVitalSigns';
import { useWardContext } from './composables/useWardContext';
import { useWardRegister } from './composables/useWardRegister';

/** 护理级别 → 徽标类映射（后端 NursingLevel 三值；--l2 留全族定义防词表扩值 §2.3） */
const NURSING_LEVEL_BADGE: Record<string, string> = {
  SPECIAL: 'fuy-nursing-level-badge--special',
  CRITICAL: 'fuy-nursing-level-badge--l1',
  NORMAL: 'fuy-nursing-level-badge--l3',
};

/** 护理级别中文词表 */
const NURSING_LEVEL_LABELS: Record<string, string> = {
  SPECIAL: '特级护理',
  CRITICAL: '病重护理',
  NORMAL: '普通护理',
};

const auth = useAuthStore();
/** 当班护士（交接班确认回显的交班人锚点） */
const operatorName = computed(() => auth.user?.displayName ?? '—');

/* ==================== 作业面组装（跨面联动编排，时序与拆分前逐字一致） ==================== */
// 卡墙底座先行创建：病区/班次/选中患者上下文由此派生；跨面联动经惰性回调注入（求值时
// 各作业面均已初始化，无循环依赖）。换患者复位含四处草稿（体征/评估+结果条/出入量/
// 特殊事件——后两处为 BUG-15 同类与残留清理修复项）；患者上下文加载含 EX-45 竞态守卫。
const ward = useWardContext({
  getWardReloads: () => [
    vitals.loadPendingReview(),
    assign.loadAssignments(),
    nursingTasks.loadTasks(),
    handoverState.loadHandoverOfDay(),
  ],
  onPatientSwitchReset: () => {
    vitals.resetVitalForm();
    assessment.resetDraft();
    ioRecordsState.resetDraft();
    specialEvents.resetDraft();
  },
  onPatientSwitchLoad: () => {
    void latestVitalsState.loadLatestVitals();
    void tempChart.loadChart();
    void assessment.loadAssessmentHistory();
  },
});
const register = useWardRegister({ wardId: ward.wardId, reloadWard: ward.loadWard });
const latestVitalsState = useLatestVitals({
  selectedDetail: ward.selectedDetail,
  selectedVisitId: ward.selectedVisitId,
});
const assign = useAssignments({ wardId: ward.wardId, shiftCode: ward.shiftCode });
const tempChart = useTempChart({
  selectedDetail: ward.selectedDetail,
  selectedVisitId: ward.selectedVisitId,
});
const specialEvents = useSpecialEvents({
  selectedDetail: ward.selectedDetail,
  onEventRecorded: tempChart.loadChart,
});
const assessment = useNursingAssessment({
  selectedDetail: ward.selectedDetail,
  selectedVisitId: ward.selectedVisitId,
});
const nursingTasks = useNursingTasks({ wardId: ward.wardId, detailMap: ward.detailMap });
const handoverState = useHandover({
  wardId: ward.wardId,
  shiftCode: ward.shiftCode,
  operatorName,
});
const ioRecordsState = useIoRecords({ selectedDetail: ward.selectedDetail });
const vitals = useVitalSigns({
  selectedPatient: ward.selectedPatient,
  selectedDetail: ward.selectedDetail,
  wardId: ward.wardId,
  // 录入成功联动：先体温单后简报（拆分前 await 链同序）
  onRecorded: async () => {
    await tempChart.loadChart();
    await latestVitalsState.loadLatestVitals();
  },
});

/* ==================== 模板绑定面（组装解包，模板零改动） ==================== */
const {
  wardId,
  shiftCode,
  wardLoading,
  detailMap,
  selectedVisitId,
  sortedPatients,
  wardCounts,
  bedFlags,
  inFlightCount,
  dutyNurseId,
  selectedDetail,
  RISK_FLAG_LABELS,
  loadWard,
  onWardChange,
  selectPatient,
  onRemovePatient,
} = ward;
const { registerVisible, registering, registerForm, onRegister } = register;
const { latestVitals, latestVitalsLoading } = latestVitalsState;
const {
  assignmentList,
  assignmentLoading,
  assigning,
  assignFormVisible,
  assignForm,
  loadAssignments,
  onAssign,
  onUnassign,
} = assign;
const {
  vitalForm,
  recording,
  onVitalFieldChange,
  onRecordVitals,
  pendingList,
  confirmingId,
  onConfirmVital,
  onRejectVital,
} = vitals;
const { chartMonth, chartLoading, prevMonthDisabled, onMonthChange, chartModel, dailyCellValue } =
  tempChart;
const { specialEventType, specialEventRemark, specialEventRecording, onAddSpecialEvent } =
  specialEvents;
const {
  scaleList,
  scaleType,
  currentScale,
  scaleAnswers,
  assessing,
  assessResult,
  assessHistory,
  loadScales,
  onScaleChange,
  onAssess,
} = assessment;
const {
  actingTaskNo,
  taskList,
  taskLoading,
  taskStatusFilter,
  loadTasks,
  taskRowClass,
  taskStatusMeta,
  taskPatientLabel,
  onCompleteTask,
  onCancelTask,
} = nursingTasks;
const {
  handover,
  handoverLoading,
  generating,
  completingHandover,
  incomingNurseId,
  loadHandoverOfDay,
  onGenerateHandover,
  onCompleteHandover,
} = handoverState;
const { ioVisible, ioForm, ioRecording, onCreateIoRecord } = ioRecordsState;

/** 班次切换：分配与交接班随班次上下文重载（时序与拆分前一致） */
function onShiftChange(): void {
  void loadAssignments();
  void loadHandoverOfDay();
}

onMounted(() => {
  void loadScales();
  void loadWard();
});
</script>

<template>
  <div class="fuy-page ward-board fuy-stagger">
    <!-- ① 病区选择 + 病情计数 + 待复核徽标 + 入区登记 -->
    <header class="ward-board-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <el-select v-model="wardId" class="ward-board-ward" @change="onWardChange">
        <el-option
          v-for="ward in WARD_OPTIONS"
          :key="ward.code"
          :label="ward.label"
          :value="ward.code"
        />
      </el-select>
      <div class="ward-board-counts">
        <span class="fuy-num ward-board-count-total">在区 {{ wardCounts.total }}</span>
        <span class="fuy-num ward-board-count-critical">危 {{ wardCounts.critical }}</span>
        <span class="fuy-num ward-board-count-severe">重 {{ wardCounts.severe }}</span>
      </div>
      <a class="ward-board-review-anchor" href="#ward-pending-review">
        待复核
        <span v-if="pendingList.length > 0" class="ward-board-review-badge fuy-num">{{
          pendingList.length
        }}</span>
      </a>
      <el-button
        type="primary"
        class="ward-board-register-btn"
        :loading="registering"
        :disabled="registering"
        @click="registerVisible = true"
        >入区登记</el-button
      >
      <el-button class="ward-board-refresh" :loading="wardLoading" @click="loadWard"
        >刷新</el-button
      >
    </header>

    <!-- ② 床位序患者卡墙（全宽） -->
    <el-card class="ward-board-wall-card" :style="{ '--fuy-stagger-index': 1 }">
      <template #header>床位卡墙（{{ wardId }}）</template>
      <div v-loading="wardLoading" class="ward-board-wall">
        <TransitionGroup name="fuy-flip" tag="div" class="ward-board-wall-grid">
          <button
            v-for="patient in sortedPatients"
            :key="patient.visitId"
            type="button"
            class="ward-bed-card"
            :class="{ 'is-selected': selectedVisitId === patient.visitId }"
            @click="selectPatient(patient)"
          >
            <div class="ward-bed-card-head">
              <span class="fuy-num ward-bed-no">{{ patient.bedNo }}</span>
              <span class="ward-bed-name">{{
                detailMap[patient.visitId ?? '']?.patientName ?? '—'
              }}</span>
              <span
                v-if="NURSING_LEVEL_BADGE[patient.nursingLevel ?? ''] !== undefined"
                class="fuy-nursing-level-badge"
                :class="NURSING_LEVEL_BADGE[patient.nursingLevel ?? '']"
                >{{
                  NURSING_LEVEL_LABELS[patient.nursingLevel ?? ''] ?? patient.nursingLevel
                }}</span
              >
            </div>
            <div class="ward-bed-flags">
              <span
                v-for="flag in bedFlags(detailMap[patient.visitId ?? '']).shown"
                :key="flag.text + flag.cls"
                class="fuy-nursing-flag"
                :class="flag.cls"
                >{{ flag.text }}</span
              >
              <span
                v-if="bedFlags(detailMap[patient.visitId ?? '']).overflow > 0"
                class="ward-bed-overflow"
                >+{{ bedFlags(detailMap[patient.visitId ?? '']).overflow }}</span
              >
            </div>
            <div class="ward-bed-foot">
              <span>责任 {{ dutyNurseId(detailMap[patient.visitId ?? '']) }}</span>
              <span>{{ inFlightCount(detailMap[patient.visitId ?? '']) }} 在途</span>
            </div>
          </button>
        </TransitionGroup>
        <el-empty
          v-if="sortedPatients.length === 0"
          :image-size="72"
          description="当前病区暂无在区患者"
        />
      </div>
    </el-card>

    <!-- 操作层三列：③④ / ⑤⑦ / ⑧（行级 stagger 承载三列级联） -->
    <el-row class="fuy-stagger" :gutter="16" :style="{ '--fuy-stagger-index': 2 }">
      <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 1 }">
        <!-- ③ 患者详情面板 -->
        <el-card class="ward-board-mid-card">
          <template #header>患者详情</template>
          <div v-loading="latestVitalsLoading">
            <template v-if="selectedDetail !== undefined">
              <div class="ward-detail-head">
                <span class="ward-detail-name">{{ selectedDetail.patientName }}</span>
                <span class="ward-detail-base"
                  >{{ selectedDetail.gender ?? '—' }} / {{ selectedDetail.age ?? '—' }}岁</span
                >
                <span
                  v-if="NURSING_LEVEL_BADGE[selectedDetail.nursingLevel ?? ''] !== undefined"
                  class="fuy-nursing-level-badge"
                  :class="NURSING_LEVEL_BADGE[selectedDetail.nursingLevel ?? '']"
                  >{{ NURSING_LEVEL_LABELS[selectedDetail.nursingLevel ?? ''] ?? '' }}</span
                >
              </div>
              <div class="ward-detail-allergy">
                <span
                  class="fuy-nursing-flag fuy-nursing-flag--danger"
                  :class="{ 'fuy-nursing-flag--outline': selectedDetail.allergyFlag !== true }"
                  >敏</span
                >
                <template v-if="selectedDetail.allergyFlag === true">
                  <span class="ward-detail-allergy-list">{{
                    (selectedDetail.allergies ?? [])
                      .map((item) => item.itemName ?? item.itemCode ?? '')
                      .filter((name) => name !== '')
                      .join('、') || '过敏源未登记'
                  }}</span>
                </template>
                <span v-else class="ward-detail-allergy-none">无已知过敏</span>
              </div>
              <div class="ward-detail-risk">
                <span
                  v-for="risk in splitTags(selectedDetail.riskFlags)"
                  :key="risk"
                  class="fuy-nursing-flag fuy-nursing-flag--outline"
                  >{{ RISK_FLAG_LABELS[risk] ?? risk }}</span
                >
                <span
                  v-if="splitTags(selectedDetail.riskFlags).length === 0"
                  class="ward-detail-allergy-none"
                  >无风险标识</span
                >
              </div>
              <el-descriptions :column="1" border size="small" class="ward-detail-desc">
                <el-descriptions-item label="床位">
                  <span class="fuy-num">{{ selectedDetail.bedNo }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="责任护士">
                  {{ dutyNurseId(selectedDetail) }}
                </el-descriptions-item>
                <el-descriptions-item label="在途任务">
                  {{ inFlightCount(selectedDetail) }} 条
                </el-descriptions-item>
              </el-descriptions>
              <!-- 最新体征行（前端另调 GET /vital-signs 组装，近 24h 末次值） -->
              <div v-if="latestVitals !== null" class="ward-detail-vitals">
                <span class="fuy-num"
                  >体温 {{ latestVitals.temperature ?? '—'
                  }}{{ tempSiteMark(latestVitals.tempSite) }}</span
                >
                <span class="fuy-num">脉搏 {{ latestVitals.pulse ?? '—' }}</span>
                <span class="fuy-num">呼吸 {{ latestVitals.respiration ?? '—' }}</span>
                <span class="fuy-num"
                  >血压 {{ latestVitals.systolicBp ?? '—' }}/{{
                    latestVitals.diastolicBp ?? '—'
                  }}</span
                >
                <span class="fuy-num">血氧 {{ latestVitals.spo2 ?? '—' }}%</span>
                <span class="ward-detail-vitals-time">{{
                  formatTime(latestVitals.measuredAt)
                }}</span>
              </div>
            </template>
            <el-empty v-else :image-size="72" description="从床位卡墙选择患者查看详情" />
          </div>
        </el-card>

        <!-- ④ 责任护士分配 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>责任护士分配</span>
              <el-select v-model="shiftCode" class="ward-board-shift" @change="onShiftChange">
                <el-option
                  v-for="shift in SHIFT_OPTIONS"
                  :key="shift.code"
                  :label="shift.label"
                  :value="shift.code"
                />
              </el-select>
            </div>
          </template>
          <div v-loading="assignmentLoading" class="ward-assign-list">
            <div v-for="row in assignmentList" :key="String(row.id)" class="ward-assign-row">
              <span class="ward-assign-nurse">{{ row.nurseId }}</span>
              <span class="ward-assign-scope fuy-num">{{
                row.assignmentType === 'BED' ? `管床 ${row.bedNo ?? '—'}` : '责任患者'
              }}</span>
              <el-button link type="danger" size="small" @click="onUnassign(row)">移除</el-button>
            </div>
            <el-empty
              v-if="assignmentList.length === 0"
              :image-size="56"
              description="本班次暂无分配，请新增"
            />
          </div>
          <div v-if="!assignFormVisible" class="ward-assign-add">
            <el-button size="small" @click="assignFormVisible = true">新增分配</el-button>
          </div>
          <div v-else class="ward-assign-form">
            <el-input v-model="assignForm.nurseId" placeholder="护士工号" />
            <el-select v-model="assignForm.assignmentType">
              <el-option
                v-for="item in ASSIGNMENT_TYPE_OPTIONS"
                :key="item.code"
                :label="item.label"
                :value="item.code"
              />
            </el-select>
            <el-input
              v-if="assignForm.assignmentType === 'BED'"
              v-model="assignForm.bedNo"
              placeholder="床位号"
            />
            <el-input v-else v-model="assignForm.patientId" placeholder="患者 ID（责任分配）" />
            <div class="ward-assign-form-actions">
              <el-button
                type="primary"
                size="small"
                :loading="assigning"
                :disabled="assigning"
                @click="onAssign"
                >确认</el-button
              >
              <el-button size="small" @click="assignFormVisible = false">取消</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="12" :style="{ '--fuy-stagger-index': 2 }">
        <!-- ⑤ 体征录入 + 待复核列表 -->
        <el-card id="ward-pending-review" class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>体征录入（{{ selectedDetail?.bedNo ?? '未选患者' }}）</span>
              <el-button link type="primary" size="small" @click="ioVisible = !ioVisible">
                {{ ioVisible ? '收起出入量' : '出入量快录' }}
              </el-button>
            </div>
          </template>
          <el-form
            label-position="top"
            size="small"
            class="ward-vital-form"
            :disabled="selectedDetail === undefined"
          >
            <div class="ward-vital-grid">
              <div class="ward-vital-field">
                <label class="ward-vital-label">体温（℃）</label>
                <div class="ward-vital-pair">
                  <input
                    v-model="vitalForm.temperature"
                    class="ward-vital-input ward-vital-input-num"
                    inputmode="decimal"
                    placeholder="36.5"
                    :disabled="selectedDetail === undefined"
                    @change="onVitalFieldChange('temperature')"
                  />
                  <select
                    v-model="vitalForm.tempSite"
                    class="ward-vital-input"
                    :disabled="selectedDetail === undefined"
                  >
                    <option v-for="site in TEMP_SITE_OPTIONS" :key="site.code" :value="site.code">
                      {{ site.label }}
                    </option>
                  </select>
                </div>
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">脉搏（次/分）</label>
                <input
                  v-model="vitalForm.pulse"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="80"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('pulse')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">呼吸（次/分）</label>
                <input
                  v-model="vitalForm.respiration"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="18"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('respiration')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">血压（mmHg）</label>
                <div class="ward-vital-pair">
                  <input
                    v-model="vitalForm.systolicBp"
                    class="ward-vital-input ward-vital-input-num"
                    inputmode="numeric"
                    placeholder="120"
                    :disabled="selectedDetail === undefined"
                    @change="onVitalFieldChange('systolicBp')"
                  />
                  <input
                    v-model="vitalForm.diastolicBp"
                    class="ward-vital-input ward-vital-input-num"
                    inputmode="numeric"
                    placeholder="80"
                    :disabled="selectedDetail === undefined"
                    @change="onVitalFieldChange('diastolicBp')"
                  />
                </div>
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">血氧（%）</label>
                <input
                  v-model="vitalForm.spo2"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="98"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('spo2')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">体重（kg）</label>
                <input
                  v-model="vitalForm.weight"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="decimal"
                  placeholder="60"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('weight')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">身高（cm）</label>
                <input
                  v-model="vitalForm.height"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="170"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('height')"
                />
              </div>
              <div class="ward-vital-field">
                <label class="ward-vital-label">疼痛评分（NRS）</label>
                <input
                  v-model="vitalForm.painScore"
                  class="ward-vital-input ward-vital-input-num"
                  inputmode="numeric"
                  placeholder="0"
                  :disabled="selectedDetail === undefined"
                  @change="onVitalFieldChange('painScore')"
                />
              </div>
            </div>
            <el-button
              type="primary"
              class="ward-vital-submit"
              :loading="recording"
              :disabled="recording || selectedDetail === undefined"
              @click="onRecordVitals"
              >录入体征</el-button
            >
            <p v-if="selectedDetail === undefined" class="ward-vital-hint">先选择患者</p>
          </el-form>
          <!-- 出入量快录（收起形态默认） -->
          <div v-if="ioVisible" class="ward-io-form">
            <select v-model="ioForm.ioType" class="ward-vital-input">
              <option v-for="item in IO_TYPE_OPTIONS" :key="item.code" :value="item.code">
                {{ item.label }}
              </option>
            </select>
            <input v-model="ioForm.itemCode" class="ward-vital-input" placeholder="项目编码" />
            <input
              v-model="ioForm.quantity"
              class="ward-vital-input ward-vital-input-num"
              inputmode="decimal"
              placeholder="数量"
            />
            <input
              v-model="ioForm.unit"
              class="ward-vital-input ward-vital-unit"
              placeholder="单位"
            />
            <el-button
              size="small"
              :loading="ioRecording"
              :disabled="ioRecording"
              @click="onCreateIoRecord"
              >记录出入量</el-button
            >
          </div>
          <!-- 待复核列表（确认成功该行移除，spec 冻结语义） -->
          <h4 class="fuy-section-title">待复核体征</h4>
          <el-table
            v-if="pendingList.length > 0"
            :data="pendingList"
            class="fuy-dense"
            size="small"
          >
            <el-table-column label="时点" width="120">
              <template #default="{ row }">
                <span class="fuy-num">{{ formatTime(row.measuredAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="体温" width="80">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.temperature ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="脉搏" width="70">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.pulse ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="呼吸" width="70">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.respiration ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="血压" width="100">
              <template #default="{ row }">
                <span class="fuy-num"
                  >{{ row.systolicBp ?? '—' }}/{{ row.diastolicBp ?? '—' }}</span
                >
              </template>
            </el-table-column>
            <el-table-column label="来源" width="80">
              <template #default="{ row }">
                {{ row.source === 'IOT' ? 'IoT' : row.source === 'PDA' ? 'PDA' : '一体机' }}
              </template>
            </el-table-column>
            <el-table-column label="质量" width="90">
              <template #default="{ row }">
                <span class="fuy-num">{{ row.iotQuality ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" class-name="fuy-ops-8">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="confirmingId !== null"
                  @click="onConfirmVital(row)"
                  >确认</el-button
                >
                <el-button
                  link
                  type="danger"
                  size="small"
                  :disabled="confirmingId !== null"
                  @click="onRejectVital(row)"
                  >驳回</el-button
                >
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else :image-size="56" description="暂无待复核体征" />
        </el-card>

        <!-- ⑦ 护理评估 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>护理评估（{{ selectedDetail?.patientName ?? '未选患者' }}）</span>
              <el-select v-model="scaleType" class="ward-board-scale" @change="onScaleChange">
                <el-option
                  v-for="scale in scaleList"
                  :key="scale.scaleType"
                  :label="SCALE_TYPE_LABELS[scale.scaleType ?? ''] ?? scale.scaleType"
                  :value="scale.scaleType ?? ''"
                />
              </el-select>
            </div>
          </template>
          <template v-if="currentScale !== undefined">
            <div
              v-for="(code, index) in currentScale.itemCodes ?? []"
              :key="code"
              class="ward-scale-item"
            >
              <span class="ward-scale-item-name">{{
                currentScale.itemLabels?.[index] ?? code
              }}</span>
              <el-radio-group v-model="scaleAnswers[code]" size="small">
                <el-radio-button
                  v-for="score in currentScale.choices?.[code] ?? []"
                  :key="score"
                  :value="score"
                >
                  {{ score }}
                </el-radio-button>
              </el-radio-group>
            </div>
            <el-button
              type="primary"
              class="ward-scale-submit"
              :loading="assessing"
              :disabled="assessing || selectedDetail === undefined"
              @click="onAssess"
              >提交评估</el-button
            >
            <!-- 结果条（§3.9 契约：HIGH 挂 .fuy-assess-result--high + 「高风险」文案） -->
            <div
              v-if="assessResult !== null"
              class="ward-assess-result"
              :class="{ 'fuy-assess-result--high': assessResult.riskLevel === 'HIGH' }"
            >
              <span class="fuy-num ward-assess-total">{{ assessResult.totalScore }}</span>
              <el-tag
                v-if="RISK_LEVEL_META[assessResult.riskLevel ?? ''] !== undefined"
                :type="RISK_LEVEL_META[assessResult.riskLevel ?? '']?.type"
                class="fuy-tag-aa"
                >{{ RISK_LEVEL_META[assessResult.riskLevel ?? '']?.text }}</el-tag
              >
              <span class="ward-assess-scale">{{
                SCALE_TYPE_LABELS[assessResult.scaleType ?? ''] ?? assessResult.scaleType
              }}</span>
              <span class="ward-assess-time">{{ formatTime(assessResult.assessedAt) }}</span>
            </div>
          </template>
          <el-empty v-else :image-size="56" description="量表定义加载中或不可用" />
          <!-- 最近评估历史 -->
          <div v-if="assessHistory.length > 0" class="ward-assess-history">
            <span
              v-for="row in assessHistory.slice(-3).reverse()"
              :key="String(row.id)"
              class="ward-assess-history-row"
            >
              {{ SCALE_TYPE_LABELS[row.scaleType ?? ''] ?? row.scaleType }} · 总分
              <span class="fuy-num">{{ row.totalScore ?? '—' }}</span>
              · {{ RISK_LEVEL_META[row.riskLevel ?? '']?.text ?? row.riskLevel ?? '—' }} ·
              {{ formatTime(row.assessedAt) }}
            </span>
          </div>
        </el-card>
      </el-col>

      <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 3 }">
        <!-- ⑧ 任务列表 + 交接班双签 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>护理任务</span>
              <el-select
                v-model="taskStatusFilter"
                class="ward-board-task-filter"
                size="small"
                @change="loadTasks"
              >
                <el-option label="全部状态" value="" />
                <el-option label="待执行" value="PENDING" />
                <el-option label="执行中" value="IN_PROGRESS" />
                <el-option label="已完成" value="COMPLETED" />
                <el-option label="已取消" value="CANCELLED" />
              </el-select>
            </div>
          </template>
          <div v-loading="taskLoading">
            <el-table
              v-if="taskList.length > 0"
              :data="taskList"
              class="fuy-dense"
              size="small"
              :row-class-name="taskRowClass"
            >
              <el-table-column label="任务号" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.taskNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="80">
                <template #default="{ row }">
                  {{ TASK_TYPE_LABELS[row.taskType ?? ''] ?? row.taskType }}
                </template>
              </el-table-column>
              <el-table-column label="患者" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.bedNo }}</span> {{ taskPatientLabel(row) }}
                </template>
              </el-table-column>
              <el-table-column label="计划时间" width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.planTime) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="责任护士" width="90">
                <template #default="{ row }">
                  {{ row.assignedNurse ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag
                    size="small"
                    :type="taskStatusMeta(row.status).type"
                    class="fuy-tag-aa"
                    :class="{ 'fuy-tag-strike': taskStatusMeta(row.status).strike }"
                    >{{ taskStatusMeta(row.status).text }}</el-tag
                  >
                  <!-- 逾期动作标记（不改状态，仍可完成 §3.10） -->
                  <el-tag
                    v-if="row.overdueFlag === true"
                    size="small"
                    type="danger"
                    class="fuy-tag-aa"
                    >逾期</el-tag
                  >
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :disabled="actingTaskNo !== null"
                    @click="onCompleteTask(row)"
                    >完成</el-button
                  >
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :disabled="actingTaskNo !== null"
                    @click="onCancelTask(row)"
                    >取消</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="56" description="当前病区暂无护理任务" />
          </div>
        </el-card>

        <!-- 交接班双签 -->
        <el-card class="ward-board-mid-card">
          <template #header>
            <div class="ward-board-card-head">
              <span>交接班双签</span>
              <el-button
                v-if="handover === null"
                type="primary"
                size="small"
                :loading="generating"
                :disabled="generating"
                @click="onGenerateHandover"
                >生成交接班</el-button
              >
            </div>
          </template>
          <div v-loading="handoverLoading">
            <template v-if="handover !== null">
              <!-- 患者摘要行 -->
              <div class="ward-handover-summary fuy-num">
                <span>总数 {{ handover.patientSummary?.total ?? 0 }}</span>
                <!-- 摘要口径后端权威（ShiftHandoverVO javadoc + V801 列注释）：SPECIAL=特级 /
                     CRITICAL=病重，两标签与计数字段一一对应禁止互换（R1 finding ④） -->
                <span>特级 {{ handover.patientSummary?.specialCount ?? 0 }}</span>
                <span>病重 {{ handover.patientSummary?.criticalCount ?? 0 }}</span>
                <span>新入 {{ handover.patientSummary?.newAdmissionCount ?? 0 }}</span>
                <span>手术 {{ handover.patientSummary?.surgeryCount ?? 0 }}</span>
                <span>转出 {{ handover.patientSummary?.transferOutCount ?? 0 }}</span>
                <span>今日出院 {{ handover.patientSummary?.todayDischargeCount ?? 0 }}</span>
              </div>
              <!-- SBAR 四段 -->
              <div class="ward-handover-sbar">
                <h4 class="fuy-section-title">现状 S</h4>
                <p class="ward-handover-text">{{ handover.sbarSituation ?? '—' }}</p>
                <h4 class="fuy-section-title">背景 B</h4>
                <p class="ward-handover-text">{{ handover.sbarBackground ?? '—' }}</p>
                <h4 class="fuy-section-title">评估 A</h4>
                <p class="ward-handover-text">{{ handover.sbarAssessment ?? '—' }}</p>
                <h4 class="fuy-section-title">建议 R</h4>
                <p class="ward-handover-text">{{ handover.sbarRecommendation ?? '—' }}</p>
              </div>
              <p class="ward-handover-pending">
                待续事项 <span class="fuy-num">{{ handover.pendingItems?.length ?? 0 }}</span> 条
              </p>
              <!-- 双签按钮契约（§3.10：DRAFT 可点 / COMPLETED disabled「已完成交接」） -->
              <div class="ward-handover-sign">
                <el-input
                  v-model="incomingNurseId"
                  placeholder="接班护士工号"
                  size="small"
                  class="ward-handover-incoming"
                  :disabled="handover.status === 'COMPLETED'"
                />
                <el-button
                  type="primary"
                  size="small"
                  :loading="completingHandover"
                  :disabled="completingHandover || handover.status === 'COMPLETED'"
                  @click="onCompleteHandover"
                  >{{ handover.status === 'COMPLETED' ? '已完成交接' : '完成交接' }}</el-button
                >
              </div>
            </template>
            <el-empty
              v-else
              :image-size="56"
              description="点击生成本班交接班材料（SBAR 自动汇总）"
            />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ⑥ 体温单渲染区（全宽置底） -->
    <el-card class="ward-board-chart-card" :style="{ '--fuy-stagger-index': 3 }">
      <template #header>
        <div class="ward-board-card-head">
          <span>体温单（{{ selectedDetail?.patientName ?? '未选患者' }}）</span>
          <!-- 图例（静态常驻，符号语义自助解读 §3.8） -->
          <span class="ward-chart-legend">
            <i class="ward-chart-legend-x" />腋温 <i class="ward-chart-legend-dot" />口温
            <i class="ward-chart-legend-circle" />肛温 <i class="ward-chart-legend-pulse" />脉率
          </span>
          <div class="ward-chart-month">
            <el-button size="small" :disabled="prevMonthDisabled" @click="onMonthChange(-1)"
              >上月</el-button
            >
            <span class="fuy-num ward-chart-month-text">{{ chartMonth }}</span>
            <el-button size="small" @click="onMonthChange(1)">下月</el-button>
          </div>
        </div>
      </template>
      <template v-if="selectedDetail !== undefined">
        <!-- 月页/患者切换 content-fade 200ms 显隐（曲线不做位移动画，防趋势误读 §3.8） -->
        <Transition name="fuy-content-fade" mode="out-in">
          <div
            v-loading="chartLoading"
            :key="`${chartMonth}-${selectedVisitId ?? 'none'}`"
            class="ward-chart-scroll"
          >
            <div class="ward-chart-inner">
              <svg
                class="ward-chart-svg"
                :width="chartModel.width"
                :height="chartModel.height + 20"
                :viewBox="`0 0 ${chartModel.width} ${chartModel.height + 20}`"
                role="img"
                aria-label="体温单曲线区"
              >
                <!-- 网格（普通 0.5px / major 1px） -->
                <line
                  v-for="grid in chartModel.grids"
                  :key="grid.key"
                  class="fuy-chart-grid"
                  :class="{ 'fuy-chart-grid--major': grid.major }"
                  :x1="grid.vertical ? grid.pos : AXIS_WIDTH"
                  :y1="grid.vertical ? 0 : grid.pos"
                  :x2="grid.vertical ? grid.pos : chartModel.width - AXIS_WIDTH"
                  :y2="grid.vertical ? chartModel.height : grid.pos"
                />
                <!-- 左轴体温刻度 / 右轴脉搏刻度 / X 轴日号 -->
                <text
                  v-for="tick in chartModel.tempTicks"
                  :key="`t-${tick.value}`"
                  class="fuy-chart-tick"
                  :x="AXIS_WIDTH - 6"
                  :y="tick.y + 4"
                  text-anchor="end"
                >
                  {{ tick.value }}
                </text>
                <text
                  v-for="tick in chartModel.pulseTicks"
                  :key="`p-${tick.value}`"
                  class="fuy-chart-tick"
                  :x="chartModel.width - AXIS_WIDTH + 6"
                  :y="tick.y + 4"
                  text-anchor="start"
                >
                  {{ tick.value }}
                </text>
                <text
                  v-for="tick in chartModel.dayTicks"
                  :key="`d-${tick.day}`"
                  class="fuy-chart-tick"
                  :x="tick.x"
                  :y="chartModel.height + 16"
                  text-anchor="middle"
                >
                  {{ tick.day }}
                </text>
                <!-- 曲线连线（体温蓝实线/脉率红线/降温红虚线/短绌填充线） -->
                <line
                  v-for="lineNode in chartModel.lines"
                  :key="lineNode.key"
                  :class="lineNode.className"
                  :x1="lineNode.x1"
                  :y1="lineNode.y1"
                  :x2="lineNode.x2"
                  :y2="lineNode.y2"
                />
                <!-- 特殊事件竖线（贯穿曲线区；呼吸心跳停止双竖线） -->
                <template v-for="eventNode in chartModel.events" :key="eventNode.key">
                  <line
                    :class="eventNode.className"
                    :x1="eventNode.x"
                    :y1="0"
                    :x2="eventNode.x"
                    :y2="chartModel.height"
                  />
                  <line
                    v-if="eventNode.double"
                    :class="eventNode.className"
                    :x1="eventNode.x + 2"
                    :y1="0"
                    :x2="eventNode.x + 2"
                    :y2="chartModel.height"
                  />
                  <text class="fuy-event-label" :x="eventNode.x + 4" :y="12">
                    {{ eventNode.label }}
                  </text>
                </template>
                <!-- 数据点符号（×/●/〇/脉率点/降温红圈/重叠外圈，title 无障碍读法） -->
                <template v-for="symbol in chartModel.symbols" :key="symbol.key">
                  <g v-if="symbol.className === 'fuy-temp-x'" :class="symbol.className">
                    <line
                      :x1="symbol.x - 3.5"
                      :y1="symbol.y - 3.5"
                      :x2="symbol.x + 3.5"
                      :y2="symbol.y + 3.5"
                    />
                    <line
                      :x1="symbol.x - 3.5"
                      :y1="symbol.y + 3.5"
                      :x2="symbol.x + 3.5"
                      :y2="symbol.y - 3.5"
                    />
                    <title>{{ symbol.title }}</title>
                  </g>
                  <circle
                    v-else
                    :class="symbol.className"
                    :cx="symbol.x"
                    :cy="symbol.y"
                    :r="symbol.className === 'fuy-temp-overlap-ring' ? 6 : 4"
                  >
                    <title>{{ symbol.title }}</title>
                  </circle>
                </template>
              </svg>
              <!-- 日行值底栏（与 X 轴日列对齐：label 列 88px + SVG 左移 88px 后日列同起点） -->
              <table class="ward-chart-daily">
                <tbody>
                  <tr v-for="row in DAILY_ROWS" :key="row.key">
                    <th class="ward-chart-daily-label">{{ row.label }}</th>
                    <td
                      v-for="day in chartModel.days"
                      :key="`${row.key}-${day}`"
                      class="fuy-num ward-chart-daily-cell"
                      :class="dailyCellValue(day, row.key)?.ruleClass"
                    >
                      {{ row.key === 'DATE' ? day : (dailyCellValue(day, row.key)?.text ?? '') }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </Transition>
        <!-- 特殊事件录入行（事件时点由后端服务器时间承载） -->
        <div class="ward-chart-event-row">
          <el-select v-model="specialEventType" class="ward-chart-event-type">
            <el-option
              v-for="item in SPECIAL_EVENT_OPTIONS"
              :key="item.code"
              :label="item.label"
              :value="item.code"
            />
          </el-select>
          <el-input
            v-model="specialEventRemark"
            placeholder="备注（选填）"
            class="ward-chart-event-remark"
          />
          <el-button
            type="primary"
            :loading="specialEventRecording"
            :disabled="specialEventRecording"
            @click="onAddSpecialEvent"
            >记录</el-button
          >
          <el-button
            v-if="selectedDetail !== undefined"
            link
            type="danger"
            size="small"
            class="ward-chart-exit"
            @click="onRemovePatient(selectedDetail)"
            >出区</el-button
          >
        </div>
      </template>
      <el-empty v-else :image-size="72" description="从床位卡墙选择患者查看体温单" />
    </el-card>

    <!-- 入区登记弹窗（520px 固定宽，§3.3） -->
    <el-dialog
      v-model="registerVisible"
      title="入区登记"
      width="520px"
      destroy-on-close
      class="ward-register-dialog"
    >
      <el-form label-width="90px">
        <el-form-item label="患者 ID" required>
          <el-input
            v-model="registerForm.patientId"
            placeholder="数字编号"
            class="ward-register-input"
          />
        </el-form-item>
        <el-form-item label="visit 号" required>
          <el-input
            v-model="registerForm.visitId"
            placeholder="I + 13 位数字"
            class="ward-register-input"
          />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input
            v-model="registerForm.patientName"
            placeholder="患者姓名"
            class="ward-register-name"
          />
        </el-form-item>
        <el-form-item label="床位" required>
          <el-input v-model="registerForm.bedNo" placeholder="如 03-01" class="ward-register-bed" />
        </el-form-item>
        <el-form-item label="护理级别" required>
          <el-select v-model="registerForm.nursingLevel" class="ward-register-bed">
            <el-option
              v-for="level in NURSING_LEVEL_OPTIONS"
              :key="level.code"
              :label="level.label"
              :value="level.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="病情标记">
          <el-select v-model="registerForm.conditionTags" multiple class="ward-register-input">
            <el-option
              v-for="tag in CONDITION_TAG_OPTIONS"
              :key="tag.code"
              :label="tag.label"
              :value="tag.code"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="registering" :disabled="registering" @click="onRegister"
          >确认登记</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ① 操作条 */
.ward-board-ward {
  width: 180px;
}
.ward-board-counts {
  display: flex;
  align-items: baseline;
  gap: var(--fuy-space-3);
}
.ward-board-count-total {
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  line-height: 1.2;
}
.ward-board-count-critical {
  color: var(--fuy-color-nursing-critical);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
}
.ward-board-count-severe {
  color: var(--fuy-color-nursing-serious);
  font-size: var(--fuy-font-size-sm);
  font-weight: 600;
}
.ward-board-review-anchor {
  position: relative;
  margin-left: auto;
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-md);
  text-decoration: none;
}
/* 待复核徽标（红底白字圆形 18px，§3.1） */
.ward-board-review-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-nursing-critical);
  color: var(--el-color-white);
  font-size: var(--fuy-font-size-xs);
  font-weight: 700;
}
.ward-board-register-btn {
  width: 96px;
}

/* ② 卡墙（lg 8 列 / xl 10 列，min-height 240px CLS 锁） */
.ward-board-wall {
  min-height: 240px;
}
.ward-board-wall-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px;
}
.ward-bed-card {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 148px;
  min-height: 84px;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid #e5e7eb;
  border-radius: var(--fuy-radius-lg);
  background: var(--el-bg-color);
  text-align: left;
  cursor: pointer;
  /* 选中态 120ms 描边/底色过渡（PR-5 号源卡选中同形态） */
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.ward-bed-card.is-selected {
  border: 2px solid var(--fuy-color-brand);
  background: var(--fuy-palette-brand-100);
}
.ward-bed-card.is-selected::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
}
.ward-bed-card-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-bed-no {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
.ward-bed-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
.ward-bed-flags {
  display: flex;
  gap: 4px;
}
.ward-bed-overflow {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.ward-bed-foot {
  display: flex;
  justify-content: space-between;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* ③④⑤⑦⑧ 三列卡片 */
.ward-board-mid-card {
  margin-bottom: var(--fuy-space-3);
}
.ward-board-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-2);
}
.ward-board-shift {
  width: 100px;
}
.ward-board-scale {
  width: 200px;
}
.ward-board-task-filter {
  width: 110px;
}

/* ③ 详情面板 */
.ward-detail-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-detail-name {
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
}
.ward-detail-base {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-detail-allergy {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin: var(--fuy-space-2) 0;
}
.ward-detail-allergy-list {
  color: var(--fuy-color-danger-text);
  font-size: var(--fuy-font-size-sm);
}
.ward-detail-allergy-none {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-detail-risk {
  display: flex;
  gap: 4px;
  margin-bottom: var(--fuy-space-2);
}
.ward-detail-desc {
  margin-bottom: var(--fuy-space-2);
}
.ward-detail-vitals {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2) var(--fuy-space-3);
  font-size: var(--fuy-font-size-md);
}
.ward-detail-vitals-time {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}

/* ④ 分配 */
.ward-assign-list {
  min-height: 80px;
}
.ward-assign-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  height: 36px;
}
.ward-assign-nurse {
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}
.ward-assign-scope {
  flex: 1;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-assign-add {
  margin-top: var(--fuy-space-2);
}
.ward-assign-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-2);
}
.ward-assign-form .ward-vital-input,
.ward-assign-form .el-input,
.ward-assign-form .el-select {
  width: 100%;
}
.ward-assign-form-actions {
  display: flex;
  gap: var(--fuy-space-2);
  width: 100%;
}

/* ⑤ 体征录入（两行 × 四列 grid，min-height 240px CLS 锁） */
.ward-vital-form {
  min-height: 240px;
}
.ward-vital-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(170px, 1fr));
  gap: var(--fuy-space-3);
}
.ward-vital-field {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
}
.ward-vital-label {
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
}
.ward-vital-pair {
  display: flex;
  gap: var(--fuy-space-1);
}
/* 原生数值输入（数值字段文本承载显式校验；EP 输入组件经禁用态联动承载未选患者灰化） */
.ward-vital-input {
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  min-width: 0;
  flex: 1;
}
.ward-vital-input:disabled {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-placeholder);
  cursor: not-allowed;
}
.ward-vital-input:focus-visible {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 0;
}
.ward-vital-input-num {
  width: 80px;
  flex: none;
}
.ward-vital-pair .ward-vital-input:not(.ward-vital-input-num) {
  width: 88px;
  flex: none;
}
.ward-vital-submit {
  margin-top: var(--fuy-space-3);
  width: 96px;
}
.ward-vital-hint {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.ward-io-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
}
.ward-vital-unit {
  width: 56px;
  flex: none;
}

/* ⑦ 评估 */
.ward-scale-item {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  min-height: 32px;
}
.ward-scale-item-name {
  flex: 1;
  font-size: var(--fuy-font-size-md);
}
.ward-scale-submit {
  margin-top: var(--fuy-space-3);
  width: 96px;
}
/* 结果条（content-fade 出现；HIGH 高危红描边 + 8% 红底，§3.9 契约） */
.ward-assess-result {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-3);
  margin-top: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
}
.ward-assess-result.fuy-assess-result--high {
  border: 1px solid var(--fuy-color-danger-text);
  background: rgba(185, 28, 28, 0.08);
}
.ward-assess-total {
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  line-height: 1.2;
}
.ward-assess-scale,
.ward-assess-time {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-assess-history {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  margin-top: var(--fuy-space-3);
}
.ward-assess-history-row {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* ⑧ 任务与交接班 */
.ward-handover-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2) var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
}
.ward-handover-sbar .fuy-section-title {
  margin: var(--fuy-space-2) 0 var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
}
.ward-handover-text {
  margin: 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
}
.ward-handover-pending {
  margin: var(--fuy-space-2) 0 0;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}
.ward-handover-sign {
  display: flex;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
}
.ward-handover-incoming {
  flex: 1;
}
/* 任务逾期行（左缘 3px 红条 + 计划时间红字，§3.10 契约；表格行类经 :row-class-name 挂载，
   scoped 零哈希匹配——全局层 deep 承载） */
:deep(.fuy-task-overdue) td {
  position: relative;
}
:deep(.fuy-task-overdue) td:first-child::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--fuy-color-danger-text);
}
:deep(.fuy-task-overdue) td .cell {
  color: var(--fuy-color-danger-text);
}

/* ⑥ 体温单（全宽，min-height 420px CLS 锁） */
.ward-board-chart-card {
  min-height: 420px;
}
.ward-chart-legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.ward-chart-legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
}
.ward-chart-legend-x {
  position: relative;
}
.ward-chart-legend-x::before,
.ward-chart-legend-x::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  width: 1.5px;
  height: 10px;
  background: var(--fuy-chart-temp-color);
}
.ward-chart-legend-x::before {
  transform: rotate(45deg);
}
.ward-chart-legend-x::after {
  transform: rotate(-45deg);
}
.ward-chart-legend-dot {
  border-radius: 50%;
  background: var(--fuy-chart-temp-color);
}
.ward-chart-legend-circle {
  border-radius: 50%;
  border: 1.5px solid var(--fuy-chart-temp-color);
}
.ward-chart-legend-pulse {
  border-radius: 50%;
  background: var(--fuy-chart-pulse-color);
}
.ward-chart-month {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-chart-month-text {
  font-size: var(--fuy-font-size-md);
  font-weight: 700;
}
.ward-chart-scroll {
  overflow-x: auto;
  min-height: 320px;
}
.ward-chart-inner {
  display: inline-block;
  min-width: max-content;
}
/* SVG 左移 88px 与底栏 label 列对齐（日列同起点 120px） */
.ward-chart-svg {
  margin-left: 88px;
  display: block;
}
.ward-chart-daily {
  border-collapse: collapse;
  table-layout: fixed;
  margin-top: var(--fuy-space-2);
}
.ward-chart-daily-label {
  width: 88px;
  padding: 2px 6px;
  text-align: left;
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  color: var(--fuy-chart-text-color);
  white-space: nowrap;
}
.ward-chart-daily-cell {
  width: 192px;
  min-width: 192px;
  max-width: 192px;
  padding: 2px 4px;
  text-align: center;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-chart-text-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 出入量班次小结/24h 总结红双线（§5.3 S14/S15；<3px 退化单线，3px/4px 保证双线可见） */
.ward-chart-daily-cell.fuy-io-summary-rule--shift {
  border-top: 3px double var(--fuy-chart-pulse-color);
}
.ward-chart-daily-cell.fuy-io-summary-rule--24h {
  border-top: 4px double var(--fuy-chart-pulse-color);
  font-weight: 700;
}
.ward-chart-event-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-top: var(--fuy-space-3);
  min-height: 40px;
}
.ward-chart-event-type {
  width: 160px;
}
.ward-chart-event-remark {
  flex: 1;
}
.ward-chart-exit {
  margin-left: auto;
}

/* 体温单 SVG 符号（§5.4 冻结样式：类只控色与线宽，几何在节点属性） */
.fuy-chart-grid {
  stroke: var(--fuy-chart-grid-color);
  stroke-width: 0.5;
}
.fuy-chart-grid--major {
  stroke-width: 1;
}
.fuy-chart-tick {
  fill: var(--fuy-chart-text-color);
  font-size: var(--fuy-font-size-xs);
}
.fuy-temp-x {
  stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-dot {
  fill: var(--fuy-chart-temp-color);
}
.fuy-temp-circle {
  fill: none;
  stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-line {
  stroke: var(--fuy-chart-temp-color);
  stroke-width: var(--fuy-chart-line-width);
  fill: none;
}
.fuy-pulse-dot {
  fill: var(--fuy-chart-pulse-color);
}
.fuy-pulse-heart-ring {
  fill: none;
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-pulse-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
  fill: none;
}
.fuy-temp-overlap-ring {
  fill: none;
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-cooling-ring {
  fill: none;
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-temp-cooling-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
  stroke-dasharray: 4 3;
  fill: none;
}
.fuy-temp-deficit-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: var(--fuy-chart-line-width);
}
.fuy-event-line {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: 1;
}
.fuy-event-line--arrest {
  stroke: var(--fuy-chart-pulse-color);
  stroke-width: 1;
}
.fuy-event-label {
  fill: var(--fuy-chart-pulse-color);
  font-size: var(--fuy-font-size-xs);
}

/* 月页/患者切换 fade leave 段（motion.css 仅定义 enter 段，DoctorStation 同款页内补齐） */
.fuy-content-fade-leave-active {
  transition: opacity var(--fuy-motion-fast) var(--fuy-ease-exit);
}
.fuy-content-fade-leave-to {
  opacity: 0;
}

/* 入区登记弹窗表单宽度（§3.3 冻结值） */
.ward-register-input {
  width: 220px;
}
.ward-register-name {
  width: 120px;
}
.ward-register-bed {
  width: 160px;
}
</style>
