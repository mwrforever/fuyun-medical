<script setup lang="ts">
// 质量看板页（/iot/quality，M16 数据质量治理面前端面 · 暖纸卷宗换脸重排 丙-7，「换脸不换
// 业务」：质量统计/利用率 TopN/积压水位/死信四区数据契约、重放/放弃状态机、原因强制显式
// 校验、×100 两位小数格式化、TopN 降序截取、四区并行出网编排零变动）
// 纵向序（蓝图 丙-7.2）：门牌页首（衬线标题 + 「待处置死信 N」琥珀胶囊——前端自 errorRows
// 过滤 PENDING 派生 + 全量刷新 + 原 hint 升批注行「谁·时刻」）→ 2×2 四区卷宗卡（质量统计+
// 利用率 TopN / 积压水位+消费错误，lg 14/10 两行既有栅格保留；消费错误卡头加 PENDING 计数
// 徽标、积压水位行 backlogEstimate>0 时琥珀文字+「积压」字样双通道——阈值口径=零值判断）
// → 页脚批注。重放成功=钤印「已重放」、放弃（原因强制）成功=钤印「已放弃」（契约 ⑧.5，
// ≤1200ms 收场；失败与业务拒绝归 message 批注条/拦截器，钤印永不承失败）；四空态脸化
// （.fuy-empty 暂无语法）。全部写操作自带在途守卫；显式校验零出网（放弃原因空串拦截）。
import { computed, onMounted, onScopeDispose, ref } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import {
  CONSUME_ERROR_STAGE_LABELS,
  CONSUME_ERROR_STATUS_LABELS,
  consumeErrors,
  monitor,
  quality,
} from '@/api/iot';
import type { ConsumeErrorVO, DataQualityStatVO } from '@/api/iot';
import { useAsyncTask } from '@/composables/useAsyncTask';
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

/** 设备利用率 TopN 截取条数（降序排序后取前 10 台展示） */
const USAGE_TOP_N = 10;

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

/* ==================== 质量统计表 ==================== */
const statRows = ref<DataQualityStatVO[]>([]);
/** 统计筛选设备 ID（空串=全部设备） */
const deviceIdFilter = ref('');

/** 加载质量统计（deviceId 过滤由后端承载）：loading 骨架经 useAsyncTask 收拢（EX-42 范式
 * 迁移，行为与迁移前一致——失败弹错归响应拦截器；驻留旧清单） */
const { loading: statLoading, run: loadStats } = useAsyncTask(async () => {
  const page = await quality.stats({
    deviceId: deviceIdFilter.value.trim() === '' ? undefined : deviceIdFilter.value.trim(),
    page: 0,
    size: 50,
  });
  statRows.value = page.content ?? [];
});

/* ==================== 设备利用率 TopN ==================== */
const usageRows = ref<DataQualityStatVO[]>([]);

/** 加载设备利用率（取 size 50 后按 usageRate 降序排序截取 TopN——TopN 形态由前端承载）：
 * loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移） */
const { loading: usageLoading, run: loadUsage } = useAsyncTask(async () => {
  const page = await quality.deviceUsage({ page: 0, size: 50 });
  usageRows.value = [...(page.content ?? [])]
    .sort((a, b) => (b.usageRate ?? 0) - (a.usageRate ?? 0))
    .slice(0, USAGE_TOP_N);
});

/* ==================== 消费积压水位 ==================== */
const lagRows = ref<Awaited<ReturnType<typeof monitor.consumerLag>>>([]);

/** 加载消费组积压快照（量小全量直出）：loading 骨架经 useAsyncTask 收拢（EX-42 范式迁移） */
const { loading: lagLoading, run: loadLag } = useAsyncTask(async () => {
  lagRows.value = await monitor.consumerLag();
});

/* ==================== 消费错误列表（重放/放弃） ==================== */
const errorRows = ref<ConsumeErrorVO[]>([]);
/** 状态筛选（空串=全部三态；默认 PENDING 待处置——死信治理主战场） */
const errorStatusFilter = ref('PENDING');

/** 错误首拉落地标记（门牌「待处置死信 N」与卡头「待处置 N」胶囊的骨架/内容切换判据：
 * 落地前以静态骨架占位禁伪 0 值——丙-4 活跃告警/丙-6 FAILED 计数先例同构；落地后恒显
 * 诚实计数含 0，零值不隐藏） */
const errorsLoaded = ref(false);

/** 加载消费错误列表（queueName 过滤由后端承载）：loading 骨架经 useAsyncTask 收拢
 * （EX-42 范式迁移） */
