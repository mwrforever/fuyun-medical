<script setup lang="ts">
// 护理执行工作台页（/nursing/execution，PR-3 Task 14，FU-M05-04 前端面；暖纸卷宗 pages-2
// 甲-2 建段重排）纵向四段：①门牌页首（衬线标题+EXECUTION DESK 英文小注+病区/班次/日期三
// 筛选状态位+「执行人 谁·班次·五环节闭环」批注行，旧工具条 h2 大标题+副注解散）②四列看板
// （待签收/待核对/待执行/执行中，列=分组即合规分组策略；列内 flex column 独立滚动承载行卡
// 上限；四列合计>120 时按列收「已到底·共 N 条」签行——渲染节点预算锁，分页归后端 list 既有
// 分页参数）③详情抽屉 520px（升档裁决 380→520 承契约 drawer 档；内部纵向序=单号行→批注槽
// →遥测条→闭环时间线→核对流水→扫码核对→动作组）④页脚批注（看板快照时点）。行卡=患者床号/
// 医嘱摘要/计划时间/升级标记；LONG 锚行以类型标签区分且不进操作流（Task 4 裁决锚行
// m04PlanNo 为空）。业务状态面归 useExecutions（EX-47 拆分，数据契约/五动作启停与在途互斥/
// 扫码正则零变动）；动作成功反馈=钤印 .fuy-stamp-toast（已签收/已核对/已开始/已完成，
// ≤1200ms 收场），失败与撤销=message 批注条（composable 既有口径）——钤印在视图层以
// 「动作是否引发看板重拉」判定成功：抽屉模态遮罩下看板重拉仅动作成功链路触发
// （refreshAfterAction），失败/校验拦截/撤销取消均不重拉。病区下拉=listOrgs({type:'WARD'})
// 真数据三态（WARD_OPTIONS 假常量清零），会话记忆键 nursing.exec.wardId 沿用；
// /nursing/executions/occupancy 不取用（在途占用已被列分组承载，再拉会引入双源真值）。
// 执行单号文本+复制按钮（打印降级注记：打印归后续批次，复制回执号面已覆盖床旁对单需求）。
// 失败弹错归响应拦截器（AxiosError 防双弹，业务拒绝对象由 surfaceBizError 兜底）。
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
// ElMessage 在组件模板外使用，按需样式手动引入（存量页面同款口径）
import 'element-plus/es/components/message/style/css';
import { CHECK_TYPE_OPTIONS, EXECUTION_TYPE_LABELS, SHIFT_OPTIONS } from '@/api/nursing';
import type { OrderExecutionVO } from '@/api/nursing';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { INFUSION_ALERT_LABELS } from '@/api/ward';
import { useAsyncTask } from '@/composables/useAsyncTask';
import { useAuthStore } from '@/stores/auth';
import { formatTime } from '@/utils/timeFormat';
import { isAnchorRow, useExecutions } from './composables/useExecutions';
import type { ExecutionColumn } from './composables/useExecutions';

const auth = useAuthStore();

/* ==================== 病区真数据下拉（假常量清零：WARD_OPTIONS → listOrgs） ==================== */
/** 病区会话记忆键（切换/刷新不回默认病区，口径同护士站，既有键沿用） */
const WARD_MEMORY_KEY = 'nursing.exec.wardId';

/** 当前病区（会话记忆初值优先——清单到达前仅回显记忆码，看板待病区定档后才出网） */
const wardId = ref(sessionStorage.getItem(WARD_MEMORY_KEY) ?? '');

/** 病区清单（listOrgs 真数据源，V1123 种子同链路）；仅保留带 code 的机构行 */
const wards = ref<OrgVO[]>([]);
/** 病区清单是否已落定（成败均计）：区分「未加载」与「加载为空」，骨架/指引不抢跑 */
const wardsSettled = ref(false);

/**
 * 病区清单加载（useAsyncTask 三态）：加载中下拉禁用+看板骨架 / 失败批注行+重试 / 空清单指引；
 * 失败态只落 error ref 驱动页内失败批注行与重试钮，错误详情弹错归响应拦截器——页内不重弹
 * （住院摆药台同款口径）。就绪后回填目标病区：会话记忆优先（nursing.exec.wardId 沿用），
 * 记忆缺失取首项，清单为空保持空串（病区未定档零出网，见 refreshBoard）。
 */
