<script setup lang="ts">
// 冷链台账页（/ward/cold-chain，M16 冷链治理面前端面）：冷链档案列表（用途/温区词表徽标、
// 校验到期 overdue 标记——到期判定由后端承载）+ 档案详情（档案字段回显 + 巡检/告警处置/
// 偏差记录列表）+ 登记表单（记录类型词表下拉；告警处置 ALARM_HANDLE 强制双人复核字段
// alarmRef+secondOperator，缺一零出网；登记人取会话归后端承载）+ 温度曲线（复用 iot series
// API 以 scope=device 查询聚合点列，SVG polyline 轻量渲染——不引 echarts，echarts 归
// bigscreen）。全部写操作自带在途守卫 + 显式校验零出网。
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
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

/** 时点展示串（MM-dd HH:mm，时间列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/* ==================== 档案列表 ==================== */
const rows = ref<ColdChainArchiveVO[]>([]);
const listLoading = ref(false);
/** 用途筛选（空串=全部四用途） */
const purposeFilter = ref('');

/** 加载档案列表（purpose 过滤由后端承载，overdue 到期标记由后端承载） */
async function loadList(): Promise<void> {
  listLoading.value = true;
  try {
    const page = await coldChain.page({
      purpose: purposeFilter.value === '' ? undefined : purposeFilter.value,
      page: 0,
      size: 50,
    });
    rows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    listLoading.value = false;
  }
}

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
 * 出网 → 关窗刷新。
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
    void ElMessage.success('冷链档案已登记');
    createVisible.value = false;
    await loadList();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    creating.value = false;
  }
}

/* ==================== 档案详情（记录 + 登记 + 温度曲线） ==================== */
const detailVisible = ref(false);
const detailLoading = ref(false);
/** 详情目标档案（弹窗上下文锚点；详情回显与登记/曲线共用） */
const detailArchive = ref<ColdChainArchiveVO | null>(null);
const recordRows = ref<ColdChainRecordVO[]>([]);

