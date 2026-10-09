<script setup lang="ts">
// 工作站首页（「纸质病案」世界 · 报表式编辑构图，批次 2 册 2 接真实聚合 API 后归位）：
// 门牌页首（问候语 + 「登录名 · 日期」批注行，2px 墨规收底）→ 指标带六格（直接坐在纸上，
// 竖发丝分隔，非卡片）→ 主栏体温单式趋势图（当期 vs 上周同期双序列 + 周同比，同一 14 日
// 真实序列推导）+ 各诊区候诊表 → 侧栏实时事件流（REST 轮询源 + STOMP 叫号帧源并存，危急值
// 按 criticalValueDegraded 标志渲染降级文案）+ 常用入口链接条。三态齐备：加载骨架（min-height
// 锁 CLS）→ 内容 200ms fade、诚实空态、错误态（说问题给恢复）。
// 零伪数据铁律：每个数字/序列/事件/表格行均可指源——overview/events 两聚合端点字段、
// STOMP 叫号帧真实载荷、或由真实数据推导（周同比=近 7 日与前 7 日两组真实和值之比）；
// 无真实来源的样稿元素（全院流转一行、诊区中文名、Ⅰ级列、设备指标）一律不落。
// 入口数据来自菜单常量（../layout/menu.ts）的真实路由入口——同一份数据既驱动侧栏也驱动
// 本页链接条，过滤口径=权限单道（auth.hasRoutePermission，与侧栏同口径）。
import { computed } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { EVENT_SOURCE_LABELS, EVENT_TYPE_LABELS } from '@/api/dashboard';
import { useAuthStore } from '@/stores/auth';
import { useWorkbenchFeed } from '@/composables/useWorkbenchFeed';
import { fenToYuanDisplay } from '@/utils/money';
import { formatTime } from '@/utils/timeFormat';
import { formatCount, weekOverWeekPercent } from '@/utils/workbenchDisplay';
import { MENU_ITEMS, type SidebarMenuItem } from '../layout/menu';

const authStore = useAuthStore();
const router = useRouter();

// 两域数据供给（REST 轮询 + STOMP 联动 + 三态；卸载清理由 composable 承载）
const {
  overview,
  overviewLoading,
  overviewError,
  events,
  eventsLoading,
  eventsError,
  streamItems,
  liveState,
  liveEndpoints,
  retryOverview,
  retryEvents,
} = useWorkbenchFeed();

/**
 * 按小时返回问候时段词（纯函数，钟点→文案可脱离系统时钟单测）：上午 <12 / 下午 <18 /
 * 其余为晚上。
 *
 * @param hour 24 小时制钟点（来源：new Date().getHours()，本地时区）
 * @return 时段问候词（上午好/下午好/晚上好）
 */
function greetingOf(hour: number): string {
  if (hour < 12) {
    return '上午好';
  }
  if (hour < 18) {
    return '下午好';
  }
  return '晚上好';
}

/** 问候语前缀（页面级一次性求值即可）：时段词随钟点变，展示与断言均不绑死具体时段 */
const greeting = computed(() => greetingOf(new Date().getHours()));

/**
 * 当日批注行日期标签（YYYY-MM-DD 周X）：病历页眉的日期批注语法，纯本地时钟零出网。
 * 取「YYYY-MM-DD」ISO 形态 + 中文星期单字（页眉批注行紧凑可读）。
 */
const todayLabel = computed(() => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const weekday = '日一二三四五六'[now.getDay()];
  return `${now.getFullYear()}-${month}-${day} 周${weekday}`;
});

/** 问候主文案：displayName 空值兜底「未登录用户」——路由守卫已默认拒绝未登录，
 * 兜底仅防组件被直接挂载的防御场景 */
const displayName = computed(() => authStore.user?.displayName ?? '未登录用户');

/** 批注行小字：登录名（空会话以 — 占位，不渲染裸空值） */
const loginName = computed(() => authStore.user?.loginName ?? '—');

/**
 * 按菜单 index 反查路由权限点：路由 = 权限点清单（web B.3-2），与侧栏同口径。
 *
 * @param index 菜单项路由路径
 * @return 路由登记的权限点编码；undefined = 未登记权限语义（恒可见）
 */
function routePermissionOf(index: string): string | undefined {
  return router.resolve(index).meta.permission;
}

/**
 * 常用入口：仅权限单道过滤（契约 §4 删岗后口径），再剔除首页自身（当前页自引用无意义）；
 * 顺序沿菜单常量稳定渲染序。
 */
const entries = computed<SidebarMenuItem[]>(() =>
  MENU_ITEMS.filter((item) => item.index !== '/').filter((item) =>
    authStore.hasRoutePermission(routePermissionOf(item.index)),
  ),
);

