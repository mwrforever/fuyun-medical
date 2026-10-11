<script setup lang="ts">
// 入院登记台页（/inpatient/admission，M04 FU-M04-01 前端面 · 暖纸卷宗换脸重排 乙-1，
// 「换脸不换业务」：数据契约/表单校验/权限口径/出网参数/visit 签发链零变动）纵向序：
// 门牌页首（衬线标题 + 排序口径升批注行「急诊优先＞预约时段＞候床时长 · 谁 · 时刻」+ 刷新）→
// 两栏（PM 重排裁决：右栏「登记确认」升首位操作面——动线=队列点「登记」→右侧确认顶置缩短
// 视线行程）：左 14 栏=候床队列卡（状态签 .fuy-tabs 卷宗签化替换 radio-button），右 10 栏=
// 入院登记确认卡（上，选中摘要条金洗底 + 医保类型 + 确认钮）+ 住院证登记表单卡（下，两列
// 字段 grid，患者搜索/诊断摘要[脱敏提示]/开单医生）→ 预约入院弹窗（目标病区 listOrgs WARD
// 档真数据三态，空床联动预占）→ 页脚批注（数据时点）。成功反馈=钤印（已登记 · visit ××××/
// 已预约，v-if 挂载制防 stagger 误伤——护士站先例同构）；失败弹错归响应拦截器（AxiosError
// 防双弹，业务拒绝对象由 surfaceBizError 兜底展示 detail 原文——IP 域 4xx detail 已是中文
// 业务口径）；作废=ElMessageBox 中档确认（文案冻结）。患者身份按脱敏口径仅展示患者编号
// （后端 VO 契约即不含姓名），诊断摘要列标注脱敏提示。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
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
} from '@/api/inpatient';
import type { AdmissionVO, BedMapVO } from '@/api/inpatient';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

// 元素权限判定入口（PR-4F F8）：医生建单码行内与运算（#13）、登记员/护士入区码 v-perm（#14）
const auth = useAuthStore();

/** 住院证状态中文词表（CANCELLED/COMPLETED 兜底直显原文） */
const STATUS_LABELS: Record<string, string> = {
  WAITING: '候床中',
  SCHEDULED: '已预约',
  COMPLETED: '已入院',
  CANCELLED: '已作废',
};

/** 队列状态签四档（卷宗签语法 ⑤#8，radio-button 卷宗签化）：全部=已作废终态经此透出；
 * 动作三态=候床中（登记/预约入口）/已预约（登记入口）为冻结词表 ADMISSION_STATUS_OPTIONS，
 * 已入院为登记确认后的结果回查签（登记台 #1 高频动作的结果核对位） */
const STATUS_TABS: ReadonlyArray<{ code: string; label: string }> = [
  { code: '', label: '全部' },
  ...ADMISSION_STATUS_OPTIONS,
  { code: 'COMPLETED', label: STATUS_LABELS.COMPLETED ?? '已入院' },
];

/* ==================== 左栏：候床队列 ==================== */
/** 状态筛选（空串=全部状态；常规视图传 WAITING/SCHEDULED/COMPLETED） */
const statusFilter = ref('');

/** 队列数据时点（页脚批注「数据时点」真值；成功分支回填，失败驻留旧时点——G8 只叙事实） */
const queueLoadedAt = ref<string | null>(null);

/** 候床队列三段式（EX-49 范式迁移）：行集/总数/加载态经 usePagedList 收拢（固定首页
 * size 50 直出，后端冻结排序=急诊优先＞预约时段＞候床时长，前端按返回序直出）；状态筛选
 * 经快照工厂发起时实时取值，total 双形态由 composable 归一（行为与迁移前一致——失败弹错
 * 归响应拦截器、驻留旧清单） */
const {
  rows: queueRows,
  total: queueTotal,
  loading: queueLoading,
  fetch: loadQueue,
} = usePagedList({
  params: () => ({
    status: statusFilter.value === '' ? undefined : statusFilter.value,
  }),
  fetcher: ({ status, page, size }) => admissions.list({ status, page, size }),
  pageSize: 50,
  onSuccess: () => {
    queueLoadedAt.value = new Date().toISOString();
  },
});