const { loading: errorLoading, run: loadErrors } = useAsyncTask(async () => {
  const page = await consumeErrors.page({
    status:
      errorStatusFilter.value === ''
        ? undefined
        : (errorStatusFilter.value as ConsumeErrorVO['status']),
    page: 0,
    size: 50,
  });
  errorRows.value = page.content ?? [];
  errorsLoaded.value = true;
});

/** 待处置死信计数（蓝图 丙-7.2：门牌琥珀胶囊与消费错误卡头徽标同源派生——前端自
 * errorRows 过滤 PENDING，零值胶囊仍展示「待处置死信 0」不隐藏） */
const pendingCount = computed(
  () => errorRows.value.filter((row) => row.status === 'PENDING').length,
);

/** 全量刷新（四区并行重取，onMounted 同一编排） */
function refreshAll(): void {
  void loadStats();
  void loadUsage();
  void loadLag();
  void loadErrors();
}

/** 刷新钮在途态（四区任一在途即亮，防重复击发） */
const anyLoading = computed(
  () => statLoading.value || usageLoading.value || lagLoading.value || errorLoading.value,
);

/** 重放在途行锚点（null=无在途；行级守卫防双击窗口内重复重投消费消息） */
const replayingId = ref<string | null>(null);

/**
 * 重放消费错误（原消息重投消费链路；replayCount 累加由后端承载）：行级在途守卫（进入即判/
 * finally 复位）防双击窗口内重复重投；成功反馈=钤印「已重放」（契约 ⑧.5）。
 *
 * @param row 重放目标错误行
 */