/**
 * 入口单字缩写（标签架语法保留）：缩写取页名首字派生（装饰性重复字符，aria-hidden 承载，
 * 非正文书文）。已知首字重复为接受态（患者建档/患者检索等）：按首字派生的必然结果，
 * 名称文字并列在侧承载区分，功能无损。
 *
 * @param item 菜单项（label 首字即缩写源）
 * @return 单字缩写（PDA 等英文页名取首字母）
 */
function initialOf(item: SidebarMenuItem): string {
  return item.label.charAt(0);
}

/** 进场级联序（≤5 封顶，第 6 项起并发，与 motion.css stagger 约定一致） */
function staggerIndex(index: number): number {
  return Math.min(index, 5);
}

/** 计数字段安全数值化（展示级：COUNT 聚合小整数；脏数据/缺失归 0 由空态兜底） */
function numberOf(value: string | undefined): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** 趋势双序列（同一 14 日真实序列推导：近 7 日=当期、前 7 日=上周同期）；不足两点视作无数据 */
const trendSeries = computed(() => {
  const trend = overview.value?.trend ?? [];
  if (trend.length < 2) {
    return null;
  }
  const mid = Math.floor(trend.length / 2);
  return {
    previous: trend.slice(0, mid),
    current: trend.slice(mid),
  };
});

/** 周同比（两组真实和值之比；分母为零诚实缺示） */
const wowPercent = computed<number | null>(() => {
  const series = trendSeries.value;
  if (series === null) {
    return null;
  }
  return weekOverWeekPercent(
    series.current.map((point) => numberOf(point.visitCount)),
    series.previous.map((point) => numberOf(point.visitCount)),
  );
});

/** 周同比展示文案（带符号，tabular 数字） */
const wowText = computed(() =>
  wowPercent.value === null
    ? '周同比 —'
    : `周同比 ${wowPercent.value > 0 ? '+' : ''}${wowPercent.value}%`,
);

/** 各诊区最长等待（分钟，真实候诊表推导）；候诊表空/无有效值 → null */
const longestWaitMinutes = computed<number | null>(() => {
  const rows = overview.value?.waitingTable ?? [];
  const minutes = rows
    .map((row) => Number(row.longestWaitingMinutes))
    .filter((value) => Number.isFinite(value));
  return minutes.length === 0 ? null : Math.max(...minutes);
});

/** 指标带六格（全部取自 overview.metrics 真实字段；delta 仅在可由真实数据推导时给出） */
const statCells = computed(() => {
  const metrics = overview.value?.metrics;
  return [
    {
      label: '今日挂号',
      value: formatCount(metrics?.todayVisits),
      delta: wowText.value,
    },
    {
      label: '当前候诊',
      value: formatCount(metrics?.waitingCount),
      delta:
        longestWaitMinutes.value === null
          ? '各诊区暂无候诊'
          : `最长等待 ${longestWaitMinutes.value} 分`,
    },
    {
      label: '今日收入',
      value:
        metrics?.todayIncomeFen === undefined
          ? '—'
          : `¥${fenToYuanDisplay(metrics.todayIncomeFen)}`,
      delta: null,
    },
    { label: '在院患者', value: formatCount(metrics?.inHospitalCount), delta: null },
    { label: '待发药处方', value: formatCount(metrics?.pendingDispenseCount), delta: null },
    { label: '待结算费用', value: formatCount(metrics?.pendingSettleCount), delta: null },
  ];
});

/* ===== 体温单式趋势 SVG 几何（viewBox 与样稿同构；坐标由真实序列计算，零手写点） ===== */

/** 图表几何常量（样稿 720×264 同构：绘图区 x∈[40,700]、y∈[44,224]、刻度行 y=252） */
const CHART = { w: 720, h: 264, x0: 40, x1: 700, yTop: 44, yBase: 224, yTick: 252 } as const;

/**
 * 纵轴上限取整（参考值标签干净可读）：向上取到 1/2/2.5/5×10^n 族；全零序列归 1（零线仍真实渲染）。
 *
 * @param max 序列最大值（非负）
 * @return 干净的纵轴上限
 */
function niceCeil(max: number): number {
  if (max <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(max));
  for (const multiple of [1, 2, 2.5, 5, 10]) {
    if (multiple * magnitude >= max) {
      return multiple * magnitude;
    }
  }
  return 10 * magnitude;
}

/** 日期短标签（statDate YYYY-MM-DD → MM-dd） */
function shortDate(statDate: string | undefined): string {
  return typeof statDate === 'string' && statDate.length >= 10 ? statDate.slice(5) : '—';
}