const {
  loading: wardsLoading,
  error: wardsError,
  run: loadWards,
} = useAsyncTask(
  async () => {
    wards.value = (await listOrgs({ type: 'WARD' })).filter((org) => org.orgCode);
    const remembered = sessionStorage.getItem(WARD_MEMORY_KEY) ?? '';
    wardId.value = remembered !== '' ? remembered : (wards.value[0]?.orgCode ?? '');
  },
  {
    onFinally: () => {
      wardsSettled.value = true;
    },
  },
);

/** 空清单态：清单已落定且为空（下拉禁用+维护引导，不伪装成可选项） */
const wardsEmpty = computed(
  () => wardsSettled.value && wardsError.value === null && wards.value.length === 0,
);

/** 病区下拉禁用：加载中 / 失败待重试 / 空清单三态均无可选项，禁用防误操作 */
const wardsDisabled = computed(
  () => wardsLoading.value || wardsError.value !== null || wardsEmpty.value,
);

/** 病区清单加载中/未落定（看板区骨架占位，防空列抢跑） */
const wardsPending = computed(() => !wardsSettled.value || wardsLoading.value);

/** 执行工作台状态面（执行人=会话用户 userId，start/finish 留痕锚点） */
const state = useExecutions({
  wardId,
  getExecutorId: () => auth.user?.userId ?? '',
});
const {
  filterDate,
  filterShift,
  columns,
  boardLoading,
  loadBoard,
  drawerVisible,
  selected,
  trace,
  traceLoading,
  openDetail,
  infusionStrip,
  acting,
  availableActions,
  onSignReceive,
  onScanCheck,
  onStart,
  onFinish,
  onCancelExecution,
  scanCode,
  scanType,
} = state;

/** 看板刷新统一入口（病区未定档零出网：空清单/失败态查询空转，不发起执行单请求） */
function refreshBoard(): void {
  if (wardId.value !== '') {
    void loadBoard();
  }
}

/** 病区切换：会话记忆 + 看板重拉 */
function onWardChange(): void {
  sessionStorage.setItem(WARD_MEMORY_KEY, wardId.value);
  refreshBoard();
}

/** 病区清单重试：就绪后按回填病区恢复看板（失败保持批注行，成功走 refreshBoard） */
function onWardsRetry(): void {
  void loadWards().then(refreshBoard);
}

onMounted(() => {
  void loadWards().then(refreshBoard);
});

/* ==================== 页脚快照时点与钤印确认（视图层反馈面） ==================== */
/** 看板快照时点（页脚批注）：boardLoading 收位即任一看板重拉完成（含动作成功后自动重拉） */
const snapshotAt = ref('');
watch(
  boardLoading,
  (now, was) => {
    if (was === true && now === false) {
      snapshotAt.value = new Date().toTimeString().slice(0, 8);
    }
  },
  { flush: 'sync' },
);

/** 钤印确认浮层显示态（契约 ⑧.5：文案=动词结果，≤1200ms 收场；失败/撤销零出现） */
const stampShown = ref(false);
/** 钤印文案（已签收/已核对/已开始/已完成） */
const stampText = ref('');
/** 钤印收场定时句柄：重复触发先清旧计时，组件卸载清理防泄漏 */
let stampTimer: number | undefined;

/** 盖钤印：置文案+落章，1200ms 后收场（reduced-motion 下动画由全局兜底压停，时序不变） */
function showStamp(text: string): void {
  stampText.value = text;
  stampShown.value = true;
  if (stampTimer !== undefined) {
    window.clearTimeout(stampTimer);
  }
  stampTimer = window.setTimeout(() => {
    stampShown.value = false;
    stampTimer = undefined;
  }, 1200);
}

onUnmounted(() => {
  // 钤印收场定时器为本组件注册的全局副作用：卸载即清，防泄漏与卸载后翻转（LoginView 同款口径）
  if (stampTimer !== undefined) {
    window.clearTimeout(stampTimer);
    stampTimer = undefined;
  }
});

