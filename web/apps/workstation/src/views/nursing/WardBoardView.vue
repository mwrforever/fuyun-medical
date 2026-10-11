<script setup lang="ts">
// 护士工作站页（/nursing/ward，M05 前端面 · 暖纸卷宗换脸重排，逐页蓝图 甲-1）：动线按
// 「看墙 → 查文书 → 录数据 → 办任务」重排——①门牌页首（衬线标题+病区/班次真数据下拉+
// 待复核胶囊呼吸点+当班护士批注行）→ ②床位卡墙（全宽，患者选中驱动全页）→ ③体温单卷宗
// 图表卡（自页底升格至卡墙之后，PM 重排裁决：查阅频率最高的文书）→ ④患者卷宗三列
// （详情+分配 / 体征录入+待复核 / 任务+交接班）→ ⑤页脚批注（数据时点）。
// 换脸不换业务：12 作业面 composable 组装时序、写后局部刷新链、v-perm 口径、启停互斥
// 守卫、EX-45 竞态守卫、草稿复位（FE-A2-01/BUG-15）、交接班双签契约逐字冻结；
// `GET /nursing/board/{wardId}` 单聚合经裁决不取用（换单聚合将改写后局部刷新语义）。
// 病区清单换真数据源 listOrgs({type:'WARD'})（WARD_OPTIONS 假常量清零，三态在 useWardContext）。
// EX-47 拆分口径保留：状态与业务逻辑下沉 composables/，视图只做组装与跨面联动编排；
// 全部写操作自带在途守卫（入口早退先于一切 await）+ 4xx 口径显式校验（禁裸 parse，数值
// 字段一律文本承载经正则+范围双验）；失败弹错归响应拦截器（AxiosError 防双弹，非
// AxiosError 的业务拒绝对象由 surfaceBizError 兜底展示 detail 原文）。体温单坐标计算在
// tempChart.ts 纯函数（spec 双层断言），视图仅做 SVG 映射渲染（曲线静态零位移动画）。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
// ElMessageBox 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message-box/style/css';
import { SHIFT_OPTIONS, SPECIAL_EVENT_OPTIONS, TEMP_SITE_OPTIONS } from '@/api/nursing';
import { useAuthStore } from '@/stores/auth';
import { AXIS_WIDTH, DAILY_ROWS } from './tempChart';
import { groupWallPatients, splitTags } from './wardBoardShared';
import type { WallBedGroup } from './wardBoardShared';
import { formatTime } from '@/utils/timeFormat';
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
/** 当班护士（门牌批注行与交接班确认回显的锚点；任务认领略痕取 userId），空值「—」占位 */
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
// 任务面注入当班护士 userId（认领/生成常规任务的操作留痕锚点，会话惰性取值）
const nursingTasks = useNursingTasks({
  wardId: ward.wardId,
  detailMap: ward.detailMap,
  getAssigneeId: () => auth.user?.userId ?? '',
});
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
  // 录入成功联动：钤印确认（暖纸成功反馈脸）→ 先体温单后简报（拆分前 await 链同序）
  onRecorded: async () => {
    showStamp('已录入');
    await tempChart.loadChart();
    await latestVitalsState.loadLatestVitals();
  },
});

/* ==================== 模板绑定面（组装解包，模板零改动） ==================== */
const {
  wardId,
  shiftCode,
  wardOptions,
  wardOptionsLoading,
  wardOptionsError,
  loadedAt,
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
  loadWardOptions,
  loadWard,
  onWardChange,
  selectPatient,
} = ward;
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
  onClaimTask,
  onGenerateRoutine,
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

/** 门牌批注行班次展示值（冻结契约词表反查，未匹配「—」占位） */
const shiftLabel = computed(
  () => SHIFT_OPTIONS.find((shift) => shift.code === shiftCode.value)?.label ?? '—',
);
/** 门牌批注行时刻（页面装配时点，系统生成非伪数据） */
const headTime = formatTime(new Date().toISOString());