const trendChart = computed(() => {
  const series = trendSeries.value;
  if (series === null) {
    return null;
  }
  const { current, previous } = series;
  const count = current.length;
  const values = [...current, ...previous].map((point) => numberOf(point.visitCount));
  const yMax = niceCeil(Math.max(...values));
  const step = (CHART.x1 - CHART.x0) / (count - 1);
  const yOf = (value: number): number =>
    CHART.yBase - (Math.min(value, yMax) / yMax) * (CHART.yBase - CHART.yTop);
  const xOf = (index: number): number => CHART.x0 + index * step;
  const pointsOf = (points: typeof current): string =>
    points.map((point, index) => `${xOf(index)},${yOf(numberOf(point.visitCount))}`).join(' ');
  const lastValue = numberOf(current[count - 1]?.visitCount);
  // 全零序列（如清晨未开诊）：仅零基线一条参考线——1/0.5 刻度对零数据是视觉噪音
  const allZero = values.every((value) => value === 0);
  // 日期刻度：首/隔两日/末四档（anchor 首左末右，中间居中），防贴边裁切
  const tickIndexes = [0, 2, 4, count - 1].filter(
    (index, position, list) => list.indexOf(index) === position,
  );
  return {
    yMax,
    // 横参考线：上限/中值/零基线（中值虚线=体温单参考语法）；全零态收敛单零线
    gridLines: allZero
      ? [{ y: CHART.yBase, label: '0', dashed: false }]
      : [
          { y: CHART.yTop, label: formatCount(String(yMax)), dashed: false },
          { y: (CHART.yTop + CHART.yBase) / 2, label: formatCount(String(yMax / 2)), dashed: true },
          { y: CHART.yBase, label: '0', dashed: false },
        ],
    // 纵向淡格线（内部点位，体温单网格语法）
    vLines: Array.from({ length: count - 1 }, (_, index) => xOf(index + 1)),
    currentPoints: pointsOf(current),
    previousPoints: pointsOf(previous),
    endX: xOf(count - 1),
    endY: yOf(lastValue),
    endLabel: formatCount(String(lastValue)),
    ticks: tickIndexes.map((index) => ({
      x: xOf(index),
      label: shortDate(current[index]?.statDate),
      anchor: index === 0 ? 'start' : index === count - 1 ? 'end' : 'middle',
    })),
    aria: `近 7 日门诊接诊对比：当期合计 ${current
      .map((point) => numberOf(point.visitCount))
      .reduce((a, b) => a + b, 0)} 人次，上周同期合计 ${previous
      .map((point) => numberOf(point.visitCount))
      .reduce((a, b) => a + b, 0)} 人次`,
  };
});

/* ===== 候诊表 / 事件流 / 危急值 / 实时指示 / 页脚 ===== */

/** 候诊表行（展示形态：诊区编码原样呈现——后端候诊表无科室名契约，不造中文名） */
const waitingRows = computed(() =>
  (overview.value?.waitingTable ?? []).map((row) => ({
    deptCode: row.deptCode ?? '—',
    waitingCount: formatCount(row.waitingCount),
    longestWaitingMinutes:
      row.longestWaitingMinutes === undefined
        ? '—'
        : `${formatCount(row.longestWaitingMinutes)} 分`,
  })),
);

