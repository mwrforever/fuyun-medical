<script setup lang="ts">
// 侧边菜单（「纸质病案」墨脊书脊导轨）：品牌头 + 岗位切换器 + 按岗位过滤的业务域分组菜单。
// 菜单源与岗位模型为 ../menu.ts 常量（一份常量承载侧栏渲染/折叠缩写/首页入口网格三个
// 消费面）；权限过滤（BUG-14 守卫骨架 + PR-4D 空集语义收紧）与会话权限点集联动：仅保留
// 「未登记权限点」与「集内权限点」的菜单项（空集=无任何业务权限，除未登记项外全部隐藏）；
// 岗位过滤在其上叠加（纯函数 selectMenuItemsForPost），两道过滤口径互不替代。
import { computed, nextTick, useTemplateRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import {
  MENU_ITEMS,
  POST_OPTIONS,
  groupMenuItems,
  selectMenuItemsForPost,
  type PostSelection,
} from '../menu';

/** 折叠态/当前岗位由父布局下行（父子直连 props 下行/事件上行，不引 provide/store） */
const { collapsed = false, post = 'all' } = defineProps<{
  /** 折叠态：true 收起为 64px 图标条（切换器随组标题一并隐藏） */
  collapsed?: boolean;
  /** 当前岗位选择：过滤菜单展示维度，翻转权在 MainLayout */
  post?: PostSelection;
}>();

/** 岗位切换事件：负载为目标岗位值，状态翻转归 MainLayout（单一持有者） */
const emit = defineEmits<{ change: [post: PostSelection] }>();

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

/**
 * 按菜单 index 反查路由权限点：路由 = 权限点清单（web B.3-2），权限语义只登记在路由
 * meta（单一事实源），菜单不重复登记权限编码防两处漂移。
 *
 * @param index 菜单项路由路径（与路由表 path 一一对应）
 * @return 路由登记的权限点编码；undefined = 该路由未登记权限语义（恒可见）
 */
function routePermissionOf(index: string): string | undefined {
  return router.resolve(index).meta.permission;
}

/** 权限过滤（BUG-14 骨架 + PR-4D 收紧，与守卫共用 hasRoutePermission 口径）：仅保留
 * 「未登记权限点」与「集内权限点」的菜单项；空集会话=无任何业务权限，仅剩未登记项 */
const visibleMenuItems = computed(() =>
  MENU_ITEMS.filter((item) => auth.hasRoutePermission(routePermissionOf(item.index))),
);

/** 岗位过滤（在权限过滤之上叠加）：具体岗位仅渲染恒显项与归属该项的菜单 */
const postMenuItems = computed(() => selectMenuItemsForPost(visibleMenuItems.value, post));

/** 顶层菜单项（group 空串），渲染在各分组之前 */
const ROOT_MENU_ITEMS = computed(() => postMenuItems.value.filter((item) => item.group === ''));

/** 分组菜单结构（首现序）：过滤后组内清空的分组整组剔除（防空标题组残留） */
const MENU_GROUPS = computed(() => groupMenuItems(postMenuItems.value));

/** 当前岗位展示名（折叠态 chip 的 aria-label 全名通道；未知键兜底「全部」防裸 undefined） */
const postLabel = computed(
  () => POST_OPTIONS.find((option) => option.key === post)?.label ?? '全部',
);

/** 折叠态循环切换的下一岗位（沿 POST_OPTIONS 数组序循环，设备科之后回「全部」） */
const nextPost = computed<PostSelection>(() => {
  const index = POST_OPTIONS.findIndex((option) => option.key === post);
  return POST_OPTIONS[(index + 1) % POST_OPTIONS.length]?.key ?? 'all';
});

/** 折叠态单 chip 呈现的岗位单字（未知键兜底「全」） */
const postAbbr = computed(() => POST_OPTIONS.find((option) => option.key === post)?.abbr ?? '全');

/** 当前高亮菜单 index（F-3 高亮修复）：路径与菜单 index 精确相等取之；否则取前缀最长
 * 匹配（如 /patients/P123 → /patients，患者详情不再整组失亮）；均无命中回落空串
 * （不高亮不误标）。'/' 排除在前缀匹配外，防任意路径都被首页前缀命中。
 * 全量常量参与匹配：菜单项被岗位过滤隐藏时高亮仍正确（该项本就不在渲染列） */
const activeIndex = computed<string>(() => {
  const exact = MENU_ITEMS.find((item) => item.index === route.path);
  if (exact !== undefined) {
    return exact.index;
  }
  const prefixed = MENU_ITEMS.filter(
    (item) => item.index !== '/' && route.path.startsWith(item.index),
  );
  if (prefixed.length > 0) {
    // 多枚前缀同时命中时取路径重叠最长（最具体）的一枚
    return prefixed.reduce((longest, item) =>
      item.index.length > longest.index.length ? item : longest,
    ).index;
  }
  return '';
});

/** 菜单滚动容器模板引用：FLIP 位置捕获与回流锁定都在其上做 */
const menuWrap = useTemplateRef<HTMLElement>('menuWrap');

/**
 * 捕获当前各菜单组的纵向位置（FLIP First 帧）：组名 → 视口纵坐标。
 *
 * @return 组名到纵坐标的映射；无菜单容器时为空映射（跳过动效）
 */
function captureGroupTops(): Map<string, number> {
  const wrap = menuWrap.value;
  const tops = new Map<string, number>();
  if (wrap === null) {
    return tops;
  }
  for (const el of wrap.querySelectorAll<HTMLElement>('[data-post-flip]')) {
    const name = el.dataset.postFlip;
    if (name !== undefined) {
      tops.set(name, el.getBoundingClientRect().top);
    }
  }
  return tops;
}

/**
 * 岗位换装签名动效（FLIP Last + 级联归位）：按更新前位置反演 transform 起始态，强制
 * 回流后逐组级联归位（仅 transform，步长沿 --fuy-motion-stagger，铁律合规）。新出现的
 * 组无原位不参与；零尺寸环境（jsdom）位移恒 0 自动跳过；过渡结束清理内联样式防残留。
 *
 * @param before 更新前捕获的组位置（First 帧映射）
 */
function playGroupsFlip(before: Map<string, number>): void {
  const wrap = menuWrap.value;
  if (wrap === null || before.size === 0) {
    return;
  }
  const moved: HTMLElement[] = [];
  for (const el of wrap.querySelectorAll<HTMLElement>('[data-post-flip]')) {
    const prevTop = before.get(el.dataset.postFlip ?? '');
    if (prevTop === undefined) {
      continue;
    }
    const delta = prevTop - el.getBoundingClientRect().top;
    if (Math.abs(delta) < 1) {
      continue; // 位移不足 1px 不值得一次合成层提交
    }
    el.style.transition = 'none';
    el.style.transform = `translateY(${delta}px)`;
    moved.push(el);
  }
  if (moved.length === 0) {
    return;
  }
  // 强制回流锁定起始态，后续过渡才能从反演位置出发（经典 FLIP 手法）
  void wrap.offsetHeight;
  moved.forEach((el, order) => {
    el.style.transition = `transform var(--fuy-motion-slow) var(--fuy-ease-standard) calc(var(--fuy-motion-stagger) * ${order})`;
    el.style.transform = '';
    el.addEventListener(
      'transitionend',
      () => {
        el.style.transition = '';
      },
      { once: true },
    );
  });
}

// 岗位换装触发点：watch 默认 pre-flush 在 DOM 更新前捕获旧位（First），更新后反演播放；
// 解构 prop 须经 getter 追踪（响应式解构编译为 props.post 读取）
watch(
  () => post,
  () => {
    const before = captureGroupTops();
    void nextTick(() => playGroupsFlip(before));
  },
);
</script>

<template>
  <div class="app-sidebar">
    <!-- 品牌头（与顶栏同高对齐）：亮纸白字标「富云」/折叠「富」单字共用一套字标形态
         （is-fold 折叠字距补偿见 element-plus.css .fuy-menu-brand.is-fold） -->
    <div class="fuy-menu-head app-sidebar-head">
      <span class="fuy-menu-brand" :class="{ 'is-fold': collapsed }">{{
        collapsed ? '富' : '富云'
      }}</span>
    </div>
    <!-- 岗位切换器（六枚 chip 三列网格）：aria-pressed 表达选中态；点击仅事件上行，
         过滤与翻转归 MainLayout。展开态六枚网格；折叠态 64px 容不下网格，降级为当前
         岗位单 chip（亮纸白药丸恒亮形态），点击沿选项序循环切换保留岗位上下文 -->
    <nav v-if="!collapsed" class="app-sidebar-posts" aria-label="岗位切换">
      <button
        v-for="option in POST_OPTIONS"
        :key="option.key"
        type="button"
        class="app-sidebar-chip"
        :class="{ 'is-active': post === option.key }"
        :aria-pressed="post === option.key"
        @click="emit('change', option.key)"
      >
        {{ option.label }}
      </button>
    </nav>
    <div v-else class="app-sidebar-posts-collapse">
      <button
        type="button"
        class="app-sidebar-chip app-sidebar-chip--fold"
        :aria-label="`切换岗位，当前${postLabel}`"
        @click="emit('change', nextPost)"
      >
        {{ postAbbr }}
      </button>
    </div>
    <!-- router 模式以 index 为目标路径导航；折叠瞬切：EP 内建宽度动画关闭（铁律禁 width 动画）。
         菜单文案用文本节点而非 span：EP 折叠样式只隐藏顶层项直接子级 span，文本节点两态均可显示 -->
    <div ref="menuWrap" class="app-sidebar-menu">
      <el-menu
        class="fuy-menu"
        :default-active="activeIndex"
        :collapse="collapsed"
        :collapse-transition="false"
        router
      >
        <el-menu-item v-for="item in ROOT_MENU_ITEMS" :key="item.index" :index="item.index">
          {{ collapsed ? item.abbr : item.label }}
        </el-menu-item>
        <!-- data-post-flip：岗位换装 FLIP 的分组锚点（fallthrough 落在组件根 li 上） -->
        <el-menu-item-group
          v-for="group in MENU_GROUPS"
          :key="group.name"
          :title="group.name"
          :data-post-flip="group.name"
        >
          <el-menu-item v-for="item in group.items" :key="item.index" :index="item.index">
            {{ collapsed ? item.abbr : item.label }}
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </div>
  </div>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：菜单态样式（项高/选中/分组标题）收编于全局 .fuy-menu 类
   （styles/element-plus.css），本块只留导轨私有布局（品牌头吸附/切换器网格/滚动容器） */
.app-sidebar {
  height: 100%;
}

/* 品牌头吸附导轨顶缘：菜单超高随 aside 滚动时品牌区保持可见；底色取书脊墨防透字 */
.app-sidebar-head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--fuy-shell-bg);
}

