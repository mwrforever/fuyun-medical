<script setup lang="ts">
// 质量看板页（/iot/quality，M16 数据质量治理面前端面）：质量统计表（缺测率 missing_rate/
// 异常数/质量分，deviceId 筛选）+ 设备利用率 TopN（按 usageRate 降序排序渲染——TopN 截取
// 形态由前端承载）+ 消费积压水位（消费组积压估计/最老消息年龄/消费与到达速率）+ 消费错误
// 列表（环节/状态徽标；默认待处置；重放一键重投、放弃原因强制弹窗——死信治理面）。
// 全部写操作自带在途守卫；显式校验零出网（放弃原因空串拦截）。
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import axios from 'axios';
import {
  CONSUME_ERROR_STAGE_LABELS,
  CONSUME_ERROR_STATUS_LABELS,
  consumeErrors,
  monitor,
  quality,
} from '@/api/iot';
import type { ConsumeErrorVO, DataQualityStatVO } from '@/api/iot';

/** 设备利用率 TopN 截取条数（降序排序后取前 10 台展示） */
const USAGE_TOP_N = 10;

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

/** 比率展示串（0.02 → 2.00%，缺测率/利用率列共用；空值占位） */
function rateText(value: number | undefined): string {
  return value === undefined ? '—' : `${(value * 100).toFixed(2)}%`;
}

/** 消费错误环节中文词表反查（环节列） */
function stageLabel(code: string | undefined): string {
  return CONSUME_ERROR_STAGE_LABELS[code ?? ''] ?? code ?? '—';
}

/** 消费错误状态中文词表反查（状态列徽标） */
function errorStatusLabel(code: string | undefined): string {
  return CONSUME_ERROR_STATUS_LABELS[code ?? ''] ?? code ?? '—';
}

/** 消费错误状态徽标状态类（fuy-cerr-tag--{status} 契约类，色值经语义 token 承载） */
function errorStatusClass(code: string | undefined): string {
  return `fuy-cerr-tag--${(code ?? '').toLowerCase()}`;
}