/** 时点当日钟面（HH:mm）：事件流时间列（样稿流水时间语法） */
function timeOfDay(iso: string): string {
  if (iso === '') {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** 事件流展示行（REST 源与 STOMP 叫号帧源统一形态；患者姓名不渲染——全院面脱敏从严） */
const streamList = computed(() =>
  streamItems.value.map((item) => ({
    key: item.id,
    time: timeOfDay(item.occurredAt),
    meta:
      item.type === 'QUEUE_CALLED'
        ? `门诊叫号 · ${item.source}`
        : `${EVENT_SOURCE_LABELS[item.source] ?? item.source} · ${EVENT_TYPE_LABELS[item.type] ?? item.type}`,
    text:
      item.type === 'QUEUE_CALLED'
        ? `${item.title} 已叫号${item.room === null ? '' : `（${item.room} 诊室）`}`
        : item.amountFen === null
          ? item.title
          : `${item.title} · ¥${fenToYuanDisplay(item.amountFen)}`,
    // 待支付/待配药=警示档（琥珀承文，双通道：文字词表恒在场）
    warning: item.type !== 'QUEUE_CALLED',
  })),
);

/** 危急值段文案（按 criticalValueDegraded 标志渲染降级文案；非降级按真实条数呈现） */
const criticalNote = computed<string | null>(() => {
  const data = events.value;
  if (data === null) {
    return null;
  }
  if (data.criticalValueDegraded) {
    return '危急值通道未接入：检验域建设中，接入后此处实时呈现危急值报告。';
  }
  const criticalCount = data.criticalValues?.length ?? 0;
  return criticalCount === 0
    ? '今日暂无危急值。'
    : `危急值 ${criticalCount} 条（明细展示待检验域契约冻结后接入）。`;
});

/** 实时指示文案（真实连接态 + 订阅面：空集=纯轮询模式诚实降级） */
const liveText = computed(() => {
  if (liveEndpoints.value.length === 0) {
    return '轮询模式 · 30s';
  }
  if (liveState.value === 'connected') {
    return '实时推送已连接';
  }
  return liveState.value === 'connecting' ? '实时连接中' : '实时重连中 · 轮询兜底';
});

/** 实时指示色调（双通道：色随文字语义，dot 仅装饰强化） */
const liveTone = computed(() => {
  if (liveEndpoints.value.length === 0) {
    return 'idle';
  }
  if (liveState.value === 'connected') {
    return 'on';
  }
  return liveState.value === 'connecting' ? 'pending' : 'off';
});

/** 页脚快照时间（总览域优先，事件域兜底——两域 generatedAt 均为后端真实生成时点） */
const updatedAt = computed(() =>
  formatTime(overview.value?.generatedAt ?? events.value?.generatedAt ?? undefined),
);
</script>

<template>
  <section class="fuy-page home-dash">
    <!-- 门牌页首：问候语 +「谁·何时」批注行（登录名 · 日期），页级 2px 墨规收底（样稿 .doc-head 同构） -->
    <header class="home-doc-head">
      <h1 class="home-doc-title">{{ greeting }}，{{ displayName }}</h1>
      <span class="home-doc-note">
        登录名 {{ loginName }} · <time>{{ todayLabel }}</time>
      </span>
    </header>

    <!-- 指标带六格：真实业务计数直接坐在纸上，竖发丝分隔（非卡片）；三态=骨架/错误/内容 -->
    <Transition name="fuy-content-fade" mode="out-in">
      <section
        v-if="overview !== null"
        key="stat-content"
        class="stat-strip fuy-stagger"
        aria-label="全院关键指标"
      >
        <div
          v-for="(cell, index) in statCells"
          :key="cell.label"
          class="stat"
          :style="{ '--fuy-stagger-index': staggerIndex(index) }"
        >
          <span class="stat-label">{{ cell.label }}</span>
          <span class="stat-value fuy-num">{{ cell.value }}</span>
          <span v-if="cell.delta !== null" class="stat-delta">{{ cell.delta }}</span>
        </div>
      </section>
      <div v-else-if="overviewError !== null" key="stat-error" class="home-error" role="alert">
        <p class="home-error-title">总览数据加载失败</p>
        <p class="home-error-hint">
          网络异常或服务暂不可用，指标与趋势暂无法呈现；可重试，问题持续请联系系统管理员。
        </p>
        <button type="button" class="home-retry" :disabled="overviewLoading" @click="retryOverview">
          {{ overviewLoading ? '重试中…' : '重试' }}
        </button>
      </div>
      <div v-else key="stat-skeleton" class="stat-strip" aria-hidden="true">
        <div v-for="index in 6" :key="index" class="stat">
          <span class="sk sk-label"></span>
          <span class="sk sk-value"></span>
          <span class="sk sk-delta"></span>
        </div>
      </div>
    </Transition>

    <div class="dash-grid">
      <!-- 主栏：体温单式趋势图 + 各诊区候诊表（overview 域三态） -->
      <div class="dash-main">
        <Transition name="fuy-content-fade" mode="out-in">
          <section v-if="overview !== null" key="main-content" aria-label="门诊接诊趋势与候诊">
            <section aria-label="门诊接诊趋势">
              <div class="trend-head">
                <h2 class="trend-title">门诊接诊 · 近 14 日周对比</h2>
                <span class="trend-legend"><i class="legend-key"></i>当期（近 7 日）</span>
                <span class="trend-legend"
                  ><i class="legend-key legend-key--dashed"></i>上周同期</span
                >
                <span class="trend-note">{{ wowText }}</span>
              </div>
              <svg
                v-if="trendChart !== null"
                class="trend-chart"
                :viewBox="`0 0 ${CHART.w} ${CHART.h}`"
                role="img"
                :aria-label="trendChart.aria"
              >
                <text
                  v-for="line in trendChart.gridLines"
                  :key="`gl-${line.y}`"
                  class="chart-reflabel"
                  x="0"
                  :y="line.y + 4"
                >
                  {{ line.label }}
                </text>
                <line
                  v-for="line in trendChart.gridLines"
                  :key="`rule-${line.y}`"
                  :class="line.dashed ? 'chart-ref' : 'chart-rule'"
                  :x1="CHART.x0"
                  :y1="line.y"
                  :x2="CHART.x1"
                  :y2="line.y"
                />
                <line
                  v-for="(x, index) in trendChart.vLines"
                  :key="`vl-${index}`"
                  class="chart-rule"
                  :x1="x"
                  :y1="CHART.yTop - 6"
                  :x2="x"
                  :y2="CHART.yBase"
                />
                <polyline class="chart-line-comp" :points="trendChart.previousPoints" />
                <polyline class="chart-line-main" :points="trendChart.currentPoints" />
                <circle class="chart-dot" :cx="trendChart.endX" :cy="trendChart.endY" r="3.5" />
                <text
                  class="chart-val"
                  :x="trendChart.endX"
                  :y="trendChart.endY - 12"
                  text-anchor="end"
                >
                  {{ trendChart.endLabel }}
                </text>
                <text
                  v-for="tick in trendChart.ticks"
                  :key="`tick-${tick.x}`"
                  class="chart-tick"
                  :x="tick.x"
                  :y="CHART.yTick"
                  :text-anchor="tick.anchor"
                >
                  {{ tick.label }}
                </text>
              </svg>
              <p v-else class="home-inline-empty">近 14 日暂无接诊统计数据。</p>
            </section>

            <!-- 各诊区候诊：来自总览快照的实时队列（真实 deptCode 原样呈现，与指标带「当前候诊」同源对账） -->
            <section class="waiting-sec" aria-label="各诊区候诊">
              <div class="trend-head">
                <h2 class="trend-title">各诊区候诊 · 实时</h2>
                <span class="note trend-note">更新于 {{ updatedAt }}</span>
              </div>
              <table v-if="waitingRows.length > 0" class="waiting-table">
                <thead>
                  <tr>
                    <th scope="col">诊区</th>
                    <th scope="col" class="num-right">候诊</th>
                    <th scope="col" class="num-right">最长等待</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in waitingRows" :key="row.deptCode">
                    <td>{{ row.deptCode }}</td>
                    <td class="num-right fuy-num">{{ row.waitingCount }}</td>
                    <td class="num-right fuy-num">{{ row.longestWaitingMinutes }}</td>
                  </tr>
                </tbody>
              </table>
              <p v-else class="home-inline-empty">各诊区当前无候诊患者。</p>
            </section>
          </section>
          <div
            v-else-if="overviewError !== null"
            key="main-error"
            class="home-inline-empty"
            role="alert"
          >
            总览数据加载失败，趋势与候诊表暂缺。
          </div>
          <div v-else key="main-skeleton" class="main-skeleton" aria-hidden="true">
            <div class="sk sk-trend"></div>
            <div class="sk sk-table-row"></div>
            <div class="sk sk-table-row"></div>
            <div class="sk sk-table-row"></div>
          </div>
        </Transition>
      </div>

      <!-- 侧栏：实时事件流（events 域三态）+ 常用入口 -->
      <aside class="dash-side">
        <section aria-label="实时事件">
          <div class="stream-head-row">
            <h2 class="trend-title">实时事件</h2>
            <span class="live" :class="`live--${liveTone}`">
              <i class="live-dot" aria-hidden="true"></i>{{ liveText }}
            </span>
          </div>
          <Transition name="fuy-content-fade" mode="out-in">
            <div v-if="events !== null" key="stream-content">
              <p v-if="criticalNote !== null" class="critical-note">{{ criticalNote }}</p>
              <TransitionGroup
                v-if="streamList.length > 0"
                name="fuy-flip"
                tag="div"
                class="stream"
              >
                <div v-for="item in streamList" :key="item.key" class="stream-item">
                  <time class="stream-time fuy-num">{{ item.time }}</time>
                  <div class="stream-body">
                    <div class="stream-meta" :class="{ 'stream-meta--warning': item.warning }">
                      {{ item.meta }}
                    </div>
                    <p class="stream-text">{{ item.text }}</p>
                  </div>
                </div>
              </TransitionGroup>
              <p v-else class="home-inline-empty">暂无待办工作事件，各域运转正常。</p>
            </div>
            <div
              v-else-if="eventsError !== null"
              key="stream-error"
              class="home-error home-error--side"
              role="alert"
            >
              <p class="home-error-title">事件流加载失败</p>
              <p class="home-error-hint">网络异常或服务暂不可用，可重试恢复。</p>
              <button
                type="button"
                class="home-retry"
                :disabled="eventsLoading"
                @click="retryEvents"
              >
                {{ eventsLoading ? '重试中…' : '重试' }}
              </button>
            </div>
            <div v-else key="stream-skeleton" aria-hidden="true">
              <div v-for="index in 4" :key="index" class="stream-item">
                <span class="sk sk-time"></span>
                <div class="stream-body">
                  <span class="sk sk-meta"></span>
                  <span class="sk sk-text"></span>
                </div>
              </div>
            </div>
          </Transition>
        </section>

        <!-- 常用入口：紧凑链接条（病案标签架语法，真实路由入口，RouterLink 直达） -->
        <template v-if="entries.length > 0">
          <section aria-label="常用入口">
            <h2 class="trend-title">常用入口</h2>
            <nav class="home-quick-grid fuy-stagger">
              <RouterLink
                v-for="(item, index) in entries"
                :key="item.index"
                class="home-quick-link"
                :to="item.index"
                :style="{ '--fuy-stagger-index': staggerIndex(index) }"
              >
                <span class="home-quick-mark" aria-hidden="true">{{ initialOf(item) }}</span>
                <span class="home-quick-link-label">{{ item.label }}</span>
              </RouterLink>
            </nav>
          </section>
        </template>
        <div v-else class="home-inline-empty">当前会话未被授予任何业务功能的访问权限。</div>
      </aside>
    </div>

    <!-- 页脚：后端快照生成时点（真实 generatedAt）+ 刷新语义 -->
    <footer v-if="overview !== null || events !== null" class="home-dash-foot">
      数据更新于 {{ updatedAt }} · 每 30s 自动刷新（页面隐藏时暂停）
    </footer>
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：页面骨架由 .fuy-page 全局类承载；本块承载报表式构图
   （指标带/趋势图/候诊表/事件流）与三态样式；字号间距颜色全部 token 化，零新色值 */

/* 报表构图纵向零缝（样稿 .page--dash 同构）：区块间距由各区自带 padding/border 承载 */
.fuy-page.home-dash {
  gap: 0;
}

/* 门牌页首：baseline 对齐的问候行，页级 2px 墨规收底（墨规=页级规线，石规/发丝不越级） */
.home-doc-head {
  display: flex;
  align-items: baseline;
  gap: var(--fuy-space-3);
  padding: var(--fuy-space-1) var(--fuy-space-1) var(--fuy-space-3);
  border-bottom: 2px solid var(--fuy-color-text-emphasis);
}

.home-doc-title {
  margin: 0;
  font-size: var(--fuy-font-size-2xl); /* 20px，与样稿门牌标题同档 */
  font-weight: 600;
  line-height: 1.3;
  color: var(--fuy-color-text-emphasis);
}

/* 批注行：文书页边注语法（「谁·何时」必带），弱墨小字不抢问候主语 */
.home-doc-note {
  color: var(--fuy-color-info-text);
  font-size: var(--fuy-font-size-xs);
}

/* ===== 指标带：直接坐在纸上的数字行，竖发丝分隔——不是卡片（样稿 .stat-strip 同构） ===== */
.stat-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border-bottom: var(--fuy-border-panel);
}

.stat {
  min-width: 0;
  padding: var(--fuy-space-3) var(--fuy-space-5) var(--fuy-space-3) var(--fuy-space-4);
  border-left: var(--fuy-border-hairline);
}

.stat:first-child {
  border-left: 0;
  padding-left: var(--fuy-space-1);
}

.stat-label {
  display: block;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* 关键计数 key-count：700 字重 + tabular 等宽（数字轮询刷新不跳宽） */
.stat-value {
  display: block;
  margin-top: var(--fuy-space-1);
  font-size: var(--fuy-font-size-3xl);
  font-weight: 700;
  line-height: 1.25;
  color: var(--fuy-color-text-emphasis);
}

.stat-delta {
  display: block;
  margin-top: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* ===== 主栏/侧栏双栏（样稿 .dash-grid 同构：主栏流式 + 侧栏 360px） ===== */
.dash-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
}

.dash-main {
  min-width: 0;
  padding: var(--fuy-space-5) var(--fuy-space-8) var(--fuy-space-3) var(--fuy-space-1);
  border-right: var(--fuy-border-hairline);
}

.dash-side {
  min-width: 0;
  padding: var(--fuy-space-5) var(--fuy-space-1) var(--fuy-space-3) var(--fuy-space-8);
  display: flex;
  flex-direction: column;
  gap: var(--fuy-space-5);
}

/* ===== 趋势图区（体温单语法：横参考规线 + 纵向淡格线 + 墨实线/灰虚线双序列） ===== */
.trend-head {
  display: flex;
  align-items: baseline;
  gap: var(--fuy-space-4);
}

.trend-title {
  margin: 0;
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
  letter-spacing: 0.5px;
  color: var(--fuy-color-text-emphasis);
}

/* 图例键：线形小样（形状第二通道——实线/虚线与序列线形一一对应，不依赖颜色单通道） */
.trend-legend {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.legend-key {
  width: 16px;
  border-top: 2px solid var(--fuy-color-brand);
}

.legend-key--dashed {
  border-top: 2px dashed var(--fuy-color-info-text);
}

.trend-note {
  margin-left: auto;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.trend-chart {
  display: block;
  width: 100%;
  height: auto;
  margin-top: var(--fuy-space-2);
}

/* SVG 线色全部取 token（体温单网格同源），零页面自造色值 */
.chart-rule {
  stroke: var(--fuy-chart-grid-color);
  stroke-width: 1;
}

.chart-ref {
  stroke: var(--fuy-color-rule-stone);
  stroke-width: 1;
  stroke-dasharray: 2 3;
}

.chart-line-main {
  fill: none;
  stroke: var(--fuy-color-brand);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.chart-line-comp {
  fill: none;
  stroke: var(--fuy-color-info-text);
  stroke-width: 1.5;
  stroke-dasharray: 5 4;
  stroke-linejoin: round;
}

.chart-dot {
  fill: var(--fuy-color-brand);
}

.chart-tick,
.chart-reflabel {
  font-size: 11px;
  fill: var(--fuy-chart-text-color);
  font-variant-numeric: tabular-nums;
}

.chart-val {
  font-size: 11px;
  font-weight: 600;
  fill: var(--fuy-color-text-emphasis);
  font-variant-numeric: tabular-nums;
}

/* ===== 候诊表（原生表直接坐纸上：密排 13px、纸色表头、行悬停暖底） ===== */
.waiting-sec {
  margin-top: var(--fuy-space-4);
  padding-top: var(--fuy-space-4);
  border-top: var(--fuy-border-panel);
}

.waiting-table {
  width: 100%;
  margin-top: var(--fuy-space-2);
  border-collapse: collapse;
  font-size: var(--fuy-font-size-sm);
}

.waiting-table th {
  padding: var(--fuy-space-1) var(--fuy-space-2);
  border-bottom: var(--fuy-border-panel);
  text-align: left;
  font-weight: 600;
  color: var(--fuy-color-text-secondary);
  background: var(--fuy-surface-card);
}

.waiting-table td {
  padding: var(--fuy-space-1) var(--fuy-space-2);
  border-bottom: var(--fuy-border-hairline);
  color: var(--fuy-color-text-emphasis);
}

/* 过渡定义在基态（双向 120ms）：悬停渐显、移出渐隐同节奏——单向定义会让移出硬切，
   与 .home-quick-link 的微反馈语义保持一致 */
.waiting-table tbody td {
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.waiting-table tbody tr:hover td {
  background: var(--fuy-palette-gray-50); /* 纸面悬停暖底（悬停微交互，仅底色过渡） */
}

.waiting-table th:last-child,
.waiting-table td:last-child {
  text-align: right;
}

.num-right {
  text-align: right;
}

/* ===== 事件流（流水时间列 + 来源·类型元信息 + 主题行；TransitionGroup fuy-flip 承载增删） ===== */
.stream-head-row {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

.live {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-info-text); /* idle/off：轮询或重连（灰=弱化不抢焦） */
}

.live--on {
  color: var(--fuy-color-success-text); /* 已连接：确认绿（状态唯一映射） */
}

.live--pending {
  color: var(--fuy-color-warning-text); /* 连接中：警示琥珀 */
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--fuy-radius-full);
  background: currentColor;
}

.stream-item {
  display: flex;
  gap: var(--fuy-space-3);
  padding: var(--fuy-space-2) 0;
  border-bottom: var(--fuy-border-hairline);
}

.stream-item:last-child {
  border-bottom: 0;
}

.stream-time {
  flex: none;
  width: 38px;
  padding-top: 1px;
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

.stream-body {
  min-width: 0;
}

.stream-meta {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
  font-size: var(--fuy-font-size-xs);
  font-weight: 500;
  color: var(--fuy-color-text-secondary);
}

/* 待支付/待配药：警示档琥珀承文（色仅第二通道，类型词表文字恒在场） */
.stream-meta--warning {
  color: var(--fuy-color-warning-text);
}

.stream-text {
  margin: var(--fuy-space-1) 0 0;
  font-size: var(--fuy-font-size-sm);
  line-height: 1.45;
  color: var(--fuy-color-text-emphasis);
  overflow-wrap: anywhere;
}

/* 危急值降级段：弱形态注记（缺位降级非危急，不得占用朱色语义） */
.critical-note {
  margin: var(--fuy-space-1) 0 var(--fuy-space-2);
  padding: var(--fuy-space-2) var(--fuy-space-3);
  border: var(--fuy-border-hairline);
  border-radius: var(--fuy-radius-md);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}

/* ===== 常用入口链接条（标签架语法，与批次 1 契约同构） ===== */
.home-quick-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: var(--fuy-space-2);
}

.home-quick-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 10px;
  border-radius: var(--fuy-radius-md);
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-sm);
  text-decoration: none;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.home-quick-link:hover {
  background: var(--fuy-palette-gray-50);
}

.home-quick-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: var(--fuy-radius-sm);
  background: rgba(30, 42, 68, 0.08); /* 淡墨一成底（样稿单字纸块同值） */
  color: var(--fuy-color-text-emphasis);
  font-size: 11px;
  font-weight: 600;
}

/* ===== 三态：诚实空态 / 错误态 / 骨架 ===== */

/* 行内诚实空态：石规虚缝弱形态（「这里本来什么都没有」） */
.home-inline-empty {
  margin: var(--fuy-space-3) 0 0;
  padding: var(--fuy-space-4);
  border: 1px dashed var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-lg);
  text-align: center;
  font-size: var(--fuy-font-size-sm);
  color: var(--fuy-color-text-secondary);
}

/* 错误态：说问题 + 给恢复（重试=浓墨实底主操作，「墨即操作」） */
.home-error {
  margin: var(--fuy-space-3) 0;
  padding: var(--fuy-space-6) var(--fuy-space-4);
  border: 1px dashed var(--fuy-color-rule-stone);
  border-radius: var(--fuy-radius-lg);
  text-align: center;
}

.home-error--side {
  margin: var(--fuy-space-2) 0 0;
  padding: var(--fuy-space-5) var(--fuy-space-4);
}

.home-error-title {
  margin: 0;
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}

.home-error-hint {
  margin: var(--fuy-space-2) auto 0;
  max-width: 46ch;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}

.home-retry {
  margin-top: var(--fuy-space-3);
  padding: var(--fuy-space-1) var(--fuy-space-4);
  border: 0;
  border-radius: var(--fuy-radius-md);
  background: var(--fuy-color-brand);
  color: #ffffff;
  font-size: var(--fuy-font-size-sm);
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.home-retry:hover:not(:disabled) {
  background: var(--fuy-palette-brand-200); /* 墨描边强调一档（token 既有值承悬停） */
}

.home-retry:active:not(:disabled) {
  background: var(--fuy-palette-brand-900); /* 按压书脊深墨 */
}

.home-retry:disabled {
  opacity: 0.6;
  cursor: default;
}

/* 骨架：静态墨洗纸块（无 shimmer 持续动画——纸世界静态占位 + min-height 锁 CLS） */
.sk {
  display: block;
  background: var(--fuy-palette-brand-100); /* 墨洗纸（选中态浅底同源，占位弱形态） */
  border-radius: var(--fuy-radius-sm);
}

.sk-label {
  width: 56px;
  height: 10px;
}

.sk-value {
  width: 76px;
  height: 24px;
  margin-top: var(--fuy-space-2);
}

.sk-delta {
  width: 64px;
  height: 10px;
  margin-top: var(--fuy-space-2);
}

.sk-trend {
  height: 200px;
  margin-top: var(--fuy-space-2);
}

.sk-table-row {
  height: 22px;
  margin-top: var(--fuy-space-2);
}

/* 主栏骨架容器（趋势区+候诊表区）min-height 锁 CLS：与内容态高度档对齐（1440 主流工作屏
   基线下真实内容约 460px——趋势 SVG 随容器宽等比 + 候诊表区固定档），骨架→内容切换不塌陷跳变 */
.main-skeleton {
  min-height: 460px;
}

.sk-time {
  width: 38px;
  height: 12px;
}

.sk-meta {
  width: 96px;
  height: 10px;
}

.sk-text {
  width: 180px;
  height: 14px;
  margin-top: var(--fuy-space-1);
}

/* ===== 页脚（快照时点注记：文书页脚语法，弱墨小字） ===== */
.home-dash-foot {
  padding: var(--fuy-space-3) var(--fuy-space-1) 0;
  border-top: var(--fuy-border-hairline);
  font-size: var(--fuy-font-size-xs);
  color: var(--fuy-color-text-secondary);
}
</style>