/** 钤印确认反馈（契约 ⑧.5）：动词结果文案 ≤1200ms 收场；失败/业务拒绝走 message 不入钤印 */
const stampText = ref('');
const stampVisible = ref(false);
let stampTimer: ReturnType<typeof setTimeout> | null = null;
function showStamp(text: string): void {
  stampText.value = text;
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

/** 卡墙分组（>120 卡按床号段折叠，⑨.4 长列表策略；≤120 单组无组头平铺） */
const wallGroups = computed<WallBedGroup[]>(() => groupWallPatients(sortedPatients.value));
/** 已折叠床号段键集（分组形态下的组头开关；平铺形态无组头不消费） */
const collapsedGroups = ref<readonly string[]>([]);
function isGroupCollapsed(key: string): boolean {
  return collapsedGroups.value.includes(key);
}
function toggleWallGroup(key: string): void {
  collapsedGroups.value = isGroupCollapsed(key)
    ? collapsedGroups.value.filter((groupKey) => groupKey !== key)
    : [...collapsedGroups.value, key];
}

/** 首拉装配态（病区清单未就绪或病区面首拉中）：三列卡体给 200px 骨架，防空态脸抢跑 */
const booting = computed(
  () => wardId.value === '' || (wardLoading.value && sortedPatients.value.length === 0),
);

/** 装配链：病区清单就绪后再拉病区面（失败/空清单驻留恢复态/空态脸，零病区面出网） */
async function bootWard(): Promise<void> {
  const ready = await loadWardOptions();
  if (ready) {
    void loadWard();
  }
}

onMounted(() => {
  void loadScales();
  void bootWard();
});
</script>

<template>
  <section class="fuy-page ward-board">
    <div class="fuy-stagger ward-board-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（病区/班次真数据
           下拉 + 待复核胶囊呼吸点 + 刷新）+ 当班护士批注行 + 2px 墨规收底（脸归全局族） -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">护士站</h1>
          <span class="fuy-page-en" aria-hidden="true">NURSING STATION</span>
        </div>
        <div class="fuy-page-status ward-board-head-controls">
          <el-select
            v-model="wardId"
            class="ward-board-ward"
            aria-label="病区"
            :disabled="wardOptionsLoading || wardOptions.length === 0"
            popper-class="fuy-snap-popper"
            @change="onWardChange"
          >
            <el-option
              v-for="option in wardOptions"
              :key="option.code"
              :label="option.label"
              :value="option.code"
            />
          </el-select>
          <el-select
            v-model="shiftCode"
            class="ward-board-shift"
            aria-label="班次"
            popper-class="fuy-snap-popper"
            @change="onShiftChange"
          >
            <el-option
              v-for="shift in SHIFT_OPTIONS"
              :key="shift.code"
              :label="shift.label"
              :value="shift.code"
            />
          </el-select>
          <!-- 待复核锚胶囊（实时待办语义挂呼吸点；双通道铁律=文字随胶囊在） -->
          <a
            class="fuy-status-pill ward-board-review-anchor"
            :class="pendingList.length > 0 ? 'fuy-status-pill--danger' : 'fuy-status-pill--info'"
            href="#ward-pending-review"
          >
            <i v-if="pendingList.length > 0" class="fuy-live-dot" aria-hidden="true"></i>
            待复核 {{ pendingList.length }}
          </a>
          <el-button class="ward-board-refresh" :loading="wardLoading" @click="loadWard"
            >刷新</el-button
          >
        </div>
        <p class="fuy-page-note">
          当班护士 {{ operatorName }} · 班次 {{ shiftLabel }} · <time>{{ headTime }}</time>
        </p>
      </header>

      <!-- ② 床位卡墙（全宽，患者选中驱动全页；>120 卡按床号段分组折叠） -->
      <section class="fuy-card ward-board-wall-card" :style="{ '--fuy-stagger-index': 1 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">床位卡墙</h2>
          <span class="ward-board-wall-meta">
            在区 <span class="fuy-num">{{ wardCounts.total }}</span> · {{ wardId || '—' }}
          </span>
        </header>
        <div class="fuy-card-body">
          <!-- 病区清单三态（listOrgs 真数据面）：在途骨架 / 失败恢复 / 空清单空态脸 -->
          <div v-if="wardOptionsLoading" class="ward-wall-sk" aria-hidden="true">
            <span v-for="i in 8" :key="i" class="fuy-sk" />
          </div>
          <div v-else-if="wardOptionsError" class="fuy-empty ward-board-boot-error" role="alert">
            <p class="fuy-empty-title">病区清单加载失败</p>
            <p class="fuy-empty-hint">无法获取病区清单，请检查网络后点击重新加载恢复。</p>
            <el-button type="primary" size="small" @click="bootWard">重新加载</el-button>
          </div>
          <div v-else-if="wardOptions.length === 0" class="fuy-empty" role="status">
            <span class="fuy-empty-mark" aria-hidden="true">空</span>
            <p class="fuy-empty-title">暂无可用病区</p>
            <p class="fuy-empty-hint">组织机构未配置启用病区，请联系管理员维护后重试。</p>
          </div>
          <div v-else class="ward-board-wall">
            <!-- 病区面首拉骨架（卡墙 240px CLS 锁）；已有数据重刷走 v-loading 遮罩不闪骨架 -->
            <div
              v-if="wardLoading && sortedPatients.length === 0"
              class="ward-wall-sk"
              aria-hidden="true"
            >
              <span v-for="i in 8" :key="i" class="fuy-sk" />
            </div>
            <template v-else>
              <div v-for="group in wallGroups" :key="group.key" class="ward-wall-group">
                <button
                  v-if="group.label !== ''"
                  type="button"
                  class="ward-wall-group-head"
                  :aria-expanded="!isGroupCollapsed(group.key)"
                  @click="toggleWallGroup(group.key)"
                >
                  <span aria-hidden="true">{{ isGroupCollapsed(group.key) ? '▸' : '▾' }}</span
                  >{{ group.label }}
                </button>
                <TransitionGroup
                  v-if="!isGroupCollapsed(group.key)"
                  name="fuy-flip"
                  tag="div"
                  class="ward-board-wall-grid"
                >
                  <button
                    v-for="patient in group.patients"
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
              </div>
              <div v-if="sortedPatients.length === 0" class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">暂无在区患者</p>
                <p class="fuy-empty-hint">当前病区暂无在区记录，可切换病区或点击刷新。</p>
              </div>
            </template>
          </div>
        </div>
      </section>

      <!-- ③ 体温单卷宗图表卡（全宽，自页底升格至卡墙之后——PM 重排裁决：查阅频率最高的
           文书紧随卡墙；min-height 420px CLS 锁，横向滚动容器 320px 保留） -->
      <section class="fuy-card ward-board-chart-card" :style="{ '--fuy-stagger-index': 2 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">体温单（{{ selectedDetail?.patientName ?? '未选患者' }}）</h2>
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
        </header>
        <div class="fuy-card-body">
          <template v-if="selectedDetail !== undefined">
            <!-- 加载骨架（体温单 320px CLS 锁档）；月页/患者切换 content-fade 显影，
                 曲线零位移动画防趋势误读（§3.8 既有约束），SVG stroke 零动画 -->
            <div v-if="chartLoading" class="ward-chart-sk" aria-hidden="true">
              <span class="fuy-sk" />
            </div>
            <Transition v-else name="fuy-content-fade" mode="out-in">
              <div :key="`${chartMonth}-${selectedVisitId ?? 'none'}`" class="ward-chart-scroll">
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
                          {{
                            row.key === 'DATE' ? day : (dailyCellValue(day, row.key)?.text ?? '')
                          }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </Transition>
            <!-- 特殊事件录入行（事件时点由后端服务器时间承载） -->
            <div class="ward-chart-event-row">
              <el-select
                v-model="specialEventType"
                class="ward-chart-event-type"
                popper-class="fuy-snap-popper"
              >
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
              <!-- 特殊事件录入提交（PR-4F #26 IO/评估/特殊事件并族）v-perm 直挂；事件类型
                   select 与备注输入为表单数据面不挂码；上月/下月翻页为读面不挂码 -->
              <el-button
                v-perm="'nursing:ward:btn:record'"
                type="primary"
                :loading="specialEventRecording"
                :disabled="specialEventRecording"
                @click="onAddSpecialEvent"
                >记录</el-button
              >
              <!-- 出区按钮已随 W-34 退役：床位移除动作归 inpatient 出院/转科事件投影 -->
            </div>
          </template>
          <div v-else class="fuy-empty" role="status">
            <span class="fuy-empty-mark" aria-hidden="true">空</span>
            <p class="fuy-empty-title">尚未选择患者</p>
            <p class="fuy-empty-hint">从床位卡墙选择患者后查看体温单与录入特殊事件。</p>
          </div>
        </div>
      </section>

      <!-- ④ 患者卷宗三列：详情+分配 / 体征录入+待复核 / 任务+交接班（行级 stagger 承载
           三列级联；lg 6/12/6 沿既有断点；首拉装配态给 200px 骨架防空态脸抢跑） -->
      <el-row
        class="fuy-stagger ward-board-cols"
        :gutter="16"
        :style="{ '--fuy-stagger-index': 3 }"
      >
        <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 1 }">
          <!-- 患者详情 -->
          <section class="fuy-card ward-board-mid-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">患者详情</h2>
            </header>
            <div v-loading="latestVitalsLoading" class="fuy-card-body">
              <span v-if="booting" class="fuy-sk fuy-sk-trend" aria-hidden="true" />
              <template v-else-if="selectedDetail !== undefined">
                <div class="ward-detail-head">
                  <span class="ward-detail-name">{{ selectedDetail.patientName }}</span>
                  <!-- 性别/年龄经 patient api 嵌查口径维持但当前恒 null：无值不渲染（Task 7 C2） -->
                  <span
                    v-if="selectedDetail.gender !== undefined && selectedDetail.gender !== null"
                    class="ward-detail-base"
                    >{{ selectedDetail.gender }} / {{ selectedDetail.age ?? '—' }}岁</span
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
              <div v-else class="fuy-empty" role="status">
                <span class="fuy-empty-mark" aria-hidden="true">空</span>
                <p class="fuy-empty-title">尚未选择患者</p>
                <p class="fuy-empty-hint">从床位卡墙选择患者后查看详情与责任分配。</p>
              </div>
            </div>
          </section>

          <!-- 责任护士分配（班次 select 升门牌状态位，卡头回归纯标题） -->
          <section class="fuy-card ward-board-mid-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">责任护士分配</h2>
            </header>
            <div class="fuy-card-body">
              <span v-if="booting" class="fuy-sk fuy-sk-trend" aria-hidden="true" />
              <template v-else>
                <div v-loading="assignmentLoading" class="ward-assign-list">
                  <div v-for="row in assignmentList" :key="String(row.id)" class="ward-assign-row">
                    <span class="ward-assign-nurse">{{ row.nurseId }}</span>
                    <span class="ward-assign-scope fuy-num">{{
                      row.assignmentType === 'BED' ? `管床 ${row.bedNo ?? '—'}` : '责任患者'
                    }}</span>
                    <!-- 责任分配增删（PR-4F #27 并入任务族，盘点 §1.1 收敛）：移除/新增分配与
                         任务五族按钮同码 v-perm 直挂；展开表单内「确认」提交随入口免挂接
                         （入口隐藏即表单不可展开） -->
                    <el-button
                      v-perm="'nursing:ward:btn:task'"
                      link
                      type="danger"
                      size="small"
                      @click="onUnassign(row)"
                      >移除</el-button
                    >
                  </div>
                  <div v-if="assignmentList.length === 0" class="fuy-empty" role="status">
                    <p class="fuy-empty-title">本班次暂无责任分配</p>
                    <p class="fuy-empty-hint">可点击新增分配登记本班责任护士。</p>
                  </div>
                </div>
                <div v-if="!assignFormVisible" class="ward-assign-add">
                  <el-button
                    v-perm="'nursing:ward:btn:task'"
                    size="small"
                    @click="assignFormVisible = true"
                    >新增分配</el-button
                  >
                </div>
                <div v-else class="ward-assign-form">
                  <el-input v-model="assignForm.nurseId" placeholder="护士工号" />
                  <el-select v-model="assignForm.assignmentType" popper-class="fuy-snap-popper">
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
                  <el-input
                    v-else
                    v-model="assignForm.patientId"
                    placeholder="患者 ID（责任分配）"
                  />
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
              </template>
            </div>
          </section>
        </el-col>

        <el-col :md="24" :lg="12" :style="{ '--fuy-stagger-index': 2 }">
          <!-- 体征录入 + 待复核列表 -->
          <section id="ward-pending-review" class="fuy-card ward-board-mid-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">体征录入（{{ selectedDetail?.bedNo ?? '未选患者' }}）</h2>
              <el-button link type="primary" size="small" @click="ioVisible = !ioVisible">
                {{ ioVisible ? '收起出入量' : '出入量快录' }}
              </el-button>
            </header>
            <div class="fuy-card-body">
              <span v-if="booting" class="fuy-sk fuy-sk-trend" aria-hidden="true" />
              <template v-else>
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
                          <option
                            v-for="site in TEMP_SITE_OPTIONS"
                            :key="site.code"
                            :value="site.code"
                          >
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
                  <!-- 体征录入（PR-4F #25，NURSE 绑定）v-perm 直挂：与 :disabled 选患者数据态
                       正交；录入字段网格为表单数据面不挂码 -->
                  <el-button
                    v-perm="'nursing:ward:btn:vital'"
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
                  <input
                    v-model="ioForm.itemCode"
                    class="ward-vital-input"
                    placeholder="项目编码"
                  />
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
                  <!-- 出入量提交（PR-4F #26 IO/评估/特殊事件并族）：v-perm 直挂；
                       头部「出入量快录」切换为面板显隐前置（附件 A 元素列未列）不挂码——
                       无码仅面板可见而提交入口隐藏，fail-closed 不弱化 -->
                  <el-button
                    v-perm="'nursing:ward:btn:record'"
                    size="small"
                    :loading="ioRecording"
                    :disabled="ioRecording"
                    @click="onCreateIoRecord"
                    >记录出入量</el-button
                  >
                </div>
                <!-- 待复核列表（确认成功该行经 fuy-flip 行级移除，spec 冻结语义） -->
                <h4 class="fuy-section-title">待复核体征</h4>
                <div v-if="pendingList.length > 0" class="ward-pending-wrap">
                  <table class="ward-pending-table">
                    <thead>
                      <tr>
                        <th>时点</th>
                        <th>体温</th>
                        <th>脉搏</th>
                        <th>呼吸</th>
                        <th>血压</th>
                        <th>来源</th>
                        <th>质量</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <TransitionGroup name="fuy-flip" tag="tbody" class="ward-pending-tbody">
                      <tr v-for="row in pendingList" :key="String(row.id)">
                        <td>
                          <span class="fuy-num">{{ formatTime(row.measuredAt) }}</span>
                        </td>
                        <td>
                          <span class="fuy-num">{{ row.temperature ?? '—' }}</span>
                        </td>
                        <td>
                          <span class="fuy-num">{{ row.pulse ?? '—' }}</span>
                        </td>
                        <td>
                          <span class="fuy-num">{{ row.respiration ?? '—' }}</span>
                        </td>
                        <td>
                          <span class="fuy-num"
                            >{{ row.systolicBp ?? '—' }}/{{ row.diastolicBp ?? '—' }}</span
                          >
                        </td>
                        <td>
                          {{
                            row.source === 'IOT' ? 'IoT' : row.source === 'PDA' ? 'PDA' : '一体机'
                          }}
                        </td>
                        <td>
                          <span class="fuy-num">{{ row.iotQuality ?? '—' }}</span>
                        </td>
                        <td class="fuy-ops-8">
                          <!-- 复审确认/驳回（PR-4F #25）与录入体征同码 v-perm 直挂（体征族闭环：
                               录入→复核两端口同码收口） -->
                          <el-button
                            v-perm="'nursing:ward:btn:vital'"
                            link
                            type="primary"
                            size="small"
                            :disabled="confirmingId !== null"
                            @click="onConfirmVital(row)"
                            >确认</el-button
                          >
                          <el-button
                            v-perm="'nursing:ward:btn:vital'"
                            link
                            type="danger"
                            size="small"
                            :disabled="confirmingId !== null"
                            @click="onRejectVital(row)"
                            >驳回</el-button
                          >
                        </td>
                      </tr>
                    </TransitionGroup>
                  </table>
                </div>
                <div v-else class="fuy-empty ward-pending-empty" role="status">
                  <p class="fuy-empty-title">暂无待复核体征</p>
                  <p class="fuy-empty-hint">IoT/PDA 采集数据经确认后并入体温单。</p>
                </div>
              </template>
            </div>
          </section>

          <!-- 护理评估 -->
          <section class="fuy-card ward-board-mid-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">
                护理评估（{{ selectedDetail?.patientName ?? '未选患者' }}）
              </h2>
              <el-select
                v-model="scaleType"
                class="ward-board-scale"
                popper-class="fuy-snap-popper"
                @change="onScaleChange"
              >
                <el-option
                  v-for="scale in scaleList"
                  :key="scale.scaleType"
                  :label="SCALE_TYPE_LABELS[scale.scaleType ?? ''] ?? scale.scaleType"
                  :value="scale.scaleType ?? ''"
                />
              </el-select>
            </header>
            <div class="fuy-card-body">
              <span v-if="booting" class="fuy-sk fuy-sk-trend" aria-hidden="true" />
              <template v-else-if="currentScale !== undefined">
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
                <!-- 护理评估提交（PR-4F #26 IO/评估/特殊事件并族）v-perm 直挂；量表选择
                     select 与答题 radio 为表单数据面不挂码 -->
                <el-button
                  v-perm="'nursing:ward:btn:record'"
                  type="primary"
                  class="ward-scale-submit"
                  :loading="assessing"
                  :disabled="assessing || selectedDetail === undefined"
                  @click="onAssess"
                  >提交评估</el-button
                >
                <!-- 结果条（§3.9 契约：HIGH 挂 .fuy-assess-result--high + 「高风险」文案；
                     洗底=印泥朱 alpha 派生档（蓝图口径），旧 off-token 红洗底已清零） -->
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
              <div v-else class="fuy-empty" role="status">
                <p class="fuy-empty-title">暂无可用量表</p>
                <p class="fuy-empty-hint">量表定义加载中或后端未配置，请稍后重试。</p>
              </div>
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
            </div>
          </section>
        </el-col>

        <el-col :md="24" :lg="6" :style="{ '--fuy-stagger-index': 3 }">
          <!-- 护理任务 -->
          <section class="fuy-card ward-board-mid-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">护理任务</h2>
              <div class="ward-board-task-head">
                <!-- 常规模板批量生成入口（Task 9 面：确认后按病区+当日生成）；任务族五
                     按钮（PR-4F #27，NURSE 绑定）同码 v-perm 直挂——状态筛选 select 为
                     读面过滤不挂码 -->
                <el-button
                  v-perm="'nursing:ward:btn:task'"
                  size="small"
                  :loading="actingTaskNo === '__routine__'"
                  :disabled="actingTaskNo !== null"
                  @click="onGenerateRoutine"
                  >生成常规任务</el-button
                >
                <el-select
                  v-model="taskStatusFilter"
                  class="ward-board-task-filter"
                  size="small"
                  popper-class="fuy-snap-popper"
                  @change="loadTasks"
                >
                  <el-option label="全部状态" value="" />
                  <el-option label="待执行" value="PENDING" />
                  <el-option label="执行中" value="IN_PROGRESS" />
                  <el-option label="已完成" value="COMPLETED" />
                  <el-option label="已取消" value="CANCELLED" />
                </el-select>
              </div>
            </header>
            <div class="fuy-card-body">
              <span v-if="booting" class="fuy-sk fuy-sk-trend" aria-hidden="true" />
              <div v-else v-loading="taskLoading">
                <el-table
                  v-if="taskList.length > 0"
                  :data="taskList"
                  class="fuy-dense"
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
                  <el-table-column label="操作" width="150" class-name="fuy-ops-8">
                    <template #default="{ row }">
                      <!-- 任务行内三动作（认领/完成/取消，PR-4F #27）与生成入口同码 v-perm
                           直挂；:disabled 在途数据态正交叠加 -->
                      <!-- 待执行任务可认领（Task 9：PENDING → 执行中，认领人留痕） -->
                      <el-button
                        v-if="row.status === 'PENDING'"
                        v-perm="'nursing:ward:btn:task'"
                        link
                        type="primary"
                        size="small"
                        :disabled="actingTaskNo !== null"
                        @click="onClaimTask(row)"
                        >认领</el-button
                      >
                      <el-button
                        v-perm="'nursing:ward:btn:task'"
                        link
                        type="primary"
                        size="small"
                        :disabled="actingTaskNo !== null"
                        @click="onCompleteTask(row)"
                        >完成</el-button
                      >
                      <el-button
                        v-perm="'nursing:ward:btn:task'"
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
                <div v-else class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无护理任务</p>
                  <p class="fuy-empty-hint">当前病区暂无任务记录，可生成常规任务或稍后刷新。</p>
                </div>
              </div>
            </div>
          </section>

          <!-- 交接班双签 -->
          <section class="fuy-card ward-board-mid-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">交接班双签</h2>
              <!-- 交班生成/完成（PR-4F #28，NURSE 绑定）同码 v-perm 直挂；接班护士工号
                   输入框为「完成交接」的随行数据面（附件 A 元素列未列）不挂码——
                   无码仅生成/完成入口隐藏，双签链路整体不可达 -->
              <el-button
                v-if="handover === null"
                v-perm="'nursing:ward:btn:handover'"
                type="primary"
                size="small"
                :loading="generating"
                :disabled="generating"
                @click="onGenerateHandover"
                >生成交接班</el-button
              >
            </header>
            <div class="fuy-card-body">
              <span v-if="booting" class="fuy-sk fuy-sk-trend" aria-hidden="true" />
              <div v-else v-loading="handoverLoading">
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
                    待续事项
                    <span class="fuy-num">{{ handover.pendingItems?.length ?? 0 }}</span> 条
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
                      v-perm="'nursing:ward:btn:handover'"
                      type="primary"
                      size="small"
                      :loading="completingHandover"
                      :disabled="completingHandover || handover.status === 'COMPLETED'"
                      @click="onCompleteHandover"
                      >{{ handover.status === 'COMPLETED' ? '已完成交接' : '完成交接' }}</el-button
                    >
                  </div>
                </template>
                <div v-else class="fuy-empty" role="status">
                  <p class="fuy-empty-title">尚未生成交接班</p>
                  <p class="fuy-empty-hint">点击生成本班交接班材料（SBAR 自动汇总）。</p>
                </div>
              </div>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- ⑤ 页脚批注（数据时点；禁放操作钮，契约 ⑧.8） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 4 }">
        数据时点 {{ formatTime(loadedAt ?? undefined) }} · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：动词结果 ≤1200ms 收场，aria-live 即时播报） -->
      <div v-if="stampVisible" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </section>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——不良事件
   ae-flow 同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与区块间距
   （header 0 / 卡墙 1 / 体温单 2 / 三列 3 / 页脚 4 五段级联序）由本层同值承接
   （.fuy-page 同构 flex+gap），保证视觉零漂移 */