/* 岗位切换器：品牌头正下方吸附（长菜单滚动时换岗常驻可见），三列网格 28px chip
   （≥24px 高密度触控底线），下缘一道书脊深墨缝与菜单区分隔 */
.app-sidebar-posts {
  position: sticky;
  top: 56px;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--fuy-space-1);
  padding: var(--fuy-space-2) var(--fuy-space-3) var(--fuy-space-3);
  background: var(--fuy-shell-bg);
  border-bottom: 1px solid var(--fuy-shell-hairline);
}

/* 岗位 chip：透明底纸纱字，选中反白为亮纸白药丸（形状即选中通道，不依赖色觉单通道） */
.app-sidebar-chip {
  height: 28px;
  padding: 0;
  border: none;
  border-radius: var(--fuy-radius-sm);
  background: transparent;
  color: var(--fuy-shell-text);
  font-family: inherit;
  font-size: var(--fuy-font-size-xs);
  font-weight: 500;
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.app-sidebar-chip:hover {
  background: var(--fuy-shell-hover);
}

/* 选中 chip＝亮纸白底 + 墨字（墨脊书脊上的胸牌），字重加重补足第三通道 */
.app-sidebar-chip.is-active {
  background: var(--fuy-surface-card);
  color: var(--fuy-color-text-emphasis);
  font-weight: 600;
}

/* 导轨上键盘焦点环改白色：即墨环在墨脊底上不可见，焦点永远可见是底线 */
.app-sidebar-chip:focus-visible {
  outline-color: #ffffff;
}

/* 折叠态岗位 chip 容器：品牌头正下居中，下缘书脊深墨缝与菜单区分隔 */
.app-sidebar-posts-collapse {
  display: flex;
  justify-content: center;
  padding: var(--fuy-space-2) 0 var(--fuy-space-3);
  background: var(--fuy-shell-bg);
  border-bottom: 1px solid var(--fuy-shell-hairline);
}

/* 折叠态岗位 chip：亮纸白药丸恒亮形态（与展开态选中 chip 同语法），单字承当前岗位，
   点击循环切换；32×28 满足触控底线，悬停纸面暖底反馈 */
.app-sidebar-chip--fold {
  width: 32px;
  background: var(--fuy-surface-card);
  color: var(--fuy-color-text-emphasis);
  font-weight: 600;
}

.app-sidebar-chip--fold:hover {
  background: var(--fuy-palette-gray-50);
}

/* 菜单滚动容器：滚动收敛在此层，品牌头与切换器吸附不随内容滚走 */
.app-sidebar-menu {
  padding-bottom: var(--fuy-space-4);
}
</style>