/** 打开详情弹窗：出网 detail 回显 + records 记录全集（失败弹错归拦截器，关窗兜底） */
async function openDetail(row: ColdChainArchiveVO): Promise<void> {
  detailArchive.value = row;
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    if (row.archiveNo !== undefined) {
      const [archive, records] = await Promise.all([
        coldChain.detail(row.archiveNo),
        coldChain.records(row.archiveNo),
      ]);
      // detail 出参为权威回显源（行数据仅作入口锚点）
      detailArchive.value = archive;
      recordRows.value = records;
    }
  } catch (error) {
    surfaceBizError(error);
  } finally {
    detailLoading.value = false;
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
 * 清表单刷新记录。
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
    void ElMessage.success('冷链记录已登记');
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

/* ==================== 温度曲线（iot series + SVG 轻量渲染） ==================== */

/** 曲线查询指标编码（冷链温度指标编码未入 MDC 词表种子——联调期占位，对齐物模型后修订） */
const curveMetricCode = ref('COLDCHAIN_TEMP');
const curvePoints = ref<TelemetryPoint[]>([]);
const curveLoading = ref(false);
/** 曲线时间窗（毫秒）：最近 24 小时 */
const CURVE_WINDOW_MS = 24 * 60 * 60 * 1000;
/** SVG 画布几何（viewBox 坐标系；内边距防折线贴边） */
const CURVE_W = 520;
const CURVE_H = 160;
const CURVE_PAD = 12;

/** 查询温度曲线：scope=device 按档案设备查询最近 24h 聚合点列 */
async function onQueryCurve(): Promise<void> {
  if (curveLoading.value) {
    return;
  }
  const deviceId = detailArchive.value?.deviceId;
  if (deviceId === undefined || deviceId === '') {
    void ElMessage.warning('档案未关联设备，无法查询温度曲线');
    return;
  }
  curveLoading.value = true;
  try {
    curvePoints.value = await telemetry.series({
      scope: 'device',
      deviceId,
      metricCode: curveMetricCode.value.trim(),
      from: new Date(Date.now() - CURVE_WINDOW_MS).toISOString(),
      to: new Date().toISOString(),
    });
  } catch (error) {
    surfaceBizError(error);
  } finally {
    curveLoading.value = false;
  }
}

/** 折线点坐标（avg 优先、last 兜底；单点/空点渲染退化态） */
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
  return values
    .map((value, index) => {
      const x =
        CURVE_PAD + (values.length === 1 ? usableW / 2 : (index / (values.length - 1)) * usableW);
      // Y 轴反转：温度越高越靠上
      const y = CURVE_PAD + usableH - ((value - min) / span) * usableH;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
});

onMounted(() => {
  void loadList();
});
</script>

<template>
  <div class="fuy-page cold-chain fuy-stagger">
    <!-- 页头：标题 + 提示 + 刷新 -->
    <header class="cold-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="cold-title">冷链台账</h2>
      <span class="cold-hint">
        疫苗/血液/试剂/药品档案 · 告警处置双人复核 · 温度曲线轻量渲染 · 校验到期归后端承载
      </span>
      <el-button :loading="listLoading" @click="loadList">刷新</el-button>
    </header>

    <el-card class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <template #header>
        <div class="cold-card-head">
          <span>冷链档案（共 {{ rows.length }} 条）</span>
          <div class="cold-filters">
            <select
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
            <el-button type="primary" size="small" @click="openCreate">新建档案</el-button>
          </div>
        </div>
      </template>
      <div v-loading="listLoading">
        <el-table v-if="rows.length > 0" :data="rows" class="fuy-dense" size="small">
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
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="72" description="暂无冷链档案" />
      </div>
    </el-card>

    <!-- 新建档案弹窗 -->
    <el-dialog v-model="createVisible" title="新建冷链档案" width="480px">
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
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button
          type="primary"
          size="small"
          :loading="creating"
          :disabled="creating"
          @click="onCreate"
          >保存档案</el-button
        >
      </template>
    </el-dialog>

    <!-- 档案详情弹窗：记录列表 + 登记表单 + 温度曲线 -->
    <el-dialog v-model="detailVisible" title="冷链档案详情" width="720px">
      <div v-loading="detailLoading">
        <div v-if="detailArchive !== null" class="cold-detail-meta fuy-num">
          {{ detailArchive.archiveNo }} · {{ purposeLabel(detailArchive.purpose) }} ·
          {{ tempRangeLabel(detailArchive.tempRangeType) }} · 设备 {{ detailArchive.deviceId }}
          <el-tag v-if="detailArchive.overdue" size="small" class="fuy-tag-aa fuy-archive-overdue"
            >已到期</el-tag
          >
        </div>

        <!-- 记录列表 -->
        <h3 class="cold-section-title">巡检/处置记录（共 {{ recordRows.length }} 条）</h3>
        <el-table v-if="recordRows.length > 0" :data="recordRows" class="fuy-dense" size="small">
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
        </el-table>
        <el-empty v-else :image-size="56" description="暂无记录" />

        <!-- 登记表单（巡检/告警处置双人复核/偏差） -->
        <h3 class="cold-section-title">登记记录</h3>
        <div class="cold-field-grid">
          <div class="cold-field">
            <label class="cold-field-label">登记类型</label>
            <select v-model="recordForm.recordType" class="cold-input" aria-label="登记类型">
              <option v-for="(label, code) in COLD_RECORD_TYPE_LABELS" :key="code" :value="code">
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
        <el-button
          type="primary"
          size="small"
          :loading="registering"
          :disabled="registering"
          @click="onRegister"
          >提交登记</el-button
        >

        <!-- 温度曲线（iot series + SVG 轻量渲染，不引 echarts） -->
        <h3 class="cold-section-title">温度曲线（最近 24 小时）</h3>
        <div class="cold-curve-bar">
          <input
            v-model="curveMetricCode"
            class="cold-input fuy-num cold-curve-metric"
            placeholder="指标编码"
            aria-label="曲线指标编码"
          />
          <el-button size="small" :loading="curveLoading" @click="onQueryCurve">查询曲线</el-button>
        </div>
        <div v-if="polylinePoints !== ''" class="cold-curve-box">
          <svg :viewBox="`0 0 ${CURVE_W} ${CURVE_H}`" role="img" aria-label="冷链温度曲线">
            <polyline
              :points="polylinePoints"
              fill="none"
              class="cold-curve-line"
              stroke-width="1.5"
            />
          </svg>
        </div>
        <el-empty v-else :image-size="56" description="暂无曲线数据（输入指标编码后查询）" />
      </div>
      <template #footer>
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 冷链台账单卡布局，token 取色禁自创色值 */
.cold-toolbar {
  align-items: center;
}

.cold-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.cold-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.cold-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

.cold-filters {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

.cold-filter-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

/* 弹窗表单基元 */
.cold-input {
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

.cold-detail-meta {
  margin-bottom: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

.cold-section-title {
  margin: var(--fuy-space-4) 0 var(--fuy-space-2);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
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

/* 到期标记（警示语义） */
.fuy-archive-overdue {
  border-color: var(--fuy-color-danger-text);
  color: var(--fuy-color-danger-text);
}

/* 温度曲线（轻量 SVG：折线取品牌色，网格淡描边） */
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
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  padding: var(--fuy-space-2);
}

.cold-curve-line {
  stroke: var(--fuy-color-brand);
}
</style>