/**
 * 动作包装：成功盖钤印。成功判定=动作引发看板重拉（boardLoading 翻起，flush sync 同步捕获
 * 零时序歧义）——抽屉模态遮罩下看板重拉仅动作成功链路触发（refreshAfterAction），失败/
 * 校验拦截/撤销取消均不重拉；composable 契约零改动（EX-47），成功反馈升钤印在视图收口。
 *
 * @param stamp 钤印文案（动词结果：已签收/已核对/已开始/已完成）
 * @param action composable 动作函数（内部自带在途互斥与失败兜底）
 */
async function actWithStamp(stamp: string, action: () => Promise<void>): Promise<void> {
  let refreshed = false;
  const stop = watch(
    boardLoading,
    (now, was) => {
      if (was === false && now === true) {
        refreshed = true;
      }
    },
    { flush: 'sync' },
  );
  try {
    await action();
  } finally {
    stop();
  }
  if (refreshed) {
    showStamp(stamp);
  }
}

/** 五动作视图包装（模板绑定入口）：四成功动作盖对应钤印；撤销保持 message（蓝图裁定） */
function onSignReceiveAction(): Promise<void> {
  return actWithStamp('已签收', onSignReceive);
}
function onCheckAction(): Promise<void> {
  return actWithStamp('已核对', onScanCheck);
}
function onStartAction(): Promise<void> {
  return actWithStamp('已开始', onStart);
}
function onFinishAction(): Promise<void> {
  return actWithStamp('已完成', onFinish);
}

/* ==================== 行卡与看板渲染预算 ==================== */
/** 渲染节点预算（契约 ⑨.4 卡墙 >120 分组承载）：四列合计上限，超限按列收签行 */
const BOARD_RENDER_LIMIT = 120;
/** 单列渲染上限（合计预算均摊四列=30）；全量数据仍入列内滚动语义，分页归后端 list 既有参数 */
const BOARD_COLUMN_CAP = 30;

/** 四列合计是否超渲染预算（超限列收「已到底 · 共 N 条」签行，其余行由列内滚动/后端分页承载） */
const overRenderLimit = computed(
  () => columns.value.reduce((sum, column) => sum + column.rows.length, 0) > BOARD_RENDER_LIMIT,
);

/**
 * 列内可见行卡：未超限全量渲染；超限截断至单列上限，防一次全量渲染卡墙。
 *
 * @param column 看板单列（列定义+行集）
 * @return 该列实际渲染的行集（截断后）
 */
function visibleRows(column: ExecutionColumn): OrderExecutionVO[] {
  return overRenderLimit.value ? column.rows.slice(0, BOARD_COLUMN_CAP) : column.rows;
}

/** 行卡类型标签（Task 4 锚行区分：LONG=类型锚行 / STAT=执行快照） */
function rowTypeLabel(row: OrderExecutionVO): string {
  return isAnchorRow(row) ? '类型锚行' : '执行快照';
}

/** 行卡标记（升级标记：越权核对/关联告警；输液类型标记监测挂接） */
function rowBadges(row: OrderExecutionVO): Array<{ cls: string; text: string }> {
  const badges: Array<{ cls: string; text: string }> = [
    { cls: 'exec-badge--type', text: EXECUTION_TYPE_LABELS[row.executionType ?? ''] ?? '' },
  ];
  if (row.overrideFlag === true) {
    badges.push({ cls: 'exec-badge--override', text: '越权' });
  }
  if ((row.latestAlarmNo ?? '') !== '') {
    badges.push({ cls: 'exec-badge--alarm', text: '告警' });
  }
  return badges.filter((badge) => badge.text !== '');
}

/* ==================== 详情抽屉 ==================== */
/** 执行人显示名（批注行/抽屉批注槽「谁」位；空值 — 占位零伪数据） */
const executorName = computed(() => auth.user?.displayName ?? '—');

/** 班次显示名（批注行「班次」位；空=全部班次） */
const shiftLabel = computed(
  () => SHIFT_OPTIONS.find((shift) => shift.code === filterShift.value)?.label ?? '全部班次',
);

/** 闭环时间线（五环节时点 + 拔针；未到环节灰显占位——闭环进度一眼可读） */
const timeline = computed(() => {
  const source = trace.value ?? selected.value;
  if (source === null) {
    return [];
  }
  return [
    { label: '计划', at: source.planTime },
    { label: '签收', at: source.signedAt },
    { label: '核对', at: source.checkedAt },
    { label: '开始', at: source.startedAt },
    { label: '完成', at: source.finishedAt },
    { label: '拔针', at: source.needleOutAt },
  ];
});

