<script setup lang="ts">
// 冷链台账页（/ward/cold-chain，M16 冷链治理面前端面 · 暖纸卷宗换脸重排 丁-3，「换脸不换
// 业务」：双人复核强制/告警处置状态机/曲线在途守卫/告警丢弃守卫与显式校验零出网零变动）。
// 纵向序（PM 重排裁决）：门牌页首（衬线标题 + 页首状态位=用途筛选+新建档案+刷新 + 原 hint
// 升批注行「谁·时刻」）→ 档案列表卡（全宽 .fuy-dense 50/页，overdue「已到期」印泥朱徽标）→
// 详情 720 弹窗升 640px 抽屉（挂 .fuy-drawer 卷宗阅读面语义，头部批注槽=档案号·用途·温区·
// 设备·到期态）；抽屉内三段以 .fuy-sign-divider 分界：巡检/处置记录表 → 登记表单（告警处置
// ALARM_HANDLE 强制双人复核字段 alarmRef+secondOperator，缺一零出网）→ 温度曲线（复用 iot
// series API 以 scope=device 查询最近 24h 聚合点列，SVG 520×160 轻量渲染——不引 echarts，
// echarts 归 bigscreen；曲线加发丝网格与温度刻度承体温单 chart-grid 语法，单点/空点退化态
// 保留）。新建档案弹窗挂 .fuy-dialog。成功反馈=钤印「已登记」（契约 ⑧.5，≤1200ms 收场），
// 失败与校验拦截=message 批注条。全部写操作自带在途守卫（入口早退先于一切 await）。
// `/iot/telemetry/latest` 不取用（台账终裁：冷链指标编码仍为联调期占位，COLDCHAIN_TEMP 未入
// MDC 词表，取用放大占位暴露面且曲线已按需查询）。
import { computed, onMounted, onScopeDispose, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { telemetry } from '@/api/iot';
import type { TelemetryPoint } from '@/api/iot';
import {
  COLD_PURPOSE_LABELS,
  COLD_RECORD_TYPE_LABELS,
  TEMP_RANGE_LABELS,
  coldChain,
} from '@/api/ward';
import type {
  ColdChainArchiveVO,
  ColdChainRecordVO,
  RegisterColdChainRecordRequest,
  SaveColdChainArchiveRequest,
} from '@/api/ward';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { usePagedList } from '@/composables/usePagedList';
import { useAuthStore } from '@/stores/auth';
import { surfaceBizError } from '@/utils/bizError';
import { formatTime } from '@/utils/timeFormat';

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

/* ==================== 钤印成功反馈（契约 ⑧.5，≤1200ms 收场） ==================== */
/** 钤印收场时长（契约 ⑧.5：≤1200ms 随页面收场） */
const STAMP_DURATION_MS = 1200;
/** 钤印文案（空串=无钤印在展）；seq 每盖一次递增，模板以 :key 重挂重放落章动画 */
const stampText = ref('');
const stampVisible = ref(false);
const stampSeq = ref(0);
let stampTimer: ReturnType<typeof setTimeout> | null = null;

/** 盖钤印（只承成功确认；失败与校验拦截归 message 批注条，钤印永不承失败） */
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

// 组件卸载时清理在途收场定时器（设备管理页钤印段同构口径）
onScopeDispose(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

/* ==================== 词表反查（列徽标与详情批注共用） ==================== */

/** 用途中文词表反查（用途列徽标） */
function purposeLabel(code: string | undefined): string {
  return COLD_PURPOSE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 用途徽标状态类（fuy-archive-tag--{purpose} 契约类，色值经语义 token 承载） */
function purposeClass(code: string | undefined): string {
  return `fuy-archive-tag--${(code ?? '').toLowerCase()}`;
}

/** 温区中文词表反查（温区列） */
function tempRangeLabel(code: string | undefined): string {
  return TEMP_RANGE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 记录类型中文词表反查（记录列表类型列） */
function recordTypeLabel(code: string | undefined): string {
  return COLD_RECORD_TYPE_LABELS[code ?? ''] ?? code ?? '—';
}

/* ==================== 档案列表 ==================== */
/** 用途筛选（空串=全部四用途） */
const purposeFilter = ref('');

/** 加载档案列表（purpose 过滤由后端承载，overdue 到期标记由后端承载）：页码/行集/加载态
 * 经 usePagedList 收拢（EX-49 范式迁移，固定首页 size 50 直出，行为与迁移前一致——失败
 * 弹错归响应拦截器；驻留旧清单） */
const {
  rows,
  total,
  loading: listLoading,
  fetch: loadList,
} = usePagedList({
  params: () => ({
    purpose: purposeFilter.value === '' ? undefined : purposeFilter.value,
  }),
  fetcher: ({ purpose, page, size }) => coldChain.page({ purpose, page, size }),
  pageSize: 50,
});

/* ==================== 新建档案弹窗 ==================== */
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref({
  purpose: 'VACCINE',
  deviceId: '',
  tempRangeType: 'COOL',
  verifyDueAt: '',
  inventoryDigest: '',
});

/** 打开新建档案弹窗（空表单，缺省疫苗/冷藏） */
function openCreate(): void {
  createForm.value = {
    purpose: 'VACCINE',
    deviceId: '',
    tempRangeType: 'COOL',
    verifyDueAt: '',
    inventoryDigest: '',
  };
  createVisible.value = true;
}

/**
 * 保存档案：设备必填显式校验 + 校验到期时间格式校验（非空须为合法日期，零出网）→
 * 出网 → 钤印「已登记」→ 关窗刷新。
 */
async function onCreate(): Promise<void> {
  if (creating.value) {
    return;
  }
  if (createForm.value.deviceId.trim() === '') {
    void ElMessage.warning('请填写设备 ID');
    return;
  }
  let verifyDueAt: string | undefined = undefined;
  if (createForm.value.verifyDueAt.trim() !== '') {
    const parsed = new Date(createForm.value.verifyDueAt.trim());
    // 显式格式校验（Number.isFinite 判定非法日期），防裸传 4xx
    if (!Number.isFinite(parsed.getTime())) {
      void ElMessage.warning(
        '校验到期时间须为合法日期时间（ISO 8601，如 2026-12-31T23:59:59+08:00）',
      );
      return;
    }
    verifyDueAt = createForm.value.verifyDueAt.trim();
  }
  creating.value = true;
  try {
    await coldChain.create({
      purpose: createForm.value.purpose as SaveColdChainArchiveRequest['purpose'],
      deviceId: createForm.value.deviceId.trim(),
      tempRangeType: createForm.value.tempRangeType as SaveColdChainArchiveRequest['tempRangeType'],
      verifyDueAt,
      inventoryDigest:
        createForm.value.inventoryDigest.trim() === ''
          ? undefined
          : createForm.value.inventoryDigest.trim(),
    });
    // 成功反馈=钤印「已登记」（契约 ⑧.5：动词结果文案；失败归 surfaceBizError 不盖钤印）
    showStamp('已登记');
    createVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ==================== 档案详情抽屉（记录 + 登记 + 温度曲线 三段卷宗阅读面） ==================== */
const detailVisible = ref(false);
/** 详情目标档案（抽屉上下文锚点；批注槽回显与登记/曲线共用） */
const detailArchive = ref<ColdChainArchiveVO | null>(null);
const recordRows = ref<ColdChainRecordVO[]>([]);

/** 装载详情出网（detail 权威回显 + records 记录全集并行拉取）：loading 骨架经 useAsyncTask
 * 收拢（EX-42 范式迁移，失败兜底展示经 onError 注入 surfaceBizError，口径与迁移前一致） */
const { loading: detailLoading, run: loadDetail } = useAsyncTask(
  async (archiveNo: string) => {
    const [archive, records] = await Promise.all([
      coldChain.detail(archiveNo),
      coldChain.records(archiveNo),
    ]);
    // detail 出参为权威回显源（行数据仅作入口锚点）
    detailArchive.value = archive;
    recordRows.value = records;
  },
  { onError: surfaceBizError },
);

/** 打开详情抽屉（前置锚定入口行并开抽屉）后发起装载（失败弹错归拦截器） */
async function openDetail(row: ColdChainArchiveVO): Promise<void> {
  detailArchive.value = row;
  detailVisible.value = true;
  if (row.archiveNo !== undefined) {
    await loadDetail(row.archiveNo);
  }
}

/** 重载详情记录（登记成功后刷新） */
async function reloadRecords(): Promise<void> {
  const archiveNo = detailArchive.value?.archiveNo;
  if (archiveNo === undefined) {
    return;
  }
  recordRows.value = await coldChain.records(archiveNo);
}

/* ==================== 登记表单（巡检/告警处置双人复核/偏差） ==================== */
const registering = ref(false);
const recordForm = ref({
  recordType: 'INSPECTION',
  alarmRef: '',
  secondOperator: '',
  content: '',
});

/**
 * 提交登记：告警处置强制双人复核字段（alarmRef+secondOperator 缺一零出网）→ 出网 →
 * 钤印「已登记」→ 清表单刷新记录。
 */
async function onRegister(): Promise<void> {
  if (registering.value) {
    return;
  }
  const archiveNo = detailArchive.value?.archiveNo;
  if (archiveNo === undefined) {
    return;
  }
  if (
    recordForm.value.recordType === 'ALARM_HANDLE' &&
    (recordForm.value.alarmRef.trim() === '' || recordForm.value.secondOperator.trim() === '')
  ) {
    void ElMessage.warning('告警处置须填写告警号与双人复核人');
    return;
  }
  registering.value = true;
  try {
    await coldChain.registerRecord(archiveNo, {
      recordType: recordForm.value.recordType as RegisterColdChainRecordRequest['recordType'],
      alarmRef:
        recordForm.value.alarmRef.trim() === '' ? undefined : recordForm.value.alarmRef.trim(),
      secondOperator:
        recordForm.value.secondOperator.trim() === ''
          ? undefined
          : recordForm.value.secondOperator.trim(),
      content: recordForm.value.content.trim() === '' ? undefined : recordForm.value.content.trim(),
    });
    // 成功反馈=钤印「已登记」（契约 ⑧.5；双人复核拦截归 message 不盖钤印）
    showStamp('已登记');
    recordForm.value = {
      recordType: recordForm.value.recordType,
      alarmRef: '',
      secondOperator: '',
      content: '',
    };
    await reloadRecords();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    registering.value = false;
  }
}

/* ==================== 温度曲线（iot series + SVG 轻量渲染 + 发丝网格刻度） ==================== */

/** 曲线查询指标编码（冷链温度指标编码未入 MDC 词表种子——联调期占位，对齐物模型后修订） */
const curveMetricCode = ref('COLDCHAIN_TEMP');
const curvePoints = ref<TelemetryPoint[]>([]);
/** 曲线时间窗（毫秒）：最近 24 小时 */
const CURVE_WINDOW_MS = 24 * 60 * 60 * 1000;
/** SVG 画布几何（viewBox 坐标系；内边距防折线贴边）——曲线绘图区 520×160 几何既有，
 * 左侧加 48px 轴槽承温度刻度（体温单 AXIS 同构），viewBox 总宽=轴槽+绘图区 */
const CURVE_W = 520;
const CURVE_H = 160;
const CURVE_PAD = 12;
/** 左轴槽宽（温度刻度文字右对齐于此；绘图区经 g translate 平移，折线几何零漂移） */
const CURVE_AXIS_W = 48;
/** 网格刻度分位（0/25/50/75/100% 对应 max→min 五档，发丝网格承体温单 chart-grid 语法） */
const CURVE_TICK_FRACTIONS = [0, 0.25, 0.5, 0.75, 1] as const;

/** 曲线查询出网（scope=device 按档案设备查询最近 24h 聚合点列）：loading 骨架经
 * useAsyncTask 收拢（EX-42 范式迁移，失败兜底展示经 onError 注入 surfaceBizError） */
const { loading: curveLoading, run: loadCurve } = useAsyncTask(
  async (deviceId: string) => {
    curvePoints.value = await telemetry.series({
      scope: 'device',
      deviceId,
      metricCode: curveMetricCode.value.trim(),
      from: new Date(Date.now() - CURVE_WINDOW_MS).toISOString(),
      to: new Date().toISOString(),
    });
  },
  { onError: surfaceBizError },
);

/** 曲线查询入口：在途守卫与档案设备缺失 warning 早退留包装函数（前置拦截零出网） */
async function onQueryCurve(): Promise<void> {
  if (curveLoading.value) {
    return;
  }
  const deviceId = detailArchive.value?.deviceId;
  if (deviceId === undefined || deviceId === '') {
    void ElMessage.warning('档案未关联设备，无法查询温度曲线');
    return;
  }
  await loadCurve(deviceId);
}

/** 折线点坐标（avg 优先、last 兜底；单点退化为短横刻记、空点空串——polyline 单坐标
 * 零渲染，刻记保「有数据」语义，与丁-1 输液曲线退化态同构口径） */
const polylinePoints = computed(() => {
  const values = curvePoints.value.map((point) => point.avg ?? point.last ?? 0);
  if (values.length === 0) {
    return '';
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const usableW = CURVE_W - CURVE_PAD * 2;
  const usableH = CURVE_H - CURVE_PAD * 2;
  const coords = values.map((value, index) => {
    const x =
      CURVE_PAD + (values.length === 1 ? usableW / 2 : (index / (values.length - 1)) * usableW);
    // Y 轴反转：温度越高越靠上
    const y = CURVE_PAD + usableH - ((value - min) / span) * usableH;
    return { x, y };
  });
  // 单点退化：单坐标 polyline 零渲染，居中点扩为 ±3px 短横刻记（无网格基准的可见落点）
  if (coords.length === 1) {
    const only = coords[0];
    return `${(only.x - 3).toFixed(1)},${only.y.toFixed(1)} ${(only.x + 3).toFixed(1)},${only.y.toFixed(1)}`;
  }
  return coords.map((point) => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ');
});

/** 发丝网格与温度刻度（承体温单 chart-grid 语法）：可用高度按五分位取档，刻度值=max-f×
 * 跨度（0 位在上=max）；单点/空点无范围基准时返回空集不渲染（退化态——单点无法构成
 * 网格基准，空点回归空态脸） */
const curveTicks = computed(() => {
  const values = curvePoints.value.map((point) => point.avg ?? point.last ?? 0);
  if (values.length < 2) {
    return [];
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const usableH = CURVE_H - CURVE_PAD * 2;
  return CURVE_TICK_FRACTIONS.map((fraction) => ({
    fraction,
    // 网格线/刻度 y 与折线值映射同式（f=0 顶边=max，f=1 底边=min），保证刻度对齐曲线
    y: CURVE_PAD + fraction * usableH,
    label: (max - fraction * span).toFixed(1),
  }));
});

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page cold-chain">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点禁挂 .fuy-stagger，stagger 挂页内区块——设备管理
         device-flow 同构）：本层承接根级 flex+gap 纵排，三段级联序 header 0 / 列表卡 1 / 页脚 2 -->
    <div class="fuy-stagger cold-flow">
      <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 英文小注 + 页首状态位（用途筛选+新建档案+刷新，
           蓝图 丁-3.2）+ 批注行（原 hint 升批注，「谁·时刻」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">冷链台账</h1>
          <span class="fuy-page-en" aria-hidden="true">COLD CHAIN</span>
        </div>
        <div class="fuy-page-status">
          <!-- 用途筛选（native select jsdom 可测口径存量件；契约 ⑧.3 label 疏排） -->
          <div class="fuy-filter cold-head-filter">
            <label for="cold-purpose-filter">用途筛选</label>
            <select
              id="cold-purpose-filter"
              v-model="purposeFilter"
              class="cold-filter-select"
              aria-label="用途筛选"
              @change="loadList"
            >
              <option value="">全部用途</option>
              <option v-for="(label, code) in COLD_PURPOSE_LABELS" :key="code" :value="code">
                {{ label }}
              </option>
            </select>
          </div>
          <el-button type="primary" size="small" @click="openCreate">新建档案</el-button>
          <el-button
            link
            type="primary"
            size="small"
            :loading="listLoading"
            :disabled="listLoading"
            @click="loadList"
            >刷新</el-button
          >
        </div>
        <p class="fuy-page-note">
          疫苗/血液/试剂/药品档案 · 告警处置双人复核 · 校验到期归后端承载 · 签认人
          {{ signerName }} · <time>{{ todayLabel }}</time>
        </p>
      </header>

      <!-- 档案列表卡（卷宗卡 + .fuy-dense 密排；overdue 到期判定由后端承载） -->
      <section class="fuy-card fuy-dense" :style="{ '--fuy-stagger-index': 1 }">
        <header class="fuy-card-head">
          <h2 class="fuy-card-title">冷链档案</h2>
          <!-- 计数取后端 total（后端筛选口径全量条数）而非当页行集——50/页下 rows.length
               只是当页截断值，卡头「共 N 条」必须是全量真值（G6 禁伪数据） -->
          <span class="cold-list-total"
            >共 <span class="fuy-num">{{ total }}</span> 条</span
          >
        </header>
        <div class="fuy-card-body">
          <!-- 列表体 min-height 240px 锁 CLS（蓝图 丁-3.6 骨架档） -->
          <div v-loading="listLoading" class="cold-list-body">
            <el-table :data="rows">
              <el-table-column label="档案号" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.archiveNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="用途" width="76">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="purposeClass(row.purpose)">
                    {{ purposeLabel(row.purpose) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="设备 ID" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="温区" width="70">
                <template #default="{ row }">{{ tempRangeLabel(row.tempRangeType) }}</template>
              </el-table-column>
              <el-table-column label="库存摘要" min-width="140">
                <template #default="{ row }">
                  <span :title="row.inventoryDigest">{{ row.inventoryDigest ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="校验到期" width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.verifyDueAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="到期" width="88">
                <template #default="{ row }">
                  <el-tag v-if="row.overdue" size="small" class="fuy-tag-aa fuy-archive-overdue"
                    >已到期</el-tag
                  >
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="登记时间" width="96">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="64" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openDetail(row)"
                    >详情</el-button
                  >
                </template>
              </el-table-column>
              <!-- 空态脸（契约 ⑥）：暂无语法 + 下一步指引，零默认纸箱插画 -->
              <template #empty>
                <div class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无冷链档案</p>
                  <p class="fuy-empty-hint">可调整用途筛选，或从页首「新建档案」建立首份档案。</p>
                </div>
              </template>
            </el-table>
          </div>
        </div>
      </section>

      <!-- 页脚批注（契约 ⑧.8）：刷新与曲线取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 2 }">
        档案与记录手动刷新取数 · 温度曲线按需查询最近 24 小时聚合点（后端聚合）
      </footer>

      <!-- 新建档案弹窗（契约 G2/⑤#9：.fuy-dialog 弹层脸 + .fuy-form 表单脸） -->
      <el-dialog v-model="createVisible" title="新建冷链档案" width="480px" class="fuy-dialog">
        <div class="fuy-form">
          <div class="cold-field-grid">
            <div class="cold-field">
              <label class="cold-field-label">冷链用途</label>
              <select v-model="createForm.purpose" class="cold-input" aria-label="冷链用途">
                <option v-for="(label, code) in COLD_PURPOSE_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
            <div class="cold-field">
              <label class="cold-field-label">温区</label>
              <select v-model="createForm.tempRangeType" class="cold-input" aria-label="温区">
                <option v-for="(label, code) in TEMP_RANGE_LABELS" :key="code" :value="code">
                  {{ label }}
                </option>
              </select>
            </div>
          </div>
          <label class="cold-field-label">设备 ID（必填，须为已注册冷链设备）</label>
          <input
            v-model="createForm.deviceId"
            class="cold-input fuy-num"
            placeholder="如：dev-fridge-1"
            aria-label="设备 ID"
          />
          <label class="cold-field-label">校验到期时间（可空，ISO 8601）</label>
          <input
            v-model="createForm.verifyDueAt"
            class="cold-input fuy-num"
            placeholder="如：2026-12-31T23:59:59+08:00"
            aria-label="校验到期时间"
          />
          <label class="cold-field-label">库存摘要（可空）</label>
          <input
            v-model="createForm.inventoryDigest"
            class="cold-input"
            placeholder="如：疫苗批次 2026-09 批 12 件"
            aria-label="库存摘要"
          />
        </div>
        <template #footer>
          <el-button size="small" @click="createVisible = false">取消</el-button>
          <!-- 保存档案（PR-4F #40「新建档案」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——无码
               DOM 移除（D-34），建档 POST 以本按钮为唯一出口：入口「新建档案」按盘点口径
               不挂码，无码会话仅可开窗查看不可出网；:loading/:disabled 在途数据态与权限
               判定两层正交 -->
          <el-button
            v-perm="'ward:coldchain:btn:manage'"
            type="primary"
            size="small"
            :loading="creating"
            :disabled="creating"
            @click="onCreate"
            >保存档案</el-button
          >
        </template>
      </el-dialog>

      <!-- 档案详情抽屉（720 弹窗→640 抽屉升档，PM 重排裁决：卷宗阅读面语义；契约 G2/⑤#10
           挂 .fuy-drawer）：头部批注槽=档案号·用途·温区·设备·到期态；内部三段以
           .fuy-sign-divider 分界（记录表 → 登记表单 → 温度曲线） -->
      <el-drawer v-model="detailVisible" title="冷链档案详情" size="640px" class="fuy-drawer">
        <p v-if="detailArchive !== null" class="cold-drawer-note">
          <span class="fuy-num">{{ detailArchive.archiveNo }}</span> ·
          {{ purposeLabel(detailArchive.purpose) }} ·
          {{ tempRangeLabel(detailArchive.tempRangeType) }} · 设备
          <span class="fuy-num">{{ detailArchive.deviceId ?? '—' }}</span>
          <el-tag v-if="detailArchive.overdue" size="small" class="fuy-tag-aa fuy-archive-overdue"
            >已到期</el-tag
          >
        </p>
        <!-- 抽屉体 min-height 320px 锁 CLS（蓝图 丁-3.6 骨架档） -->
        <div v-loading="detailLoading" class="cold-drawer-body">
          <!-- 第一段：巡检/处置记录（.fuy-dense 挂本段祖先层——全局密排段为后代选择器
               .fuy-dense .el-table，只挂 el-table 自身不命中，祖先层承载才生效） -->
          <section class="cold-seg cold-seg-records fuy-dense">
            <h3 class="fuy-section-title">巡检/处置记录（共 {{ recordRows.length }} 条）</h3>
            <el-table :data="recordRows" class="fuy-dense">
              <el-table-column label="记录号" min-width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.recordNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="88">
                <template #default="{ row }">{{ recordTypeLabel(row.recordType) }}</template>
              </el-table-column>
              <el-table-column label="告警号" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.alarmRef ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="复核人" width="76">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.secondOperator ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="内容" min-width="150">
                <template #default="{ row }">
                  <span :title="row.content">{{ row.content ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="登记人" width="76">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.recordedBy ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="96">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.recordedAt) }}</span>
                </template>
              </el-table-column>
              <!-- 空态脸（契约 ⑥）：暂无语法 + 下一步指引 -->
              <template #empty>
                <div class="fuy-empty" role="status">
                  <span class="fuy-empty-mark" aria-hidden="true">空</span>
                  <p class="fuy-empty-title">暂无记录</p>
                  <p class="fuy-empty-hint">该档案尚无巡检/处置/偏差记录，可在下方登记首条。</p>
                </div>
              </template>
            </el-table>
          </section>

          <div class="fuy-sign-divider cold-drawer-divider" aria-hidden="true" />

          <!-- 第二段：登记记录（巡检/告警处置双人复核/偏差；ALARM_HANDLE 时 alarmRef+
               secondOperator 双字段显隐既有） -->
          <section class="cold-seg cold-seg-register">
            <h3 class="fuy-section-title">登记记录</h3>
            <div class="fuy-form">
              <div class="cold-field-grid">
                <div class="cold-field">
                  <label class="cold-field-label">登记类型</label>
                  <select v-model="recordForm.recordType" class="cold-input" aria-label="登记类型">
                    <option
                      v-for="(label, code) in COLD_RECORD_TYPE_LABELS"
                      :key="code"
                      :value="code"
                    >
                      {{ label }}
                    </option>
                  </select>
                </div>
                <div class="cold-field">
                  <template v-if="recordForm.recordType === 'ALARM_HANDLE'">
                    <label class="cold-field-label">告警号（告警处置必填）</label>
                    <input
                      v-model="recordForm.alarmRef"
                      class="cold-input fuy-num"
                      placeholder="如：AL20260926009"
                      aria-label="告警号"
                    />
                  </template>
                  <template v-else-if="recordForm.recordType === 'INSPECTION'">
                    <label class="cold-field-label">双人复核人（巡检可空）</label>
                    <input
                      v-model="recordForm.secondOperator"
                      class="cold-input fuy-num"
                      placeholder="复核人工号"
                      aria-label="复核人"
                    />
                  </template>
                  <template v-else>
                    <label class="cold-field-label">偏差说明锚点（可空）</label>
                    <input
                      v-model="recordForm.alarmRef"
                      class="cold-input fuy-num"
                      placeholder="关联告警/事件号（可空）"
                      aria-label="告警号"
                    />
                  </template>
                </div>
              </div>
              <template v-if="recordForm.recordType === 'ALARM_HANDLE'">
                <label class="cold-field-label">双人复核人（告警处置必填）</label>
                <input
                  v-model="recordForm.secondOperator"
                  class="cold-input fuy-num"
                  placeholder="第二操作人工号"
                  aria-label="复核人"
                />
              </template>
              <label class="cold-field-label">登记内容</label>
              <textarea
                v-model="recordForm.content"
                class="cold-input"
                rows="2"
                placeholder="如：温度正常，门封完好"
                aria-label="登记内容"
              ></textarea>
              <!-- 提交登记（PR-4F #40「登记记录」出网把守位，IOT_ADMIN 绑定）v-perm 直挂——
                   无码 DOM 移除（D-34），巡检/告警处置/偏差记录 POST 以本按钮为唯一出口：
                   「详情」入口与「查询曲线」为读面不挂码；:loading/:disabled 在途数据态与
                   权限判定两层正交 -->
              <el-button
                v-perm="'ward:coldchain:btn:manage'"
                type="primary"
                size="small"
                :loading="registering"
                :disabled="registering"
                @click="onRegister"
                >提交登记</el-button
              >
            </div>
          </section>

          <div class="fuy-sign-divider cold-drawer-divider" aria-hidden="true" />

          <!-- 第三段：温度曲线（iot series + SVG 轻量渲染，不引 echarts；发丝网格与温度刻度
               承体温单 chart-grid 语法，单点/空点退化态保留） -->
          <section class="cold-seg cold-seg-curve">
            <h3 class="fuy-section-title">温度曲线（最近 24 小时）</h3>
            <div class="cold-curve-bar">
              <input
                v-model="curveMetricCode"
                class="cold-input fuy-num cold-curve-metric"
                placeholder="指标编码"
                aria-label="曲线指标编码"
              />
              <el-button size="small" :loading="curveLoading" @click="onQueryCurve"
                >查询曲线</el-button
              >
            </div>
            <!-- 曲线画布：左轴槽 48px 承温度刻度，绘图区 520×160 几何既有（g translate 平移，
                 折线坐标零漂移）；单点退化为短横刻记、无网格基准 -->
            <div v-if="polylinePoints !== ''" class="cold-curve-box">
              <svg
                :viewBox="`0 0 ${CURVE_AXIS_W + CURVE_W} ${CURVE_H}`"
                role="img"
                aria-label="冷链温度曲线"
              >
                <!-- 发丝网格与温度刻度（有范围基准才渲染） -->
                <g v-if="curveTicks.length > 0">
                  <line
                    v-for="tick in curveTicks"
                    :key="`grid-${tick.fraction}`"
                    class="fuy-chart-grid"
                    :x1="CURVE_AXIS_W + CURVE_PAD"
                    :y1="tick.y"
                    :x2="CURVE_AXIS_W + CURVE_W - CURVE_PAD"
                    :y2="tick.y"
                  />
                  <text
                    v-for="tick in curveTicks"
                    :key="`tick-${tick.fraction}`"
                    class="fuy-chart-tick"
                    :x="CURVE_AXIS_W - 6"
                    :y="tick.y + 4"
                    text-anchor="end"
                  >
                    {{ tick.label }}
                  </text>
                </g>
                <g :transform="`translate(${CURVE_AXIS_W}, 0)`">
                  <polyline
                    :points="polylinePoints"
                    fill="none"
                    class="cold-curve-line"
                    stroke-width="1.5"
                  />
                </g>
              </svg>
            </div>
            <!-- 曲线空态脸（契约 ⑥）：暂无语法 + 下一步指引 -->
            <div v-else class="fuy-empty cold-curve-empty" role="status">
              <span class="fuy-empty-mark" aria-hidden="true">空</span>
              <p class="fuy-empty-title">暂无曲线数据</p>
              <p class="fuy-empty-hint">输入指标编码后点击查询曲线，查看该设备最近 24 小时温度。</p>
            </div>
          </section>
        </div>
      </el-drawer>

      <!-- 钤印确认浮层（契约 ⑧.5：只承成功确认；v-if 挂载制 + seq :key 重挂重放落章——
           设备管理页同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤，挂载即
           is-show 根治；≤1200ms 收场归 showStamp 定时器） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 暖纸重排（丁-3）：门牌页首/页首状态位/卷宗列表卡/640 详情抽屉三段卷宗阅读面/钤印；
   取色全走 --fuy-* 语义别名（零新 hex），动效只消费 motion.css 全局 keyframes */

/* 页内级联容器（契约 ⑦.4）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与
   区块间距由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 */
.cold-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：用途筛选 + 新建档案 + 刷新同行（设备管理页同位语法） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 页首筛选组：label 疏排由全局 .fuy-filter 承载 */
.cold-head-filter {
  gap: var(--fuy-space-2);
}

/* 用途筛选 native select（jsdom 可测口径存量件）：32px 与默认钮同高；围合取值直取
   --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边） */
.cold-filter-select {
  height: 32px;
  padding: 0 var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 列表卡头计数副注 */
.cold-list-total {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 列表体 min-height 240px 锁 CLS（蓝图 丁-3.6 骨架档） */
.cold-list-body {
  min-height: 240px;
}

/* 表单基元：native input/select/textarea 与 EP 密度口径对齐（存量页面同款）；围合取值
   直取 --fuy-* 语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边，与卡面拉开一档） */
.cold-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: var(--fuy-space-3);
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
  font-family: inherit;
}

.cold-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--fuy-space-3);
}

.cold-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 抽屉批注槽（谁·何时）：档案号·用途·温区·设备·到期态（甲-2 exec-drawer-note 同构） */
.cold-drawer-note {
  margin: 0 0 var(--fuy-space-3);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-1) var(--fuy-space-2);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

/* 抽屉体 min-height 320px 锁 CLS（蓝图 丁-3.6 骨架档） */
.cold-drawer-body {
  min-height: 320px;
}

/* 抽屉三段（卷宗阅读面纵向序）；段标统一挂全局类 .fuy-section-title（D-30 段标口径
   统一：契约 §④.1 段标属黑体默认继承面，14px/600 浓墨档由 element-plus.css 全局承载，
   局部衬线段标声明已删） */
.cold-seg {
  display: flex;
  flex-direction: column;
}

/* 「签」字分隔上下呼吸位（卡内文书分界） */
.cold-drawer-divider {
  margin: var(--fuy-space-4) 0;
}

/* 用途徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-archive-tag--vaccine {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-archive-tag--blood {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

.fuy-archive-tag--reagent {
  border-color: var(--fuy-color-brand);
  color: var(--fuy-color-brand);
}

.fuy-archive-tag--pharma {
  border-color: var(--fuy-color-triage-l4);
  color: var(--fuy-color-triage-l4);
}

/* 到期标记（印泥朱：危急/停用语义通道，双通道铁律=必配「已到期」文字） */
.fuy-archive-overdue {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

/* 温度曲线（轻量 SVG：折线取墨色=操作域归墨；网格与刻度承体温单 chart-grid 语法——
   类只控色与线宽，几何在节点属性） */
.cold-curve-bar {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-2);
}

.cold-curve-metric {
  width: 220px;
  margin-bottom: 0;
}

.cold-curve-box {
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  padding: var(--fuy-space-2);
  background: var(--fuy-surface-input);
}

.cold-curve-line {
  stroke: var(--fuy-color-brand);
}

/* 体温单 chart-grid 语法（丁-3.3 蓝图指定）：发丝网格 0.5px + 刻度文字弱墨 */
.fuy-chart-grid {
  stroke: var(--fuy-chart-grid-color);
  stroke-width: 0.5;
}

.fuy-chart-tick {
  fill: var(--fuy-chart-text-color);
  font-size: var(--fuy-font-size-xs);
}

/* 曲线空态脸收敛内边距（抽屉内小容器场景） */
.cold-curve-empty {
  padding: var(--fuy-space-4) 16px;
}
</style>