async function onReplay(row: ConsumeErrorVO): Promise<void> {
  if (row.errorId === undefined || replayingId.value !== null) {
    return;
  }
  replayingId.value = row.errorId;
  try {
    await consumeErrors.replay(row.errorId);
    showStamp('已重放');
    await loadErrors();
  } catch (error) {
    surfaceBizError(error);
  } finally {
    replayingId.value = null;
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

// 组件卸载时清理在途收场定时器（丙-2/丙-3 钤印段同构口径）
onScopeDispose(() => {
  if (stampTimer !== null) {
    clearTimeout(stampTimer);
    stampTimer = null;
  }
});

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

/** 确认放弃：原因强制显式校验（空原因零出网）→ 出网 → 钤印「已放弃」关窗刷新。 */
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
    showStamp('已放弃');
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
  <div class="fuy-page quality-board">
    <!-- 页内级联容器（契约 ⑦.4：页面根节点不挂 .fuy-stagger，stagger 挂页内区块——丙-2
         device-flow 同构）：本层承接根级 flex+gap 纵排，四段级联序 header 0 / 两行四区 1·2 /
         页脚 3 -->
    <div class="fuy-stagger quality-flow">
      <!-- 门牌页首（契约 ⑧.1）：衬线标题 + 待处置死信琥珀胶囊（蓝图 丙-7.2：前端自 errorRows
           过滤 PENDING 派生，PENDING 量开场面）+ 全量刷新 + 批注行（原 hint 升批注，
           「谁·时刻」齐备）+ 2px 墨规收底 -->
      <header class="fuy-page-head" :style="{ '--fuy-stagger-index': 0 }">
        <div class="fuy-page-head-main">
          <h1 class="fuy-page-title">质量看板</h1>
          <span class="fuy-page-en" aria-hidden="true">DATA QUALITY</span>
        </div>
        <div class="fuy-page-status">
          <!-- 待处置死信计数（蓝图 丙-7.2：前端自 errorRows 过滤 PENDING 派生，PENDING 量
               开场面）：首拉未落地以静态骨架占位（禁伪 0 值——丙-4/丙-6 先例同构），落地后
               恒显诚实计数含 0；双通道铁律：胶囊必带文字（零值不隐藏） -->
          <span v-if="!errorsLoaded" class="fuy-sk quality-pending-sk" aria-hidden="true"></span>
          <span v-else class="fuy-status-pill fuy-status-pill--amber" data-test="page-pending-pill"
            >待处置死信 {{ pendingCount }}</span
          >
          <el-button
            link
            type="primary"
            size="small"
            :loading="anyLoading"
            :disabled="anyLoading"
            @click="refreshAll"
            >刷新</el-button
          >
        </div>
        <p class="fuy-page-note">
          缺测率/异常数/质量分 · 利用率 TopN · 消费积压水位 · 死信治理（重放/放弃）· 签认人
          {{ signerName }} · <time>{{ todayLabel }}</time>
        </p>
      </header>

      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 1 }">
        <!-- 左上：质量统计表（卷宗卡 + .fuy-dense 密排挂卡容器——丙-2 同构后代关系） -->
        <el-col :md="24" :lg="14">
          <section class="fuy-card fuy-dense">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">数据质量统计</h2>
              <div class="quality-card-tools">
                <span class="quality-card-total">共 {{ statRows.length }} 条</span>
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
            </header>
            <div class="fuy-card-body quality-zone-body" v-loading="statLoading">
              <el-table :data="statRows">
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
                <!-- 空态脸（契约 ⑥）：暂无语法 + 下一步指引，零默认纸箱插画 -->
                <template #empty>
                  <div class="fuy-empty" role="status">
                    <span class="fuy-empty-mark" aria-hidden="true">空</span>
                    <p class="fuy-empty-title">暂无质量统计数据</p>
                    <p class="fuy-empty-hint">
                      当前无质量统计快照，可调整设备 ID 筛选或点击刷新重取。
                    </p>
                  </div>
                </template>
              </el-table>
            </div>
          </section>
        </el-col>

        <!-- 右上：设备利用率 TopN -->
        <el-col :md="24" :lg="10">
          <section class="fuy-card fuy-dense">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">设备利用率 Top{{ USAGE_TOP_N }}</h2>
              <span class="quality-card-total">按利用率降序</span>
            </header>
            <div class="fuy-card-body quality-zone-body" v-loading="usageLoading">
              <el-table :data="usageRows" data-test="usage-table">
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
                <template #empty>
                  <div class="fuy-empty" role="status">
                    <span class="fuy-empty-mark" aria-hidden="true">空</span>
                    <p class="fuy-empty-title">暂无设备利用率数据</p>
                    <p class="fuy-empty-hint">当前无设备利用率记录，可点击刷新重新拉取。</p>
                  </div>
                </template>
              </el-table>
            </div>
          </section>
        </el-col>
      </el-row>

      <el-row :gutter="16" :style="{ '--fuy-stagger-index': 2 }">
        <!-- 左下：消费积压水位 -->
        <el-col :md="24" :lg="14">
          <section class="fuy-card fuy-dense">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">消费积压水位</h2>
              <span class="quality-card-total">共 {{ lagRows.length }} 组</span>
            </header>
            <div class="fuy-card-body quality-zone-body" v-loading="lagLoading">
              <el-table :data="lagRows">
                <el-table-column label="消费组" min-width="170">
                  <template #default="{ row }">
                    <span class="fuy-num">{{ row.consumerGroup }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="积压估计" width="110">
                  <template #default="{ row }">
                    <!-- 蓝图 丙-7.2（PM 重排：水位异常双通道，阈值口径=零值判断非发明业务
                         阈值）：backlogEstimate>0 时琥珀文字+「积压」字样（双通道铁律：
                         色不单独承义）；零值行仅数字直出 -->
                    <span v-if="(row.backlogEstimate ?? 0) > 0" class="quality-backlog-hot fuy-num">
                      {{ row.backlogEstimate }} 积压
                    </span>
                    <span v-else class="fuy-num">{{ row.backlogEstimate ?? '—' }}</span>
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
                <template #empty>
                  <div class="fuy-empty" role="status">
                    <span class="fuy-empty-mark" aria-hidden="true">空</span>
                    <p class="fuy-empty-title">暂无消费积压数据</p>
                    <p class="fuy-empty-hint">各消费组暂无积压快照，可点击刷新重取。</p>
                  </div>
                </template>
              </el-table>
            </div>
          </section>
        </el-col>

        <!-- 右下：消费错误列表（重放/放弃死信治理主战场；卡头挂 PENDING 计数徽标） -->
        <el-col :md="24" :lg="10">
          <section class="fuy-card fuy-dense">
            <header class="fuy-card-head">
              <h2 class="fuy-card-title">消费错误</h2>
              <div class="quality-card-tools">
                <!-- 死信治理开场面（蓝图 丙-7.2）：与门牌胶囊同源派生自 errorRows，首拉
                     落地前同守卫静态骨架占位（禁伪 0 值） -->
                <span
                  v-if="!errorsLoaded"
                  class="fuy-sk quality-pending-sk"
                  aria-hidden="true"
                ></span>
                <span
                  v-else
                  class="fuy-status-pill fuy-status-pill--amber"
                  data-test="card-pending-pill"
                  >待处置 {{ pendingCount }}</span
                >
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
            </header>
            <div class="fuy-card-body quality-zone-body" v-loading="errorLoading">
              <el-table :data="errorRows">
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
                    <!-- 重放/放弃（PR-4F #38，IOT_ADMIN 绑定）v-perm 同码直挂——无码 DOM
                         移除（D-34）；PENDING v-if 状态机数据态与权限判定两层正交；放弃
                         弹窗「确认放弃」随入口不可达免挂接；重放在途守卫防重复重投 -->
                    <el-button
                      v-if="row.status === 'PENDING'"
                      v-perm="'iot:quality:btn:replay'"
                      link
                      type="primary"
                      size="small"
                      :loading="replayingId === row.errorId"
                      :disabled="replayingId !== null"
                      @click="onReplay(row)"
                      >重放</el-button
                    >
                    <el-button
                      v-if="row.status === 'PENDING'"
                      v-perm="'iot:quality:btn:replay'"
                      link
                      type="danger"
                      size="small"
                      @click="openAbandon(row)"
                      >放弃</el-button
                    >
                  </template>
                </el-table-column>
                <template #empty>
                  <div class="fuy-empty" role="status">
                    <span class="fuy-empty-mark" aria-hidden="true">空</span>
                    <p class="fuy-empty-title">暂无消费错误</p>
                    <p class="fuy-empty-hint">
                      当前筛选条件下无死信记录，可切换状态筛选或点击刷新。
                    </p>
                  </div>
                </template>
              </el-table>
            </div>
          </section>
        </el-col>
      </el-row>

      <!-- 页脚批注（契约 ⑧.8）：手动刷新取数语义（禁放操作钮） -->
      <footer class="fuy-page-foot" :style="{ '--fuy-stagger-index': 3 }">
        四区均为手动刷新取数，不自动轮询
      </footer>

      <!-- 放弃原因强制弹窗（死信治理：放弃后不再重投，原因留痕由后端承载；.fuy-dialog 弹层脸） -->
      <el-dialog v-model="abandonVisible" title="放弃消费错误" width="420px" class="fuy-dialog">
        <p class="quality-abandon-target fuy-num">
          {{ abandonTarget?.errorId ?? '' }} · {{ abandonTarget?.queueName ?? '' }}
        </p>
        <label class="quality-field-label">放弃原因（强制：确认脏数据/无效消息不再重投）</label>
        <textarea
          v-model="abandonReason"
          class="quality-textarea"
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

      <!-- 钤印确认浮层（契约 ⑧.5：只承成功确认；v-if 挂载制 + seq :key 重挂重放落章——
           丙-2/丙-3 同构：常驻形态会被 .fuy-stagger > * 的 fuy-rise both 误伤，挂载即 is-show
           根治；≤1200ms 收场归 showStamp 定时器） -->
      <div v-if="stampVisible" :key="stampSeq" class="fuy-stamp-toast is-show" aria-live="polite">
        {{ stampText }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 暖纸重排（丙-7）：门牌页首/待处置死信胶囊/四区卷宗卡/积压琥珀双通道/放弃弹层脸；
   取色全走 --fuy-* 语义别名（零新 hex），动效只消费 motion.css 全局 keyframes */

/* 页内级联容器（契约 ⑦.4）：本层成为 .fuy-page 唯一子级后根级 flex gap 失效，纵排与
   区块间距由本层同值承接（.fuy-page 同构 flex+gap），保证视觉零漂移 */
.quality-flow {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-3);
}

/* 门牌状态位：待处置死信胶囊 + 刷新钮同行（丙-2 同位语法） */
.fuy-page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 卡头右侧工具区：计数注记/胶囊徽标/筛选控件 */
.quality-card-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fuy-space-2);
}