/** 核对流水（trace 面核对明细；checkResult PASS/FAIL 双落留痕） */
const checkLogs = computed(() => trace.value?.checkLogs ?? []);

/** 当前抽屉行的可用动作集（锚行恒空集） */
const actions = computed(() => (selected.value === null ? [] : availableActions(selected.value)));

/** 复制执行单号（回执号面；打印降级注记——打印归后续批次，复制已覆盖床旁对单需求） */
async function onCopyNo(): Promise<void> {
  const no = selected.value?.executionNo ?? '';
  if (no === '') {
    return;
  }
  try {
    await navigator.clipboard.writeText(no);
    void ElMessage.success('执行单号已复制');
  } catch {
    void ElMessage.warning('复制失败（浏览器未授权剪贴板），请手动抄录执行单号');
  }
}
</script>

<template>
  <div class="fuy-page exec-workbench">
    <!-- ① 门牌页首（契约 ⑧.1）：衬线标题+英文小注（装饰层）+ 三筛选状态位右挂 +
         「执行人 谁 · 班次 · 五环节闭环」批注行；2px 墨规收底走全局 .fuy-page-head 脸，
         本页零私有标题样式（旧 h2 大标题+副注随门牌收编删除） -->
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">护理执行工作台</h1>
        <span class="fuy-page-en" aria-hidden="true">EXECUTION DESK</span>
      </div>
      <div class="fuy-page-status exec-head-filters">
        <el-select
          v-model="wardId"
          class="exec-ward"
          placeholder="请选择病区"
          :loading="wardsLoading"
          :disabled="wardsDisabled"
          popper-class="fuy-snap-popper"
          @change="onWardChange"
        >
          <el-option
            v-for="org in wards"
            :key="org.id ?? org.orgCode"
            :label="org.orgName ?? org.orgCode"
            :value="org.orgCode ?? ''"
          />
        </el-select>
        <el-select
          v-model="filterShift"
          class="exec-shift"
          placeholder="全部班次"
          clearable
          popper-class="fuy-snap-popper"
          @change="refreshBoard"
        >
          <el-option
            v-for="shift in SHIFT_OPTIONS"
            :key="shift.code"
            :label="shift.label"
            :value="shift.code"
          />
        </el-select>
        <input
          v-model="filterDate"
          type="date"
          class="exec-date"
          aria-label="计划日期"
          @change="refreshBoard"
        />
        <el-button type="primary" :loading="boardLoading" @click="refreshBoard">查询</el-button>
      </div>
      <p class="fuy-page-note">
        执行人 {{ executorName }} · {{ shiftLabel }} ·
        医嘱执行单五环节闭环（签收-核对-执行-完成-追溯）
      </p>
    </header>

    <!-- ② 四列看板（列=分组即合规分组策略；终态行不进列；列内按计划时间升序=后端返回序）：
         病区清单失败/空清单批注行 → 加载骨架 → 看板（错误态不伪装空态，说明给恢复/给下一步） -->
    <div v-if="wardsError !== null" class="exec-ward-state" role="status">
      <span>病区清单加载失败，执行看板暂不可用</span>
      <el-button link type="primary" :loading="wardsLoading" @click="onWardsRetry">重试</el-button>
    </div>
    <p v-else-if="wardsEmpty" class="exec-ward-state" role="status">
      暂无可用病区，请联系管理员维护组织机构后再使用执行看板
    </p>
    <!-- 加载骨架（G4 三态；min-height 320 锁 CLS，静态金洗纸块零 shimmer 常驻动画） -->
    <div v-else-if="wardsPending" class="exec-board-skeleton" aria-hidden="true">
      <span v-for="i in 4" :key="i" class="fuy-sk exec-sk-col" />
    </div>
    <div v-else v-loading="boardLoading" class="exec-board fuy-stagger">
      <section
        v-for="(column, index) in columns"
        :key="column.status"
        class="exec-column"
        :style="{ '--fuy-stagger-index': index }"
        :aria-label="column.label"
      >
        <header class="exec-column-head">
          <span class="exec-column-title">{{ column.label }}</span>
          <span class="fuy-num exec-column-count">{{ column.rows.length }}</span>
        </header>
        <div class="exec-column-body">
          <button
            v-for="row in visibleRows(column)"
            :key="row.executionNo"
            type="button"
            class="exec-card"
            :class="{ 'is-anchor': isAnchorRow(row) }"
            @click="openDetail(row)"
          >
            <div class="exec-card-head">
              <span class="fuy-num exec-card-bed">{{ row.bedNo ?? '—' }}</span>
              <span class="fuy-num exec-card-plan">{{ formatTime(row.planTime) }}</span>
            </div>
            <div
              class="exec-card-item"
              :title="`${row.execItemName ?? ''} ${row.dosageText ?? ''}`"
            >
              {{ row.execItemName ?? '—' }}
              <span v-if="(row.dosageText ?? '') !== ''" class="exec-card-dosage fuy-num">{{
                row.dosageText
              }}</span>
            </div>
            <div class="exec-card-badges">
              <span
                v-for="badge in rowBadges(row)"
                :key="badge.text"
                class="exec-badge"
                :class="badge.cls"
                >{{ badge.text }}</span
              >
              <span
                class="exec-badge exec-badge--rowtype"
                :class="{ 'is-anchor': isAnchorRow(row) }"
              >
                {{ rowTypeLabel(row) }}
              </span>
            </div>
          </button>
          <!-- 渲染预算签行：四列合计>120 时截断列收底（N=该列完整行数；分页归后端既有参数） -->
          <p
            v-if="overRenderLimit && column.rows.length > BOARD_COLUMN_CAP"
            class="exec-column-foot fuy-num"
          >
            已到底 · 共 {{ column.rows.length }} 条
          </p>
          <p v-if="column.rows.length === 0" class="exec-column-empty">—</p>
        </div>
      </section>
      <!-- 看板级空态（契约 ⑥ 空态脸禁纸箱插画）：主句合「暂无」语法，说明给下一步 -->
      <div
        v-if="columns.every((column) => column.rows.length === 0)"
        class="exec-board-empty fuy-empty"
        role="status"
      >
        <span class="fuy-empty-mark" aria-hidden="true">空</span>
        <p class="fuy-empty-title">暂无执行单</p>
        <p class="fuy-empty-hint">
          当前病区与班次暂无待办执行单，可切换病区或班次，或点击查询刷新。
        </p>
      </div>
    </div>

    <!-- ③ 详情抽屉（520px 升档承契约 drawer 档，挂 .fuy-drawer 弹层脸）：内部纵向序=
         单号行 → 批注槽（谁·何时）→ 遥测条 → 闭环时间线 → 核对流水 → 扫码核对 → 动作组；
         EP 内建右推过渡保留（翻纸语义，不压制） -->
    <el-drawer
      v-model="drawerVisible"
      title="执行单详情"
      size="520px"
      class="fuy-drawer exec-drawer"
    >
      <template v-if="selected !== null">
        <!-- 执行单号 + 复制按钮（打印降级注记：复制已覆盖对单需求） -->
        <div class="exec-drawer-no-row">
          <span class="fuy-num exec-drawer-no">{{ selected.executionNo }}</span>
          <el-button link type="primary" size="small" @click="onCopyNo">复制单号</el-button>
        </div>
        <!-- 批注槽（谁·何时）：床号/就诊号/快照类型 + 执行人 + 计划时点（空值 — 占位） -->
        <p class="exec-drawer-note">
          <span class="fuy-num">{{ selected.bedNo ?? '—' }} 床</span>
          <span class="fuy-num">{{ selected.visitId }}</span>
          <span
            class="exec-badge exec-badge--rowtype"
            :class="{ 'is-anchor': isAnchorRow(selected) }"
          >
            {{ rowTypeLabel(selected) }}
          </span>
          <span>执行人 {{ executorName }} · 计划 {{ formatTime(selected.planTime) }}</span>
        </p>

        <!-- 输液遥测条（前端组合面：护理侧在途行 × ward 侧设备遥测；无在途输注不渲染；
             三档=infusion 语义族描边+文字，洗底走 A-5 批准的 EP light 阶暖化映射） -->
        <div v-if="infusionStrip !== null" class="exec-infusion-strip">
          <span class="exec-infusion-title">输注监测</span>
          <span class="fuy-num">
            余量 {{ infusionStrip.telemetry?.remainLatest ?? '—' }}ml / 滴速
            {{ infusionStrip.telemetry?.dropRateLatest ?? '—' }}
          </span>
          <span
            v-if="infusionStrip.telemetry?.alertLevel !== undefined"
            class="exec-infusion-alert"
            :class="`is-${(infusionStrip.telemetry.alertLevel ?? 'none').toLowerCase()}`"
          >
            {{ INFUSION_ALERT_LABELS[infusionStrip.telemetry.alertLevel] ?? '' }}
          </span>
          <span
            v-if="(infusionStrip.infusion.escalationCount ?? 0) > 0"
            class="exec-badge exec-badge--alarm"
            >升级 {{ infusionStrip.infusion.escalationCount }} 次</span
          >
        </div>

        <!-- 闭环时间线（五环节+拔针；未到环节灰显；零动画——状态文案直达防误读） -->
        <h4 class="fuy-section-title">闭环时间线</h4>
        <div v-loading="traceLoading" class="exec-timeline">
          <div
            v-for="node in timeline"
            :key="node.label"
            class="exec-timeline-node"
            :class="{ 'is-done': (node.at ?? '') !== '' }"
          >
            <i class="exec-timeline-dot" />
            <span class="exec-timeline-label">{{ node.label }}</span>
            <span class="fuy-num exec-timeline-at">{{ node.at ? formatTime(node.at) : '—' }}</span>
          </div>
        </div>

        <!-- 核对流水（PASS/FAIL 双落留痕） -->
        <template v-if="checkLogs.length > 0">
          <h4 class="fuy-section-title">核对流水</h4>
          <div class="exec-checklogs">
            <p v-for="(log, index) in checkLogs" :key="index" class="exec-checklog fuy-num">
              {{ formatTime(log.occurredAt) }}
              {{
                log.checkType === 'WRISTBAND'
                  ? '腕带'
                  : log.checkType === 'BAG_LABEL'
                    ? '瓶签'
                    : '执行单'
              }}
              {{ log.checkResult === 'PASS' ? '核对通过' : '核对不符' }}
            </p>
          </div>
        </template>

        <!-- 扫码核对（三向单维；输入框回车提交=扫码枪形态，PDA 同款正则校验零变动） -->
        <h4 class="fuy-section-title">扫码核对</h4>
        <div class="exec-scan">
          <select v-model="scanType" class="exec-scan-type" aria-label="核对方式">
            <option v-for="item in CHECK_TYPE_OPTIONS" :key="item.code" :value="item.code">
              {{ item.label }}
            </option>
          </select>
          <input
            v-model="scanCode"
            class="exec-scan-input"
            type="text"
            autocomplete="off"
            placeholder="扫码后回车提交"
            :disabled="actions.length === 0"
            @keyup.enter="onCheckAction"
          />
        </div>

        <!-- 操作按钮组（五动作在途互斥；锚行不进操作流）；五动作（PR-4F #29，NURSE 绑定）
             同码 v-perm 直挂——无码 DOM 移除（D-34），:disabled 在途数据态正交叠加；
             扫码核对输入框回车通道（附件 A 元素列未列）不挂码，越权出网由后端端点绑定拦截；
             动作成功盖钤印（已签收/已核对/已开始/已完成），撤销保持=message（蓝图裁定） -->
        <div v-if="actions.length > 0" class="exec-drawer-actions">
          <el-button
            v-if="actions.includes('signReceive')"
            v-perm="'nursing:execution:btn:perform'"
            type="primary"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onSignReceiveAction"
            >补签收</el-button
          >
          <el-button
            v-if="actions.includes('check')"
            v-perm="'nursing:execution:btn:perform'"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onCheckAction"
            >核对</el-button
          >
          <el-button
            v-if="actions.includes('start')"
            v-perm="'nursing:execution:btn:perform'"
            type="primary"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onStartAction"
            >开始执行</el-button
          >
          <el-button
            v-if="actions.includes('finish')"
            v-perm="'nursing:execution:btn:perform'"
            type="success"
            size="small"
            :loading="acting"
            :disabled="acting"
            @click="onFinishAction"
            >完成执行</el-button
          >
          <el-button
            v-if="actions.includes('cancel')"
            v-perm="'nursing:execution:btn:perform'"
            link
            type="danger"
            size="small"
            :disabled="acting"
            @click="onCancelExecution"
            >撤销</el-button
          >
        </div>
        <p v-else class="exec-anchor-hint">类型锚行不进操作流（STAT 快照行才是执行载体）</p>
      </template>
    </el-drawer>

    <!-- 成功钤印浮层（契约 ⑧.5）：文案=动词结果，≤1200ms 收场；失败/撤销零出现 -->
    <div class="fuy-stamp-toast" :class="{ 'is-show': stampShown }" aria-live="polite">
      {{ stampText }}
    </div>

    <!-- ④ 页脚批注（契约 ⑧.8）：看板快照时点（动作成功后的自动重拉同步刷新）；禁放操作钮 -->
    <footer class="fuy-page-foot">
      看板快照 {{ snapshotAt || '—' }} · 手动刷新取数（病区/班次/日期变更即重拉）
    </footer>
  </div>