/** 时点展示串（MM-dd HH:mm，采样时间列共用） */
function formatTime(raw: string | undefined): string {
  if (!raw) {
    return '—';
  }
  const date = new Date(raw);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/* ==================== 质量统计表 ==================== */
const statRows = ref<DataQualityStatVO[]>([]);
const statLoading = ref(false);
/** 统计筛选设备 ID（空串=全部设备） */
const deviceIdFilter = ref('');

/** 加载质量统计（deviceId 过滤由后端承载） */
async function loadStats(): Promise<void> {
  statLoading.value = true;
  try {
    const page = await quality.stats({
      deviceId: deviceIdFilter.value.trim() === '' ? undefined : deviceIdFilter.value.trim(),
      page: 0,
      size: 50,
    });
    statRows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    statLoading.value = false;
  }
}

/* ==================== 设备利用率 TopN ==================== */
const usageRows = ref<DataQualityStatVO[]>([]);
const usageLoading = ref(false);

/** 加载设备利用率（取 size 50 后按 usageRate 降序排序截取 TopN——TopN 形态由前端承载） */
async function loadUsage(): Promise<void> {
  usageLoading.value = true;
  try {
    const page = await quality.deviceUsage({ page: 0, size: 50 });
    usageRows.value = [...(page.content ?? [])]
      .sort((a, b) => (b.usageRate ?? 0) - (a.usageRate ?? 0))
      .slice(0, USAGE_TOP_N);
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    usageLoading.value = false;
  }
}

/* ==================== 消费积压水位 ==================== */
const lagRows = ref<Awaited<ReturnType<typeof monitor.consumerLag>>>([]);
const lagLoading = ref(false);

/** 加载消费组积压快照（量小全量直出） */
async function loadLag(): Promise<void> {
  lagLoading.value = true;
  try {
    lagRows.value = await monitor.consumerLag();
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    lagLoading.value = false;
  }
}

/* ==================== 消费错误列表（重放/放弃） ==================== */
const errorRows = ref<ConsumeErrorVO[]>([]);
const errorLoading = ref(false);
/** 状态筛选（空串=全部三态；默认 PENDING 待处置——死信治理主战场） */
const errorStatusFilter = ref('PENDING');

/** 加载消费错误列表（queueName 过滤由后端承载） */
async function loadErrors(): Promise<void> {
  errorLoading.value = true;
  try {
    const page = await consumeErrors.page({
      status:
        errorStatusFilter.value === ''
          ? undefined
          : (errorStatusFilter.value as ConsumeErrorVO['status']),
      page: 0,
      size: 50,
    });
    errorRows.value = page.content ?? [];
  } catch {
    // 失败弹错归响应拦截器；驻留旧清单
  } finally {
    errorLoading.value = false;
  }
}

/** 重放消费错误（原消息重投消费链路；replayCount 累加由后端承载） */
async function onReplay(row: ConsumeErrorVO): Promise<void> {
  if (row.errorId === undefined) {
    return;
  }
  try {
    await consumeErrors.replay(row.errorId);
    void ElMessage.success(`错误已重放：${row.errorId}`);
    await loadErrors();
  } catch (error) {
    surfaceBizError(error);
  }
}

/* ==================== 放弃弹窗（原因强制） ==================== */
const abandonVisible = ref(false);
const abandoning = ref(false);
/** 放弃目标错误（弹窗上下文锚点） */
const abandonTarget = ref<ConsumeErrorVO | null>(null);
const abandonReason = ref('');

/** 打开放弃弹窗（仅 PENDING 态行暴露入口） */
function openAbandon(row: ConsumeErrorVO): void {
  abandonTarget.value = row;
  abandonReason.value = '';
  abandonVisible.value = true;
}

/** 确认放弃：原因强制显式校验（空原因零出网）→ 出网 → 关窗刷新。 */
async function onAbandon(): Promise<void> {
  if (abandoning.value) {
    return;
  }
  if (abandonReason.value.trim() === '') {
    void ElMessage.warning('请填写放弃原因');
    return;
  }
  abandoning.value = true;
  try {
    await consumeErrors.abandon(abandonTarget.value?.errorId ?? '', {
      reason: abandonReason.value.trim(),
    });
    void ElMessage.success(`错误已放弃：${abandonTarget.value?.errorId ?? ''}`);
    abandonVisible.value = false;
    await loadErrors();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    abandoning.value = false;
  }
}

onMounted(() => {
  void loadStats();
  void loadUsage();
  void loadLag();
  void loadErrors();
});
</script>

<template>
  <div class="fuy-page quality-board fuy-stagger">
    <!-- 页头：标题 + 提示 + 全量刷新 -->
    <header class="quality-toolbar fuy-toolbar" :style="{ '--fuy-stagger-index': 0 }">
      <h2 class="quality-title">质量看板</h2>
      <span class="quality-hint">
        缺测率/异常数/质量分 · 利用率 TopN · 消费积压水位 · 死信治理（重放/放弃）
      </span>
      <el-button
        :loading="statLoading || usageLoading || lagLoading || errorLoading"
        @click="
          () => {
            void loadStats();
            void loadUsage();
            void loadLag();
            void loadErrors();
          }
        "
        >刷新</el-button
      >
    </header>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 1 }">
      <!-- 左上：质量统计表 -->
      <el-col :md="24" :lg="14">
        <el-card>
          <template #header>
            <div class="quality-card-head">
              <span>数据质量统计（共 {{ statRows.length }} 条）</span>
              <div class="quality-filter">
                <input
                  v-model="deviceIdFilter"
                  class="quality-input fuy-num"
                  placeholder="设备 ID（可空）"
                  aria-label="设备 ID 筛选"
                  @keyup.enter="loadStats"
                />
                <el-button size="small" @click="loadStats">查询</el-button>
              </div>
            </div>
          </template>
          <div v-loading="statLoading">
            <el-table v-if="statRows.length > 0" :data="statRows" class="fuy-dense" size="small">
              <el-table-column label="设备 ID" min-width="100">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="统计日" width="92">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.statDate ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="应收" width="72">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.expectedCount ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="实收" width="72">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.receivedCount ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="缺测率" width="86">
                <template #default="{ row }">
                  <span class="fuy-num">{{ rateText(row.missingRate) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="异常数" width="72">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.anomalyCount ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="质量分" width="76">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.qualityScore ?? '—' }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无质量统计数据" />
          </div>
        </el-card>
      </el-col>

      <!-- 右上：设备利用率 TopN -->
      <el-col :md="24" :lg="10">
        <el-card>
          <template #header>设备利用率 Top{{ USAGE_TOP_N }}（按利用率降序）</template>
          <div v-loading="usageLoading">
            <el-table
              v-if="usageRows.length > 0"
              :data="usageRows"
              class="fuy-dense"
              size="small"
              data-test="usage-table"
            >
              <el-table-column type="index" label="#" width="48" />
              <el-table-column label="设备 ID" min-width="110">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="利用率" width="96">
                <template #default="{ row }">
                  <span class="fuy-num">{{ rateText(row.usageRate) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="统计日" width="92">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.statDate ?? '—' }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无利用率数据" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="fuy-stagger" :style="{ '--fuy-stagger-index': 2 }">
      <!-- 左下：消费积压水位 -->
      <el-col :md="24" :lg="14">
        <el-card>
          <template #header>消费积压水位（共 {{ lagRows.length }} 组）</template>
          <div v-loading="lagLoading">
            <el-table v-if="lagRows.length > 0" :data="lagRows" class="fuy-dense" size="small">
              <el-table-column label="消费组" min-width="170">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.consumerGroup }}</span>
                </template>
              </el-table-column>
              <el-table-column label="积压估计" width="90">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.backlogEstimate ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="最老消息年龄(s)" width="120">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.oldestMsgAgeSecs ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="消费速率" width="90">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.consumeRate ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="到达速率" width="90">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.arriveRate ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="采样时间" width="96">
                <template #default="{ row }">
                  <span class="fuy-num">{{ formatTime(row.sampledAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无消费积压数据" />
          </div>
        </el-card>
      </el-col>

      <!-- 右下：消费错误列表（重放/放弃死信治理） -->
      <el-col :md="24" :lg="10">
        <el-card>
          <template #header>
            <div class="quality-card-head">
              <span>消费错误（共 {{ errorRows.length }} 条）</span>
              <select
                v-model="errorStatusFilter"
                class="quality-status-select"
                aria-label="错误状态筛选"
                @change="loadErrors"
              >
                <option value="">全部状态</option>
                <option
                  v-for="(label, code) in CONSUME_ERROR_STATUS_LABELS"
                  :key="code"
                  :value="code"
                >
                  {{ label }}
                </option>
              </select>
            </div>
          </template>
          <div v-loading="errorLoading">
            <el-table v-if="errorRows.length > 0" :data="errorRows" class="fuy-dense" size="small">
              <el-table-column label="错误 ID" width="76">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.errorId }}</span>
                </template>
              </el-table-column>
              <el-table-column label="队列" min-width="130">
                <template #default="{ row }">
                  <span class="fuy-num" :title="row.queueName">{{ row.queueName ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="环节" width="64">
                <template #default="{ row }">{{ stageLabel(row.errorStage) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="76">
                <template #default="{ row }">
                  <el-tag size="small" class="fuy-tag-aa" :class="errorStatusClass(row.status)">
                    {{ errorStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="重放" width="56">
                <template #default="{ row }">
                  <span class="fuy-num">{{ row.replayCount ?? 0 }}</span>
                </template>
              </el-table-column>
              <el-table-column label="错误信息" min-width="110">
                <template #default="{ row }">
                  <span :title="row.errorMsg">{{ row.errorMsg ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="96" class-name="fuy-ops-8">
                <template #default="{ row }">
                  <el-button
                    v-if="row.status === 'PENDING'"
                    link
                    type="primary"
                    size="small"
                    @click="onReplay(row)"
                    >重放</el-button
                  >
                  <el-button
                    v-if="row.status === 'PENDING'"
                    link
                    type="danger"
                    size="small"
                    @click="openAbandon(row)"
                    >放弃</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else :image-size="72" description="暂无消费错误" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 放弃原因强制弹窗（死信治理：放弃后不再重投，原因留痕由后端承载） -->
    <el-dialog v-model="abandonVisible" title="放弃消费错误" width="420px">
      <p class="quality-abandon-target fuy-num">
        {{ abandonTarget?.errorId ?? '' }} · {{ abandonTarget?.queueName ?? '' }}
      </p>
      <label class="quality-field-label">放弃原因（强制：确认脏数据/无效消息不再重投）</label>
      <textarea
        v-model="abandonReason"
        class="quality-input"
        rows="2"
        placeholder="放弃原因强制留痕"
        aria-label="放弃原因"
      ></textarea>
      <template #footer>
        <el-button size="small" @click="abandonVisible = false">取消</el-button>
        <el-button
          type="danger"
          size="small"
          :loading="abandoning"
          :disabled="abandoning"
          @click="onAbandon"
          >确认放弃</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 质量看板 2×2 四象限布局，token 取色禁自创色值 */
.quality-toolbar {
  align-items: center;
}

.quality-title {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

.quality-hint {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.quality-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fuy-space-3);
}

.quality-filter {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 筛选/弹窗基元（native input/select/textarea 与 EP 密度口径对齐） */
.quality-input {
  width: 160px;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

.quality-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--el-bg-color);
}

.quality-field-label {
  display: block;
  margin-bottom: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.quality-abandon-target {
  margin: 0 0 var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-palette-gray-50);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
}

/* 消费错误状态三态徽标（描边文本场景，色值全为语义 token 别名） */
.fuy-cerr-tag--pending {
  border-color: var(--fuy-color-warning-text);
  color: var(--fuy-color-warning-text);
}

.fuy-cerr-tag--replayed {
  border-color: var(--fuy-color-success-text);
  color: var(--fuy-color-success-text);
}

.fuy-cerr-tag--abandoned {
  border-color: var(--fuy-color-info-text);
  color: var(--fuy-color-info-text);
}
</style>