/** 页脚数据时点展示串（未完成过任何一次成功加载以 — 占位，零伪数据） */
const queueLoadedAtLabel = computed(() =>
  queueLoadedAt.value === null ? '—' : formatTime(queueLoadedAt.value),
);

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
const scheduleForm = ref({ targetWardId: '', targetBedId: '', expectDate: '' });
/** 目标病区空床清单（预约携床位时同事务联动预占） */
const freeBeds = ref<BedMapVO[]>([]);

/* ---------- 预约弹窗目标病区（G4：WARD_OPTIONS 假常量清零 → listOrgs WARD 档真数据三态） ---------- */
/** 病区选项（启用机构清单，sort 升序；空清单/失败态下拉禁用——出网值锚定真实病区编码） */
const wardOptions = ref<OrgVO[]>([]);
const wardLoad = useAsyncTask(async () => {
  wardOptions.value = await listOrgs({ type: 'WARD' });
});
const wardLoading = wardLoad.loading;
const wardError = wardLoad.error;

/** 病区清单装载（装配预取与弹窗重试共用入口；单飞守卫——在途重复触发共享同一回包） */
let wardsInFlight: Promise<void> | null = null;
function loadWards(): Promise<void> {
  if (wardsInFlight === null) {
    wardsInFlight = wardLoad.run().then(() => undefined);
    void wardsInFlight.finally(() => {
      wardsInFlight = null;
    });
  }
  return wardsInFlight;
}

/** 病区下拉禁用态：清单在途或空清单（失败同形）皆不可选 */
const wardDisabled = computed(() => wardLoading.value || wardOptions.value.length === 0);

/** 预约表单病区默认项落位：清单就绪且未选时落首项并联动空床（openSchedule 与重试汇合点） */
async function applyScheduleWardDefault(): Promise<void> {
  if (scheduleForm.value.targetWardId === '' && wardOptions.value.length > 0) {
    scheduleForm.value.targetWardId = wardOptions.value[0]?.orgCode ?? '';
    await reloadFreeBeds();
  }
}

/** 打开预约弹窗时的病区面就位：清单未载且无失败驻留时先装载（失败给「说问题+给恢复」，
 * 由重试通道恢复），就绪后落默认病区 */
async function ensureScheduleWardDefault(): Promise<void> {
  if (wardOptions.value.length === 0 && wardError.value === null) {
    await loadWards();
  }
  await applyScheduleWardDefault();
}

/** 弹窗病区清单重试（说问题给恢复通道）：强制重载清单后落默认项 */
async function onWardRetry(): Promise<void> {
  await loadWards();
  await applyScheduleWardDefault();
}

/** 目标病区空床清单重载（床位选择随之刷新；失败弹错归响应拦截器，床位选择空表态） */
async function reloadFreeBeds(): Promise<void> {
  if (scheduleForm.value.targetWardId === '') {
    freeBeds.value = [];
    return;
  }
  try {
    const all = await beds.map(scheduleForm.value.targetWardId);
    freeBeds.value = all.filter((bed) => bed.bedStatus === 'FREE');
  } catch {
    freeBeds.value = [];
  }
}

/** 打开预约弹窗（仅 WAITING 态行暴露入口）并就位病区面（默认病区+空床联动） */
async function openSchedule(row: AdmissionVO): Promise<void> {
  scheduleTarget.value = row;
  scheduleForm.value = { targetWardId: '', targetBedId: '', expectDate: '' };
  freeBeds.value = [];
  scheduleVisible.value = true;
  await ensureScheduleWardDefault();
}

/** 切换目标病区后重载空床清单（床位选择随之刷新） */
async function onScheduleWardChange(): Promise<void> {
  scheduleForm.value.targetBedId = '';
  await reloadFreeBeds();
}

/** 预约提交：预约时段显式校验（缺项零出网）→ 出网 → 成功盖钤印关窗刷新队列。 */
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
    showStamp('已预约');
    scheduleVisible.value = false;
    await loadQueue();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    scheduling.value = false;
  }
}