</template>

<style scoped>
/* ① 门牌状态位（三筛选+查询钮右挂；门牌页首本体走全局 .fuy-page-head 脸） */
.exec-head-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
}
.exec-ward {
  width: 150px;
}
.exec-shift {
  width: 110px;
}
/* 原生日期输入（最亮纸白底+石规描边，⑤#2 输入族围合同族；高度对齐 EP select 32px） */
.exec-date {
  height: 32px;
  padding: 0 var(--fuy-space-2);
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--fuy-surface-input);
}
/* 病区清单失败/空清单批注行（错误态不伪装空态：说问题+给恢复/给下一步） */
.exec-ward-state {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  margin: 0;
  color: var(--fuy-color-info-text);
  font-size: var(--fuy-font-size-sm);
}

/* ② 四列看板（等宽 grid；min-height CLS 锁既有；四列 stagger 进场 ≤4 档） */
.exec-board {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--fuy-space-3);
  min-height: 320px;
}
/* 加载骨架（静态金洗纸块，min-height 锁 CLS 与看板同构） */
.exec-board-skeleton {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--fuy-space-3);
}
.exec-sk-col {
  min-height: 320px;
}
/* 列容器=纸面分组签（发丝描边，底透工作面纸色——行卡亮纸/悬停暖底与此拉开层级） */
.exec-column {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-lg);
}
.exec-column-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border-bottom: var(--fuy-border-hairline);
}
.exec-column-title {
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}
/* 列头计数徽标=墨底纸字（primary 实底语义；禁状态朱作列色） */
.exec-column-count {
  min-width: 18px;
  padding: 0 6px;
  border-radius: var(--fuy-radius-full);
  background: var(--fuy-color-brand);
  color: var(--fuy-paper);
  font-size: var(--fuy-font-size-xs);
  font-weight: 700;
  text-align: center;
}
/* 列内 flex column 独立滚动：max-height=视口高−门牌/页脚工具区高（约 288px），
   行卡上限由列内滚动承载（列即分组，>120 卡合规路径 ⑨.4） */