.quality-card-total {
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 质量统计卡头筛选行 */
.quality-filter {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 待处置死信计数骨架（首拉落地前占位）：静态金洗纸胶囊形零 shimmer，定尺寸锁 CLS
   （丙-4 alarm-count-sk/丙-6 linkage-failed-sk 同构档） */
.quality-pending-sk {
  width: 88px;
  height: 24px;
  border-radius: var(--fuy-radius-full);
}

/* 四区卡体 min-height 200px 锁 CLS（蓝图 丙-7.6：骨架两行各 200px 档） */
.quality-zone-body {
  min-height: 200px;
}

/* 筛选/弹窗基元（native input/select/textarea 与 EP 密度口径对齐）；围合取值直取 --fuy-*
   语义通道（契约 ⑤#2 输入围合=最亮纸白底+石规描边——住院域琢段统一口径） */
.quality-input {
  width: 160px;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

.quality-status-select {
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
}

/* 放弃原因 textarea（弹窗内全宽）：与输入基元同族描边脸 */
.quality-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 5px var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-family: inherit;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-emphasis);
  background: var(--fuy-surface-input);
  resize: vertical;
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

/* 积压水位异常双通道（蓝图 丙-7.2 PM 重排）：琥珀文字 + 「积压」字样（阈值=零值判断，
   色值走警示语义 token——双通道铁律：色不单独承义） */
.quality-backlog-hot {
  color: var(--fuy-color-warning-text);
  font-weight: 600;
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