/* ---------- 登记确认（右栏首位操作面，PM 重排升位） ---------- */
const selectedAdmission = ref<AdmissionVO | null>(null);
const insuranceType = ref('');
const registering = ref(false);

/** 行内「登记」：选中候床证驱动右栏登记确认区（确认卡摘要条显影） */
function selectForRegister(row: AdmissionVO): void {
  selectedAdmission.value = row;
  insuranceType.value = '';
}

/** 登记确认提交：未选证/未选医保显式校验零出网 → 出网 → 成功盖钤印+队列刷新。
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
    // 钤印含 visit 号回显（登记台 #1 高频动作的结果凭证面，契约 ⑧.5 动词结果+单号）
    showStamp(`已登记 · visit ${visit.visitId ?? ''}`);
    selectedAdmission.value = null;
    await loadQueue();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    registering.value = false;
  }
}

/* ---------- 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ---------- */
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画；
 * v-if 挂载制=护士站先例同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤为
 * 常显空朱块，挂载即 is-show（stamp-in 特异性压过 rise）根治 */
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

/* ==================== 右栏：住院证登记表单（建证卡，DOM 序于确认卡之后） ==================== */
const creating = ref(false);
/** 患者搜索态（检索词/脱敏结果行/选中患者） */
const patientKeyword = ref('');
const patientResults = ref<PatientVO[]>([]);
const selectedPatientId = ref('');
const createForm = ref({
  sourceType: SOURCE_TYPE_OPTIONS[0]?.code ?? '',
  admissionType: ADMISSION_TYPE_OPTIONS[0]?.code ?? '',
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

/** 住院证创建：患者/开单医生显式校验零出网 → 出网 → 成功提示+刷新队列。 */
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
      sourceType: SOURCE_TYPE_OPTIONS[0]?.code ?? '',
      admissionType: ADMISSION_TYPE_OPTIONS[0]?.code ?? '',
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

/* ---------- 门牌批注行：登记员（会话身份） · 时刻（本地时钟零出网） ---------- */
/** 登记员批注：会话显示名真值；空会话以 — 占位（防御场景，路由守卫默认拒绝未登录） */
const signerName = computed(() => auth.user?.displayName ?? '—');

/** 当日批注行时刻标签（YYYY-MM-DD 周X）：病历页眉日期批注语法，纯本地时钟零出网 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

onMounted(() => {
  void loadQueue();
  void loadWards();
});
</script>

<template>
  <div class="fuy-page admission-view">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——护士站
         ward-board-flow 同构形态）：三段级联序 门牌 0 / 两栏 1 / 页脚 2 -->
    <div class="fuy-stagger admission-flow">
      <!-- ① 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注（装饰层）+ 状态位（刷新钮） +
           排序口径升批注行（原 hint 文案逐字保留，「谁·何时」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">入院登记台</h1>
          <span class="fuy-page-en" aria-hidden="true">ADMISSION DESK</span>
        </div>
        <div class="fuy-page-status">
          <el-button class="admission-refresh" :loading="queueLoading" @click="loadQueue"
            >刷新</el-button
          >
        </div>
        <p class="fuy-page-note">
          排序：急诊优先 ＞ 预约时段 ＞ 候床时长 · 登记员 {{ signerName }} ·
          <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- ② 两栏（el-row gutter 16，lg 14/10 既有；PM 重排裁决：右栏登记确认卡升首位） -->
      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左栏：候床队列卡（卷宗卡 + .fuy-dense 密排——挂卡容器构成后代关系） -->
        <el-col :md="24" :lg="14">
          <section class="fuy-card fuy-dense admission-queue-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">候床队列</h2>
              <span class="admission-queue-total"
                >共 <span class="fuy-num">{{ queueTotal }}</span> 条</span
              >
            </header>
            <div class="fuy-card-body admission-queue-body">
              <!-- 状态签（卷宗签语法 ⑤#8：激活签=顶部朱砂签线，由 .fuy-tabs 全局段承载；
                   签即筛选——切换即按状态出网重载） -->
              <el-tabs
                v-model="statusFilter"
                class="fuy-tabs admission-status-tabs"
                @tab-change="loadQueue"
              >
                <el-tab-pane
                  v-for="tab in STATUS_TABS"
                  :key="tab.code"
                  :label="tab.label"
                  :name="tab.code"
                />
              </el-tabs>
              <div v-loading="queueLoading" class="admission-queue-table-wrap">
                <el-table
                  v-if="queueRows.length > 0"
                  :data="queueRows"
                  class="fuy-dense"
                  size="small"
                >
                  <el-table-column label="住院证号" min-width="130">
                    <template #default="{ row }">
                      <span class="fuy-num">{{ row.admissionNo }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="类型" width="130">
                    <template #default="{ row }">
                      {{ admissionTypeLabel(row.admissionType) }}
                      <!-- 急诊优先排序标识（后端置顶排序的前端透出，机器判据类；
                           印泥朱实底白字=强状态语义，triage-l1 语义通道承新值） -->
                      <span
                        v-if="row.admissionType === 'EMERGENCY'"
                        class="admission-emergency-flag"
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
                      <!-- 行内动作（PR-4F #13）：医生建单码与票据状态数据态行内与运算（权限在
                           外数据在内，两层独立判定）；登记选择为 #14 入区链路前置，建单与登记
                           两类角色共用（纯 UI 选中不出网），故不挂元素码 -->
                      <el-button
                        v-if="
                          row.status === 'WAITING' && auth.hasPerm('inpatient:admission:btn:create')
                        "
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
                        v-if="
                          (row.status === 'WAITING' || row.status === 'SCHEDULED') &&
                          auth.hasPerm('inpatient:admission:btn:create')
                        "
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
                <!-- 诚实空态脸（契约 ⑥/⑧：暂无〈业务客体〉+ 给下一步，el-empty 纸箱插画零出现） -->
                <div v-else class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无候床住院证</p>
                  <p class="fuy-empty-hint">当前状态签下暂无候床记录，可切换状态签或点击刷新。</p>
                </div>
              </div>
            </div>
          </section>
        </el-col>

        <!-- 右栏：登记确认卡（上，升位） + 住院证登记表单卡（下） -->
        <el-col :md="24" :lg="10">
          <!-- 入院登记确认卡（PM 重排升位：动线=队列点「登记」→右侧确认顶置缩短视线行程；
               DOM 序先于建证卡——锚点①机器证据） -->
          <section class="fuy-card admission-confirm-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">入院登记确认</h2>
              <span class="admission-queue-total">visit 号由系统签发</span>
            </header>
            <div class="fuy-card-body">
              <!-- 选中摘要条显影（点「登记」后 .fuy-content-fade 换装：金洗底=选中态语义） -->
              <Transition name="fuy-content-fade" mode="out-in">
                <p
                  v-if="selectedAdmission === null"
                  key="confirm-empty"
                  class="admission-selected is-empty"
                >
                  从候床队列点击「登记」选择住院证
                </p>
                <p v-else key="confirm-summary" class="admission-selected">{{ selectedSummary }}</p>
              </Transition>
              <label class="admission-field-label" for="admission-insurance"
                >医保类型（登记必填，visit 号由系统签发）</label
              >
              <select
                id="admission-insurance"
                v-model="insuranceType"
                class="admission-input"
                aria-label="医保类型"
              >
                <option value="">请选择医保类型</option>
                <option v-for="item in INSURANCE_TYPE_OPTIONS" :key="item.code" :value="item.code">
                  {{ item.label }}
                </option>
              </select>
              <!-- 入区登记确认（PR-4F #14）：登记员/护士码 v-perm 直挂——与 selectedAdmission
                   选中数据态 :disabled 正交叠加（F6④ 扩绑后 NURSE 可入页办理） -->
              <el-button
                v-perm="'inpatient:admission:btn:register'"
                type="primary"
                class="admission-submit"
                :loading="registering"
                :disabled="registering || selectedAdmission === null"
                @click="onRegister"
                >登记确认</el-button
              >
            </div>
          </section>

          <!-- 住院证登记表单卡（医生建证：检索患者→建证；两列字段 grid 既有） -->
          <section class="fuy-card admission-form-card">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">住院证登记</h2>
            </header>
            <div class="fuy-card-body">
              <el-form class="fuy-form" label-position="top" size="small">
                <div class="admission-search-row">
                  <input
                    v-model="patientKeyword"
                    class="admission-input"
                    placeholder="姓名/证件号/手机号"
                    @keyup.enter="onSearchPatient"
                  />
                  <el-button size="small" @click="onSearchPatient">搜索</el-button>
                </div>
                <label class="admission-field-label" for="admission-patient"
                  >患者（检索结果，档案按脱敏口径展示）</label
                >
                <select
                  id="admission-patient"
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
                    <label class="admission-field-label" for="admission-source">来源类型</label>
                    <select
                      id="admission-source"
                      v-model="createForm.sourceType"
                      class="admission-input"
                    >
                      <option
                        v-for="item in SOURCE_TYPE_OPTIONS"
                        :key="item.code"
                        :value="item.code"
                      >
                        {{ item.label }}
                      </option>
                    </select>
                  </div>
                  <div class="admission-field">
                    <label class="admission-field-label" for="admission-type">入院类型</label>
                    <select
                      id="admission-type"
                      v-model="createForm.admissionType"
                      class="admission-input"
                    >
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
                    <label class="admission-field-label" for="admission-expect">预期入院日期</label>
                    <input
                      id="admission-expect"
                      v-model="createForm.expectDate"
                      type="date"
                      class="admission-input fuy-num"
                    />
                  </div>
                  <div class="admission-field">
                    <label class="admission-field-label" for="admission-doctor">开单医生工号</label>
                    <input
                      id="admission-doctor"
                      v-model="createForm.issuedDoctorId"
                      class="admission-input"
                      placeholder="开单医生工号"
                    />
                  </div>
                </div>
                <label class="admission-field-label" for="admission-diagnosis"
                  >诊断摘要（敏感信息：展示与留存按脱敏口径，≤255 字）</label
                >
                <textarea
                  id="admission-diagnosis"
                  v-model="createForm.diagnosisSummary"
                  class="admission-input admission-textarea"
                  rows="2"
                  maxlength="255"
                  placeholder="入院诊断摘要"
                ></textarea>
                <!-- 创建住院证（PR-4F #13）：医生建单码 v-perm 直挂——与 creating 在途数据态
                     正交叠加 -->
                <el-button
                  v-perm="'inpatient:admission:btn:create'"
                  type="primary"
                  class="admission-submit"
                  :loading="creating"
                  :disabled="creating"
                  @click="onCreate"
                  >创建住院证</el-button
                >
              </el-form>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- ③ 预约入院弹窗（携床位时同事务联动预占；目标病区=listOrgs 真数据三态） -->
      <el-dialog v-model="scheduleVisible" title="预约入院" width="420px" class="fuy-dialog">
        <p class="admission-schedule-target fuy-num">
          住院证 {{ scheduleTarget?.admissionNo ?? '' }}
        </p>
        <label class="admission-field-label" for="admission-schedule-ward">目标病区</label>
        <!-- 病区下拉换真数据（乙-1.5：WARD_OPTIONS 假常量清零→listOrgs WARD 档）；
             清单在途/空清单禁用——出网值必须锚定真实病区编码 -->
        <select
          id="admission-schedule-ward"
          v-model="scheduleForm.targetWardId"
          class="admission-input"
          aria-label="目标病区"
          :disabled="wardDisabled"
          @change="onScheduleWardChange"
        >
          <option v-for="org in wardOptions" :key="org.orgCode" :value="org.orgCode ?? ''">
            {{ org.orgName }}（{{ org.orgCode }}）
          </option>
        </select>
        <!-- 清单失败=说问题给恢复（G4/G6：不伪装空选项），重试同路恢复默认病区 -->
        <div v-if="wardError !== null" class="admission-ward-error" role="alert">
          <span>病区清单加载失败，暂无法选择目标病区</span>
          <el-button link type="primary" size="small" :loading="wardLoading" @click="onWardRetry"
            >重试</el-button
          >
        </div>
        <label class="admission-field-label" for="admission-schedule-bed"
          >目标床位（空床，可空=到院再签）</label
        >
        <select
          id="admission-schedule-bed"
          v-model="scheduleForm.targetBedId"
          class="admission-input"
          aria-label="目标床位"
        >
          <option value="">暂不指定床位</option>
          <option v-for="bed in freeBeds" :key="bed.bedId" :value="bed.bedId ?? ''">
            {{ bed.bedNo }}
          </option>
        </select>
        <label class="admission-field-label" for="admission-schedule-date"
          >预约入院日期（必填）</label
        >
        <input
          id="admission-schedule-date"
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

      <!-- 页脚批注（契约 ⑧.8）：数据时点=最近一次成功加载时点 + 手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        数据时点 {{ queueLoadedAtLabel }} · 手动刷新取数
      </footer>

      <!-- 钤印确认反馈（契约 ⑧.5：只承成功确认；已登记含 visit 回显/已预约；v-if 挂载制=
           护士站先例同构——防 stagger 误伤常驻泄漏，seq 递增重挂重放落章动画，≤1200ms 收场） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——护士站
   ward-board-flow 同构形态）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与
   区块间距（门牌 0 / 两栏 1 / 页脚 2 三段级联序）由本层同值承接（.fuy-page 同构 flex+gap），
   保证视觉零漂移 */
.admission-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：刷新钮右挂同行（.fuy-page-status 全局段只承载 margin-left:auto，补 flex 同行） */
.fuy-page-status {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 队列卡头计数/确认卡副注（12px 灰墨，卡头右侧随 .fuy-card-head 空间分布） */
.admission-queue-total {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 队列卡体 CLS 锁（蓝图 乙-1.6：骨架=队列卡 min-height 320px）——加载/空态切换零塌陷 */
.admission-queue-body {
  box-sizing: border-box;
  min-height: 320px;
}

/* 状态签与表格间距（卷宗签下缘呼吸一档） */
.admission-status-tabs {
  margin-bottom: var(--fuy-space-2);
}

/* 急诊优先排序标识（后端冻结排序的前端透出）：印泥朱实底白字小徽标=强状态语义保留，
   取值经 triage-l1 语义通道承新值（双通道：徽标文字「急诊优先」在位）；白字取最亮纸白
   语义通道 --el-fill-color-blank（表单域最亮纸面同源），页内 scoped 零新 hex 字面量 */
.admission-emergency-flag {
  margin-left: var(--fuy-space-1);
  padding: 0 var(--fuy-space-1);
  border-radius: var(--fuy-radius-sm);
  background: var(--fuy-color-triage-l1);
  color: var(--el-fill-color-blank);
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

/* 表单基元：native select/input 与 EP 密度口径对齐（存量 jsdom 可测口径保留） */
.admission-search-row {
  display: flex;
  gap: var(--fuy-space-2);
}

/* 表单基元：native select/input 与 EP 密度口径对齐（存量 jsdom 可测口径保留）；
   围合取值直取 --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边，与卡面
   拉开一档；石规与 EP border 映射同源，底色自卡面亮纸矫正为表单域最亮纸白） */
.admission-input {
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

/* 右栏两卡纵距（el-col 内无根级 gap，卡间距自给——建证卡随确认卡之后） */
.admission-form-card {
  margin-top: var(--fuy-space-3);
}

/* 选中摘要条：金洗底（brand-100 选中态同源——蓝图「金洗底保留换值」）；
   空态=悬停暖底弱形态灰墨（未选证引导位） */
.admission-selected {
  margin: 0 0 var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-brand-100);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.admission-selected.is-empty {
  background: var(--fuy-palette-gray-50);
  color: var(--fuy-color-text-secondary);
}

/* 预约弹窗病区清单失败恢复行（说问题给恢复：状态朱文字+重试动作，G6） */
.admission-ward-error {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-danger-text);
}

.admission-schedule-target {
  margin: 0 0 var(--fuy-space-3);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}
</style>