.ward-board-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}
/* 门牌状态位控件组（病区/班次下拉 + 待复核胶囊 + 刷新） */
.ward-board-head-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-board-ward {
  width: 180px;
}
.ward-board-shift {
  width: 100px;
}
/* 待复核锚胶囊（<a> 承胶囊脸：去链接下划线，色随语义变体） */
.ward-board-review-anchor {
  text-decoration: none;
}

/* ② 卡墙（lg 8 列 / xl 10 列，min-height 240px CLS 锁） */
.ward-board-wall {
  min-height: 240px;
}
.ward-board-wall-meta {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.ward-board-wall-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px;
}
/* 床位纸面卡（G3 语法：卡面亮纸 + 发丝描边；旧 off-token 卡墙边字面量已清零走发丝） */
.ward-bed-card {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 148px;
  min-height: 84px;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-surface-card);
  text-align: left;
  cursor: pointer;
  /* 选中态 120ms 描边/底色过渡（PR-5 号源卡选中同形态） */
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
/* 选中态=金洗纸底 + 左缘 3px 墨条（既有形态承新值） */
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
/* 卡墙首拉骨架（.fuy-sk 金洗纸块，卡墙 240px CLS 锁档） */
.ward-wall-sk {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px;
  align-content: start;
  min-height: 240px;
}
.ward-wall-sk .fuy-sk {
  height: 84px;
}
/* 床号段分组组头（石规签行：上缘石规 + 灰墨小字，>120 卡折叠开关） */
.ward-wall-group-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-1);
  width: 100%;
  margin: var(--fuy-space-2) 0;
  padding: var(--fuy-space-1) 0;
  border: 0;
  border-top: 1px solid var(--fuy-color-rule-stone);
  background: transparent;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
  letter-spacing: 0.08em;
  text-align: left;
  cursor: pointer;
}