.exec-column-body {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  padding: var(--fuy-space-2);
  overflow-y: auto;
  max-height: calc(100vh - 288px);
}
/* 渲染预算签行（已到底 · 共 N 条）：上缘发丝分隔，灰墨弱形态不抢行卡扫读 */
.exec-column-foot {
  margin: 0;
  padding: var(--fuy-space-1) 0;
  border-top: var(--fuy-border-hairline);
  color: var(--fuy-color-info-text);
  font-size: var(--fuy-font-size-xs);
  text-align: center;
}
.exec-column-empty {
  margin: 0;
  text-align: center;
  color: var(--fuy-color-text-secondary);
}
/* 行卡（纸面卡=卡面亮纸+石规描边；锚行虚线描边降区分）；hover=规线加重+悬停暖底（禁投影） */
.exec-card {
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--el-border-color);
  border-radius: var(--fuy-radius-lg);
  background: var(--fuy-surface-card);
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--fuy-motion-fast) linear,
    background-color var(--fuy-motion-fast) linear;
}
.exec-card:hover {
  border-color: var(--fuy-color-brand);
  background: var(--fuy-palette-brand-50);
}
.exec-card.is-anchor {
  border-style: dashed;
}
.exec-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.exec-card-bed {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
.exec-card-plan {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.exec-card-item {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fuy-font-size-sm);
}
.exec-card-dosage {
  margin-left: 4px;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.exec-card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
/* 标记（类型/越权/告警，token 取色禁自创色值） */
.exec-badge {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--fuy-radius-full);
  font-size: var(--fuy-font-size-xs);
  line-height: 1;
}
.exec-badge--type {
  background: var(--el-color-primary-light-8);
  color: var(--fuy-color-brand);
}
.exec-badge--rowtype {
  background: var(--el-fill-color-light);
  color: var(--fuy-color-text-secondary);
}
.exec-badge--rowtype.is-anchor {
  background: var(--el-color-info-light-8);
  color: var(--el-text-color-secondary);
}
.exec-badge--override {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.exec-badge--alarm {
  background: var(--el-color-danger-light-9);
  color: var(--fuy-color-danger-text);
}
/* 看板级空态（.fuy-empty 全局脸）：悬浮居中于四列之上 */
.exec-board-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

/* ③ 抽屉（520px；.fuy-drawer 全局脸承载衬线标题/头底发丝/标准弹层阴影） */
.exec-drawer-no-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.exec-drawer-no {
  font-size: var(--fuy-font-size-lg);
  font-weight: 700;
}
/* 批注槽（谁·何时）：灰墨批注行，床号/就诊号/快照类型 + 执行人 + 计划时点 */
.exec-drawer-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin: var(--fuy-space-2) 0;
  color: var(--fuy-color-info-text);
  font-size: var(--fuy-font-size-xs);
}
/* 输液遥测条（组合面；三档=infusion 语义族描边+文字，洗底走 A-5 批准的 EP light 阶映射） */
.exec-infusion-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: 1px solid var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
}
.exec-infusion-title {
  font-weight: 600;
}
.exec-infusion-alert {
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: var(--fuy-radius-full);
  font-size: var(--fuy-font-size-xs);
}
.exec-infusion-alert.is-yellow {
  border-color: var(--fuy-color-infusion-yellow);
  background: var(--el-color-warning-light-9);
  color: var(--fuy-color-infusion-yellow);
}
.exec-infusion-alert.is-orange {
  border-color: var(--fuy-color-infusion-orange);
  background: var(--el-color-warning-light-8);
  color: var(--fuy-color-infusion-orange);
}
.exec-infusion-alert.is-red {
  border-color: var(--fuy-color-infusion-red);
  background: var(--el-color-danger-light-9);
  color: var(--fuy-color-infusion-red);
}
/* 时间线（左缘连线；已到环节实心点+深色，未到灰显；零动画——状态文案直达） */
.exec-timeline {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-2);
  min-height: 120px;
  padding-left: var(--fuy-space-2);
}
.exec-timeline::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 6px;
  bottom: 6px;
  width: 1px;
  background: var(--el-border-color);
}
.exec-timeline-node {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: var(--fuy-space-2);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
.exec-timeline-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: var(--fuy-radius-full);
  background: var(--el-border-color);
}
.exec-timeline-node.is-done {
  color: var(--fuy-color-text-emphasis);
}
.exec-timeline-node.is-done .exec-timeline-dot {
  background: var(--fuy-color-brand);
}
.exec-timeline-label {
  min-width: 32px;
}
.exec-timeline-at {
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
.exec-checklogs {
  margin-bottom: var(--fuy-space-3);
}
.exec-checklog {
  margin: 0 0 var(--fuy-space-1);
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
/* 扫码核对（核对方式 select + 原文输入，Enter 提交；最亮纸白底+石规描边同输入族） */
.exec-scan {
  display: flex;
  gap: var(--fuy-space-2);
  margin-bottom: var(--fuy-space-3);
}
.exec-scan-type {
  width: 88px;
  flex: none;
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--fuy-surface-input);
}
.exec-scan-input {
  flex: 1;
  min-width: 0;
  height: 24px;
  padding: 0 6px;
  border: 1px solid var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-sm);
  color: var(--el-text-color-regular);
  background: var(--fuy-surface-input);
}
.exec-scan-input:focus-visible {
  outline: 2px solid var(--fuy-color-brand);
  outline-offset: 0;
}
.exec-drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fuy-space-2);
}
.exec-anchor-hint {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-xs);
}
</style>
