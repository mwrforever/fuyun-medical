<script setup lang="ts">
// 岗位工作台首页（「纸质病案」世界）：门牌页首（问候 + 岗位描边印 + 批注行，2px 墨规收底）
// + 常用入口紧凑链接条 + 诚实空态。报表式构图的指标带/趋势图/事件流属裁剪区——无真实
// API 支撑一律不落（零伪数据），接真实 API 后归位（批次 2+）。
// 入口数据来自菜单常量（../layout/menu.ts）的真实路由入口——同一份数据既驱动侧栏也驱动
// 本页链接条，零出网、零伪数据（无首页聚合 API，引入调用即属推测性设计）；过滤 = 权限
// （auth.hasRoutePermission，与侧栏同口径）∩ 岗位（POST_SELECTION_KEY 注入值，由
// MainLayout provide）。首页自身不入链接条（当前页自引用无意义）。
import { computed, inject } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import {
  MENU_ITEMS,
  POST_OPTIONS,
  POST_SELECTION_KEY,
  selectMenuItemsForPost,
  type PostSelection,
  type SidebarMenuItem,
} from '../layout/menu';

const authStore = useAuthStore();
const router = useRouter();

/**
 * 当前岗位（布局壳下行，只读）：独立挂载（如单测直挂）无 provide 时兜底「全部」，
 * 防御语义与侧栏 props 默认值一致。
 */
const postState = inject(POST_SELECTION_KEY, undefined);
const post = computed<PostSelection>(() => postState?.value ?? 'all');

/** 岗位展示名（批注行同源标签；未知键兜底「全部」防裸渲染 undefined） */
const postLabel = computed(
  () => POST_OPTIONS.find((option) => option.key === post.value)?.label ?? '全部',
);

/** 当前岗位单字（岗位描边印取字；未知键兜底「全」，与侧栏折叠 chip 同源同兜底） */
const postAbbr = computed(
  () => POST_OPTIONS.find((option) => option.key === post.value)?.abbr ?? '全',
);

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
 * 当前岗位高频入口：权限过滤 ∩ 岗位过滤，再剔除首页自身（当前页自引用无意义）；
 * 顺序沿菜单常量稳定渲染序。
 */
const entries = computed<SidebarMenuItem[]>(() =>
  selectMenuItemsForPost(MENU_ITEMS, post.value)
    .filter((item) => item.index !== '/')
    .filter((item) => authStore.hasRoutePermission(routePermissionOf(item.index))),
);

/** 进场级联序（≤5 封顶，第 6 项起并发，与 motion.css stagger 约定一致） */
function staggerIndex(index: number): number {
  return Math.min(index, 5);
}
</script>

<template>
  <section class="fuy-page">
    <!-- 门牌页首：问候语 + 岗位描边印（墨印承身份，危急才用朱印）+「谁·何时」批注行，
         页级 2px 墨规收底（样稿 .doc-head 同构） -->
    <header class="home-doc-head">
      <h1 class="home-doc-title">{{ greeting }}，{{ displayName }}</h1>
      <span class="home-post-stamp" aria-hidden="true">{{ postAbbr }}</span>
      <span class="home-doc-note">
        当前岗位：{{ postLabel }} · 登录名 {{ loginName }} · <time>{{ todayLabel }}</time>
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
          <span class="home-quick-abbr" aria-hidden="true">{{ item.abbr }}</span>
          <span class="home-quick-link-label">{{ item.label }}</span>
        </RouterLink>
      </nav>
    </template>

    <!-- 诚实空态：该岗位经权限∩岗位过滤后确无入口时的真实语义（非占位 lorem） -->
    <div v-else class="home-empty">
      <p class="home-empty-title">该岗位暂无可视入口</p>
      <p class="home-empty-hint">
        当前会话未被授予任何{{ postLabel }}业务功能的访问权限，请联系管理员确认权限分配。
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

/* 岗位描边印：墨印承身份标记（描边印章语法，样稿 .stamp 同形）——朱印只承危急状态，
   岗位是身份不是危急，故用墨字描边印 */
.home-post-stamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  min-width: 22px;
  height: 22px;
  padding: 0 4px;
  border: 1.5px solid var(--fuy-color-text-emphasis);
  border-radius: var(--fuy-radius-sm);
  color: var(--fuy-color-text-emphasis);
  font-size: var(--fuy-font-size-xs);
  font-weight: 600;
  line-height: 1;
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

/* 单字纸块缩写：淡墨纸块（沿用文字缩写体系，零图标库依赖），单字居中 */
.home-quick-abbr {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: var(--fuy-radius-sm);
  background: rgba(30, 42, 68, 0.08); /* 淡墨一成底（样稿 .abbr 同值） */
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