/* ④ 三列卡片（section.fuy-card 脸，卡间既有间距语义保留） */
.ward-board-mid-card {
  margin-bottom: var(--fuy-space-3);
}
.ward-board-task-head {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}
.ward-board-task-filter {
  width: 110px;
}

/* 患者详情 */
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

/* 责任护士分配 */
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

/* 体征录入（两行 × 四列 grid，min-height 240px CLS 锁） */
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

/* 待复核体征（纸面密排小表：fuy-flip 行级移除载体，发丝行线非石规下沉卡内） */
.ward-pending-wrap {
  overflow-x: auto;
}
.ward-pending-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fuy-font-size-sm);
}
.ward-pending-table th {
  padding: var(--fuy-space-1) var(--fuy-space-2);
  border-bottom: var(--fuy-border-hairline);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}
.ward-pending-table td {
  padding: var(--fuy-space-1) var(--fuy-space-2);
  border-bottom: var(--fuy-border-hairline);
  white-space: nowrap;
}
.ward-pending-empty {
  margin-top: var(--fuy-space-2);
}

/* 护理评估 */
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
.ward-board-scale {
  width: 200px;
}
.ward-scale-submit {
  margin-top: var(--fuy-space-3);
  width: 96px;
}
/* 结果条（content-fade 出现；HIGH 高危=印泥朱描边 + 印泥朱 8% 洗底（alpha 派生），§3.9 契约） */
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
  background: rgba(180, 35, 24, 0.08);
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

/* 任务与交接班 */
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

/* ③ 体温单（全宽，min-height 420px CLS 锁） */
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
/* 加载骨架（体温单 320px CLS 锁档，金洗纸静态块零 shimmer） */
.ward-chart-sk .fuy-sk {
  display: block;
  width: 100%;
  height: 320px;
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
</style>
