<script setup lang="ts">
// 工作站首页（「纸质病案」世界 · 删岗后口径，批次 2 册 1 契约 §4）：门牌页首（问候语 +
// 「登录名 · 日期」批注行，2px 墨规收底；岗位描边印已随岗位维度废除移除）+ 常用入口
// 紧凑链接条 + 诚实空态。报表式构图的指标带/趋势图/事件流属裁剪区——无真实 API 支撑
// 一律不落（零伪数据），接真实 API 后归位（批次 2+）。
// 入口数据来自菜单常量（../layout/menu.ts）的真实路由入口——同一份数据既驱动侧栏也驱动
// 本页链接条，零出网、零伪数据（无首页聚合 API，引入调用即属推测性设计）；过滤口径=
// 仅权限单道（auth.hasRoutePermission，与侧栏同口径；原「权限∩岗位双道」随岗位废除
// 收敛为权限单道）。首页自身不入链接条（当前页自引用无意义）。
import { computed } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { MENU_ITEMS, type SidebarMenuItem } from '../layout/menu';

const authStore = useAuthStore();
const router = useRouter();

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
 * 入口单字缩写（标签架语法保留）：菜单模型已不再携带缩写字段（批次 2 删岗随动清理），
 * 缩写取页名首字派生（装饰性重复字符，aria-hidden 承载，非正文书文）。
 * 已知首字重复为接受态（患×2/住×3/护×2/退×2/设×2，如患者建档/患者检索）：岗位 abbr
 * 废除后按首字派生的必然结果，属维持现状的认可留痕——缩写块为装饰性第二通道，名称
 * 文字并列在侧承载区分，功能无损（契约 §4「语法不变」）。
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
</script>

<template>
  <section class="fuy-page">
    <!-- 门牌页首：问候语 +「谁·何时」批注行（登录名 · 日期），页级 2px 墨规收底
         （样稿 .doc-head 同构；岗位描边印已随批次 2 删岗移除） -->
    <header class="home-doc-head">
      <h1 class="home-doc-title">{{ greeting }}，{{ displayName }}</h1>
      <span class="home-doc-note">
        登录名 {{ loginName }} · <time>{{ todayLabel }}</time>
      </span>
    </header>

    <!-- 常用入口：紧凑链接条（病案标签架语法，单字纸块缩写 + 名称，零卡片罗列），
         真实路由入口（RouterLink 直达），stagger 进场 -->
    <template v-if="entries.length > 0">
      <h2 class="home-sec-title">常用入口</h2>
      <nav class="home-quick-grid fuy-stagger" aria-label="常用入口">
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
    </template>

    <!-- 诚实空态：权限过滤后确无入口时的真实语义（非占位 lorem） -->
    <div v-else class="home-empty">
      <p class="home-empty-title">暂无可视入口</p>
      <p class="home-empty-hint">
        当前会话未被授予任何业务功能的访问权限，请联系管理员确认权限分配。
      </p>
    </div>
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：页面骨架（限宽/居中/内边距/纵向间距）由 .fuy-page 全局类
   承载，本块只留门牌页首/入口链接条/空态的私有样式；字号与间距全部 token 化 */

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

/* 小节题：灰墨疏排小字（样稿 .sec-title 同构），批注行与链接条之间的层级转场 */
.home-sec-title {
  margin: var(--fuy-space-2) var(--fuy-space-1) 0;
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  letter-spacing: 1px;
  color: var(--fuy-color-text-secondary);
}

/* 入口链接条：紧凑 flex 换行排布（非均质卡片网格），6/10px 缝距与样稿 .qn-grid 同值 */
.home-quick-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
}

/* 入口链接＝零描边零卡片：直接坐在纸上的标签架位，悬停以纸面暖底（#efece1）点亮 */
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

/* 单字纸块缩写：淡墨纸块（页名首字派生），单字居中 */
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
  font-size: 11px; /* 20px 纸块内的缩写字号（样稿同值，装饰性重复字符非正文书文） */
  font-weight: 600;
}

/* 诚实空态：石规虚缝弱形态（弱于业务面板），文案为真实权限语义 */
.home-empty {
  padding: var(--fuy-space-10) var(--fuy-space-4);
  border: 1px dashed var(--fuy-color-rule-stone); /* 石规色虚缝（颜色 token 单值引用；--fuy-border-panel 为整段 shorthand 不可嵌套） */
  border-radius: var(--fuy-radius-lg);
  text-align: center;
}

.home-empty-title {
  margin: 0;
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-md);
  font-weight: 600;
}

.home-empty-hint {
  margin: var(--fuy-space-2) 0 0;
  color: var(--fuy-color-text-secondary);
  font-size: var(--fuy-font-size-sm);
}
</style>
