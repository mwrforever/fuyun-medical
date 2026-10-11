<!-- 图标注册表经普通 script 块导出：menu.ts 只持图标名字符串（数据层零组件依赖），
     组件解析收敛在本注册表，导出供 AppSidebar.spec 对照契约 §1 全量核验 -->
<script lang="ts">
import type { Component } from 'vue';
import {
  Bell,
  Box,
  CopyDocument,
  Cpu,
  DocumentAdd,
  DocumentChecked,
  EditPen,
  Files,
  Filter,
  Finished,
  FirstAidKit,
  Flag,
  FolderOpened,
  Grid,
  Guide,
  HomeFilled,
  Iphone,
  Key,
  List,
  MagicStick,
  Management,
  Memo,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  Paperclip,
  Phone,
  Pouring,
  PriceTag,
  Promotion,
  Reading,
  Refrigerator,
  Sell,
  Setting,
  ShoppingTrolley,
  Stamp,
  SuitcaseLine,
  TakeawayBox,
  Tickets,
  TrendCharts,
  Wallet,
  Warning,
} from '@element-plus/icons-vue';

/**
 * 侧栏图标注册表（图标名 → EP 图标组件）：批次 2 册 1 契约 §1 全 43 枚（9 组图标 +
 * 34 项图标）经 @element-plus/icons-vue 2.3.2 显式引入，禁 other 来源；组图标与项图标
 * 不重名（扫读双通道不混淆）。2026-10-12 D-29 九枚圆形笔画图标局部取代（User/
 * Search/Compass/Discount/Odometer/Connection/AlarmClock/Link/Service → FolderOpened/
 * Filter/Guide/Stamp/Management/Paperclip/Flag/MagicStick/Phone，出处=CHANGELOG 同日
 * D-29 条目）。名单外图标名渲染期降级为空图标，值域完整性由
 * menu.spec（数据侧）与 AppSidebar.spec（注册表侧）双测守护。
 */
export const MENU_ICON_REGISTRY: Record<string, Component> = {
  Bell,
  Box,
  CopyDocument,
  Cpu,
  DocumentAdd,
  DocumentChecked,
  EditPen,
  Files,
  Filter,
  Finished,
  FirstAidKit,
  Flag,
  FolderOpened,
  Grid,
  Guide,
  HomeFilled,
  Iphone,
  Key,
  List,
  MagicStick,
  Management,
  Memo,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  Paperclip,
  Phone,
  Pouring,
  PriceTag,
  Promotion,
  Reading,
  Refrigerator,
  Sell,
  Setting,
  ShoppingTrolley,
  Stamp,
  SuitcaseLine,
  TakeawayBox,
  Tickets,
  TrendCharts,
  Wallet,
  Warning,
};
</script>

<script setup lang="ts">
// 侧边菜单（「暖纸卷宗」棕墨书脊导轨 · 树形形态，批次 2 册 1 契约 §3，2026-10-10 暖纸
// 换血承继原「纸质病案」口径）：品牌头 +
// 树形图标菜单。分组=el-sub-menu 可折叠父节点（组图标+组名+展开箭头，默认全展开——
// 上班扫读第一优先，折叠是用户主动行为）；页项=el-menu-item 叶节点（项图标+全称，
// 40px 药丸选中语法——D-29① 2026-10-12 选中材质向金洗纸收拢，与收起态组弹层选中及
// 全站页级选中语义单一化）；收起态=el-menu :collapse 图标条（64px，组图标悬浮
// 弹出组内页项【EP 内建 popover】+ 顶层项 tooltip 全名，均瞬切零动画）。
// 动效语法（2026-10-09 琢段打磨，瞬切铁律仅限 240↔64 宽度切换与弹层）：树形进场级联
// （fuy-tree-stagger，仅初载一次，路由切换不重放）、组展开叶项显影（fuy-leaf-reveal-in）、
// 箭头旋转/按压沉底/收起态图标放大三态微交互（120ms 档，只动 transform/opacity）。
// 权限口径不变（BUG-14 守卫骨架 + PR-4D 空集语义）：经 hasRoutePermission 过滤路由
// meta 权限点（单一事实源），空权限会话=仅恒显项（首页），契约空集诚实语义。
import type { MenuInstance } from 'element-plus';
import { nextTick, onBeforeUnmount, onMounted, useTemplateRef } from 'vue';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { MENU_ITEMS, groupMenuItems } from '../menu';

/** 折叠态由父布局下行（父子直连 props 下行，不引 provide/store） */
const { collapsed = false } = defineProps<{
  /** 折叠态：true 收起为 64px 图标条（组名隐藏，组语义由组图标悬浮弹层承载） */
  collapsed?: boolean;
}>();

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

/** 顶层菜单项（group 空串），渲染在各分组之前 */
const ROOT_MENU_ITEMS = computed(() => visibleMenuItems.value.filter((item) => item.group === ''));

/** 分组菜单结构（首现序）：过滤后组内清空的分组整组剔除（防空标题组残留） */
const MENU_GROUPS = computed(() => groupMenuItems(visibleMenuItems.value));

/** 默认全展开：挂载时全部可见组纳入 default-openeds（契约 §3 树形形态——多组同开，
 * unique-opened 保持 EP 默认 false；default-openeds 仅挂载期生效，会话权限在布局挂载
 * 前已就绪，无异步再过滤场景） */
const defaultOpeneds = computed(() => MENU_GROUPS.value.map((group) => group.name));

/** 当前高亮菜单 index（F-3 高亮修复）：路径与菜单 index 精确相等取之；否则取前缀最长
 * 匹配（如 /patients/P123 → /patients，患者详情不再整组失亮）；均无命中回落空串
 * （不高亮不误标）。'/' 排除在前缀匹配外，防任意路径都被首页前缀命中。
 * 全量常量参与匹配：菜单项被权限过滤隐藏时高亮仍正确（该项本就不在渲染列） */
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

// ===== 键盘可达（W-105② 修正，2026-10-12，roving tabindex 方案）=====
// EP 菜单交互模型缺口：全部 .el-menu-item 硬渲染 tabindex=-1、组头 .el-sub-menu__title 无
// tabindex、容器 ul 不可聚焦，且 EP 2.14.5 垂直菜单无方向键导航——Tab 无法进入侧栏（原状
// Tab 连按 25 次不进导轨）。此处以 WAI-ARIA roving tabindex 模式补齐：焦点序列内恰一枚
// tabindex=0（Tab 入口，锚点=路由激活项，无激活回落首项），ArrowUp/ArrowDown/Home/End 在
// 序列内移动 focus（首尾循环），Enter/Space 激活（叶项=路由导航、展开态组头=切换组开合、
// 收起态组头=开组弹层并聚焦首叶项），Escape 关收起态组弹层并返焦来源组头。
// 按键监听挂 document 级：收起态组弹层 DOM teleported 至 body，组件根 @keydown 触达不了
// 弹层内按键，双挂两处反而分裂逻辑——单 document 监听统一导轨与弹层两个序列。

/** ElMenu 组件实例（EP 官方 expose 的 open/close 通道：收起态键盘开/关组弹层，绕过 hover
 * 300ms 迟滞与 focus 早退分支） */
const menu = useTemplateRef<MenuInstance>('menu');

/** 导轨根元素（焦点序列收集与包含判定的 DOM 边界） */
const railRoot = useTemplateRef<HTMLElement>('railRoot');

/** Escape 返焦目标：键盘开收起态组弹层时记录来源组头；鼠标 hover 开的弹层无来源为 null */
let popperReturnTarget: HTMLElement | null = null;

/** 导轨 DOM 变更守护（MutationObserver）：组开合（is-opened 类）、路由激活（is-active 类）、
 * 折叠态切换（el-menu--collapse 类）与权限过滤重渲染（子树增删）后重算 roving 锚点 */
let railObserver: MutationObserver | null = null;

/** 重算去重闸：同一批 DOM 变更只排一次微任务（nextTick 后 Vue 补丁完毕再取稳定 DOM） */
let applyQueued = false;

/**
 * 导轨内元素可见性判定（结构化口径，jsdom 无布局亦可测）：内联叶项随组开合 v-show 显隐，
 * is-opened 类与 v-show 绑定同一 opened 计算值（EP sub-menu render 实证）天然同步。
 *
 * @param el 导轨内菜单项或组头元素
 * @return true 可见（参与焦点序列）；false 随折叠组隐藏
 */
function isVisibleInRail(el: Element): boolean {
  const inlineList = el.closest('ul.el-menu--inline');
  if (inlineList === null) {
    return true; // 根项与组头恒在导轨内
  }
  return inlineList.closest('li.el-sub-menu')?.classList.contains('is-opened') === true;
}

/**
 * 收集焦点所在上下文的焦点序列（DOM 序即视觉序）：目标在收起态组弹层内=该弹层叶项序列
 * （弹层长在 body，与导轨序列互斥）；在导轨内=根项+组头+已展开组叶项。
 *
 * @param target 按键/聚焦目标元素（菜单项或组头）
 * @return 焦点序列；空数组=无可聚焦目标（调用方守卫）
 */
function focusSequenceOf(target: Element): HTMLElement[] {
  const popper = target.closest('.fuy-tree-popper');
  if (popper !== null) {
    return [...popper.querySelectorAll('li.el-menu-item')].filter(
      (el): el is HTMLElement => el instanceof HTMLElement,
    );
  }
  const rail = railRoot.value;
  if (rail === null) {
    return [];
  }
  return [...rail.querySelectorAll('li.el-menu-item, .el-sub-menu__title')].filter(
    (el): el is HTMLElement => el instanceof HTMLElement && isVisibleInRail(el),
  );
}

/** 施加 roving tabindex：导轨内锚点项（路由激活叶项优先，无激活回落首项）置 0、其余 -1；
 * EP 以 vnode 属性渲染 tabindex=-1，本侧 DOM 属性覆写不会被 EP 重渲染回滚（vnode prop 未
 * 变更时 Vue 不回写 DOM）——组弹层叶项的 roving 由进入弹层时的聚焦逻辑就地施加 */
function applyRovingTabindex(): void {
  const rail = railRoot.value;
  if (rail === null) {
    return;
  }
  const sequence = focusSequenceOf(rail);
  if (sequence.length === 0) {
    return;
  }
  const anchor = Math.max(
    0,
    sequence.findIndex((el) => el.matches('li.el-menu-item.is-active')),
  );
  sequence.forEach((el, position) => {
    el.tabIndex = position === anchor ? 0 : -1;
  });
}

/** 导轨变更后排程重算（微任务去重）：MutationObserver 同步回调期 Vue 补丁未必收尾 */
function queueApplyRoving(): void {
  if (applyQueued) {
    return;
  }
  applyQueued = true;
  void nextTick(() => {
    applyQueued = false;
    applyRovingTabindex();
  });
}

/** 序列内移动焦点并同步 roving：目标项置 0 其余 -1，focus() 触发浏览器滚动随动 */
function moveFocus(sequence: HTMLElement[], next: number): void {
  if (sequence.length === 0) {
    return;
  }
  const target = sequence[(next + sequence.length) % sequence.length];
  sequence.forEach((el) => {
    el.tabIndex = el === target ? 0 : -1;
  });
  target.focus();
}

/** 按叶项文案匹配定位组数据（弹层 DOM 不承载组名/路由 index）：叶项 label 全站唯一
 * （menu.spec 全量断言守护），长度+逐位相等双校验防部分误配 */
function groupOfLeafLabels(labels: string[]) {
  return MENU_GROUPS.value.find(
    (group) =>
      group.items.length === labels.length &&
      group.items.every((item, position) => labels[position] === item.label),
  );
}

/** 键盘开收起态组弹层：经 ElMenu expose open() 直开（无 hover 迟滞），弹层内容 teleported
 * 至 body 需轮询等待挂载（20 次 ×16ms 预算覆盖 EP popper 渲染时序），挂载后弹层叶项就地
 * roving 并聚焦首项（返焦目标=来源组头，供 Escape 回程） */
function openCollapsedPopper(titleEl: HTMLElement): void {
  const groupLi = titleEl.closest('li.el-sub-menu');
  const rail = railRoot.value;
  if (groupLi === null || rail === null) {
    return;
  }
  // 组名不在 DOM：导轨组 li 序与 MENU_GROUPS 序一一对应（模板 v-for 同源）
  const groupLiList = [...rail.querySelectorAll('li.el-sub-menu')];
  const group = MENU_GROUPS.value[groupLiList.indexOf(groupLi)];
  if (group === undefined) {
    return;
  }
  popperReturnTarget = titleEl;
  menu.value?.open(group.name);
  let attempts = 0;
  const pollPopper = (): void => {
    const labels = group.items.map((item) => item.label);
    const popper = [...document.querySelectorAll('.fuy-tree-popper ul.el-menu--popup')]
      .find((list) => {
        // 可见性前置：EP persistent 弹层关闭态仍挂 DOM——隐藏的是外层 .el-popper（display:
        // none），内层 ul 自身 computed display 恒 block 检不出折叠（实测 9 组常驻）；用
        // getClientRects（祖先 display:none 折叠时为 0，显形为 1）判定真正可焦
        if (list.getClientRects().length === 0) {
          return false;
        }
        const popperLabels = [...list.querySelectorAll('li.el-menu-item')].map((item) =>
          (item.textContent ?? '').trim(),
        );
        return (
          popperLabels.length === labels.length &&
          labels.every((label, position) => popperLabels[position] === label)
        );
      })
      ?.closest('.fuy-tree-popper');
    const leaves = [...(popper?.querySelectorAll('li.el-menu-item') ?? [])].filter(
      (el): el is HTMLElement => el instanceof HTMLElement,
    );
    if (leaves.length > 0) {
      leaves.forEach((el, position) => {
        el.tabIndex = position === 0 ? 0 : -1;
      });
      leaves[0]?.focus();
      return;
    }
    attempts += 1;
    if (attempts < 20) {
      setTimeout(pollPopper, 16);
    }
  };
  pollPopper();
}

/** 激活目标：叶项/展开态组头派发原生 click（EP onClick 承载路由导航与组开合切换）；
 * 收起态组头的 click 被 EP 折叠分支早退忽略，改走 open() 通道开弹层 */
function activateTarget(target: HTMLElement): void {
  if (target.matches('.el-sub-menu__title') && collapsed) {
    openCollapsedPopper(target);
    return;
  }
  target.click();
}

/** Escape 回程：关焦点所在收起态组弹层并返焦来源组头（无记录时按弹层叶项文案反查导轨组头） */
function closePopperAndReturn(target: HTMLElement): void {
  const popper = target.closest('.fuy-tree-popper');
  const rail = railRoot.value;
  if (popper === null || rail === null) {
    return;
  }
  const labels = [...popper.querySelectorAll('li.el-menu-item')].map((el) =>
    (el.textContent ?? '').trim(),
  );
  const group = groupOfLeafLabels(labels);
  if (group === undefined) {
    return;
  }
  menu.value?.close(group.name);
  const returnTarget = popperReturnTarget;
  if (returnTarget !== null && rail.contains(returnTarget)) {
    returnTarget.focus();
    return;
  }
  // 鼠标 hover 开的弹层无键盘来源：按组序反查导轨组头聚焦
  const groupIndex = MENU_GROUPS.value.indexOf(group);
  const groupTitles = [...rail.querySelectorAll('.el-sub-menu__title')].filter(
    (el): el is HTMLElement => el instanceof HTMLElement,
  );
  groupTitles[groupIndex]?.focus();
}

/**
 * 键盘导航统一入口（document 级 keydown）：方向键/Home/End 移动、Enter/Space 激活、
 * Escape 关收起态组弹层；带 Ctrl/Alt/Meta 修饰键不拦截（浏览器快捷键照常）。
 *
 * @param event document 级键盘事件（目标限本侧栏导轨与组弹层内的菜单项/组头）
 */
function onDocumentKeydown(event: KeyboardEvent): void {
  if (event.ctrlKey || event.altKey || event.metaKey) {
    return;
  }
  const target = event.target;
  if (!(target instanceof HTMLElement)) {
    return;
  }
  if (!target.matches('li.el-menu-item, .el-sub-menu__title')) {
    return;
  }
  const rail = railRoot.value;
  const inPopper = target.closest('.fuy-tree-popper') !== null;
  if (rail === null || !(inPopper || rail.contains(target))) {
    return;
  }
  const sequence = focusSequenceOf(target);
  const position = sequence.indexOf(target);
  if (position < 0) {
    return;
  }
  switch (event.key) {
    case 'ArrowDown':
    case 'ArrowUp': {
      event.preventDefault();
      moveFocus(sequence, position + (event.key === 'ArrowDown' ? 1 : -1));
      break;
    }
    case 'Home': {
      event.preventDefault();
      moveFocus(sequence, 0);
      break;
    }
    case 'End': {
      event.preventDefault();
      moveFocus(sequence, sequence.length - 1);
      break;
    }
    case 'Enter':
    case ' ': {
      event.preventDefault();
      activateTarget(target);
      break;
    }
    case 'Escape': {
      if (inPopper) {
        event.preventDefault();
        closePopperAndReturn(target);
      }
      break;
    }
    default:
      break;
  }
}

/** 焦点随动（focusin，鼠标点击同样生效）：实际聚焦项即 roving 锚点——Tab 离开侧栏再回来
 * 落在最后聚焦项（用户心理模型），不弹回首项 */
function onDocumentFocusIn(event: FocusEvent): void {
  const target = event.target;
  if (!(target instanceof HTMLElement)) {
    return;
  }
  if (!target.matches('li.el-menu-item, .el-sub-menu__title')) {
    return;
  }
  const sequence = focusSequenceOf(target);
  if (sequence.includes(target)) {
    sequence.forEach((el) => {
      el.tabIndex = el === target ? 0 : -1;
    });
  }
}

onMounted(() => {
  document.addEventListener('keydown', onDocumentKeydown);
  document.addEventListener('focusin', onDocumentFocusIn);
  // 挂载即施加初始 roving（菜单 v-for 渲染完毕后取稳定 DOM）
  void nextTick(applyRovingTabindex);
  const rail = railRoot.value;
  if (rail !== null) {
    railObserver = new MutationObserver(queueApplyRoving);
    railObserver.observe(rail, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['class'],
    });
  }
});

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDocumentKeydown);
  document.removeEventListener('focusin', onDocumentFocusIn);
  railObserver?.disconnect();
  railObserver = null;
  popperReturnTarget = null;
});
</script>

<template>
  <!-- railRoot：键盘可达（W-105②）导轨 DOM 边界 ref——roving tabindex 序列收集/包含判定 -->
  <div ref="railRoot" class="app-sidebar">
    <!-- 品牌头（与顶栏同高对齐）：亮纸白字标「富云」/折叠「富」单字共用一套字标形态
         （is-fold 折叠字距补偿见 element-plus.css .fuy-menu-brand.is-fold；头容器同步挂
         is-fold 承载 D-29② 对齐轴切换——展开态左对齐菜单文本轴、折叠态「富」印块
         居中于 64px 轨，规则落 element-plus.css .fuy-menu-head 段） -->
    <div class="fuy-menu-head app-sidebar-head" :class="{ 'is-fold': collapsed }">
      <span class="fuy-menu-brand" :class="{ 'is-fold': collapsed }">{{
        collapsed ? '富' : '富云'
      }}</span>
    </div>
    <!-- 树形菜单：router 模式以页项 index 为目标路径导航（分组 index=组名仅作展开锚不参与
         导航）；收起态瞬切=EP 折叠宽度动画关闭（铁律禁 width 动画）；popper-effect=light
         使收起态 tooltip/组弹层落亮纸白卡面（纸墨世界）；popper-class 挂 .fuy-tree-popper
         供弹层瞬切与纸面收编（样式收编于 element-plus.css .fuy-tree-popper 段，弹层
         teleported 至 body）。fuy-tree-stagger=树形进场级联（motion.css 唯一定义处）：
         行内联 --fuy-stagger-index 排级联序（品牌头 0 → 顶层项 1 → 组块自 2 递增，5 档
         封顶在 CSS min() 执行），仅初载播放一次，路由切换组件不重挂不重放 -->
    <div class="app-sidebar-menu">
      <!-- menu ref：键盘可达（W-105②）收起态组弹层开/关走 EP expose open/close 通道 -->
      <el-menu
        ref="menu"
        class="fuy-menu fuy-tree-stagger"
        :default-active="activeIndex"
        :default-openeds="defaultOpeneds"
        :collapse="collapsed"
        :collapse-transition="false"
        popper-effect="light"
        popper-class="fuy-tree-popper"
        router
      >
        <!-- 顶层项（首页）：#title 承全称——展开态内联渲染、收起态转 EP 内建 tooltip 全名 -->
        <el-menu-item
          v-for="item in ROOT_MENU_ITEMS"
          :key="item.index"
          class="fuy-tree-root"
          :index="item.index"
          :style="{ '--fuy-stagger-index': 1 }"
        >
          <el-icon><component :is="MENU_ICON_REGISTRY[item.icon]" /></el-icon>
          <template #title>{{ item.label }}</template>
        </el-menu-item>
        <!-- 分组父节点：组图标+组名（span 供 EP 收起态隐藏规则命中）+展开箭头（EP 内建）；
             键盘可达随 el-sub-menu 内建 aria（aria-expanded/aria-haspopup）；级联序=组块
             原始序自 2 递增（超 5 档并发由 motion.css min() 封顶） -->
        <el-sub-menu
          v-for="(group, groupIndex) in MENU_GROUPS"
          :key="group.name"
          :index="group.name"
          :style="{ '--fuy-stagger-index': 2 + groupIndex }"
        >
          <template #title>
            <el-icon><component :is="MENU_ICON_REGISTRY[group.icon]" /></el-icon>
            <span>{{ group.name }}</span>
          </template>
          <!-- 叶项级联序=组内序（0 起）：供叶项展开显影动画的 delay 取值（motion.css
               fuy-leaf-reveal-in，2 档封顶），非进场 stagger 通道 -->
          <el-menu-item
            v-for="(item, leafIndex) in group.items"
            :key="item.index"
            :index="item.index"
            :style="{ '--fuy-stagger-index': leafIndex }"
          >
            <el-icon><component :is="MENU_ICON_REGISTRY[item.icon]" /></el-icon>
            <template #title>{{ item.label }}</template>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </div>
  </div>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：菜单态样式（项高/字号/选中/分组标题）收编于全局 .fuy-menu 类
   （styles/element-plus.css），本块只留导轨私有布局与树形扫读节奏的增量打磨；
   EP 深层节点经 :deep() 以 .fuy-menu 挂类通道覆盖（特异性恒压 EP，非裸改 .el-*） */
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

/* 品牌字标=树形进场级联第 0 档（首拍锚点，后续 顶层项 1 → 组块自 2 递增）：消费
   motion.css 全局 fuy-rise（组件内零 keyframes 定义，唯一来源铁律），backwards fill
   终态零残留 transform；仅初载播一次（组件挂载一次），路由切换不重放 */
.app-sidebar-head .fuy-menu-brand {
  animation: fuy-rise var(--fuy-motion-slow) var(--fuy-ease-enter) backwards;
}

/* 菜单滚动容器：滚动收敛在此层，品牌头吸附不随内容滚走 */
.app-sidebar-menu {
  padding-bottom: var(--fuy-space-4);
}

/* ===== 树形扫读节奏（样稿 .tree 系同构，批次 2 册 1 契约 §3 树形形态）===== */

/* 组内叶项高度对齐契约 40px：EP 把 --el-menu-sub-item-height 在 :root 以
   calc(var(--el-menu-item-height) - 6px) 于 :root 作用域求值（56px-6px=50px）后整值继承，
   element-plus.css .fuy-menu 段对 --el-menu-item-height 的覆盖无法传导至此，须显式同值
   覆盖（变量名经 EP el-menu.css 验证在位；共享层并行冻结，故落本组件挂类段） */
.fuy-menu {
  --el-menu-sub-item-height: 40px;
}

/* 图标统一 16px 线性形态（样稿 .tree-ico 同值）：el-icon 以 1em 缩放 svg，容器级字号
   即图标尺寸；右侧 8px 呼吸与样稿 gap 9/10px 同档；transform 120ms 过渡供收起态
   悬停放大微交互（下方 rail 段）承载 */
.fuy-menu :deep(.el-icon) {
  margin-right: var(--fuy-space-2);
  font-size: 16px;
  transition: transform var(--fuy-motion-fast) var(--fuy-ease-standard);
}

/* 分组叶项展开显影（motion.css fuy-leaf-reveal-in 唯一定义处）：组展开/侧栏自图标条
   展开时新揭示叶项自组头下方 4px 沉降显影，delay=组内序内联 --fuy-stagger-index、
   min() 2 档封顶（≤80ms，节奏起伏而响应不迟滞）；折叠离场随 v-show 直切零动画；
   backwards fill 终态零残留，:active 按压 transform 不被 animation 级联占位 */
.fuy-menu :deep(.el-menu--inline .el-menu-item) {
  animation: fuy-leaf-reveal-in var(--fuy-motion-base) var(--fuy-ease-enter) backwards;
  animation-delay: calc(min(var(--fuy-stagger-index, 0), 2) * var(--fuy-motion-stagger));
}

/* 组头图标 17px 微占层级优势（样稿 .tree-ico--group 17px 对叶项 16px）：组与项的扫读
   双通道除图标语义不重名外，再以 1px 尺寸差拉开父子层级 */
.fuy-menu :deep(.el-sub-menu__title > .el-icon) {
  font-size: 17px;
}

/* 分组父节点（组头）：40px 高与页项同拍，药丸收边同语法（左右 8px 收边 + 2px 直角）；
   组名 14px/500 纸纱（样稿 .tree-group-head 同款，父节点以字重与缩进区分叶项而非字号）；
   过渡含 transform 供按压沉底微交互（与页项同拍） */
.fuy-menu :deep(.el-sub-menu__title) {
  display: flex;
  align-items: center;
  margin: 0 var(--fuy-space-2);
  border-radius: var(--fuy-radius-sm);
  font-weight: 500;
  transition:
    background-color var(--fuy-motion-fast) var(--fuy-ease-standard),
    transform var(--fuy-motion-fast) var(--fuy-ease-standard);
}

/* 按压态（触觉反馈第三态，与悬停/焦点构成三态全覆盖）：行/组头按下沉 1px——纸上的行
   被指尖按下落一档的触感，只动 transform（合成器路径）；悬停/按压/聚焦三态 120ms
   同档，节奏统一不散灶。页项过渡在 element-plus.css 仅 background-color（共享层并行
   冻结），此处挂类通道补 transform 一项（特异性恒压，非裸改 .el-*） */
.fuy-menu :deep(.el-menu-item) {
  transition:
    background-color var(--fuy-motion-fast) var(--fuy-ease-standard),
    transform var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.fuy-menu :deep(.el-menu-item:active),
.fuy-menu :deep(.el-sub-menu__title:active) {
  transform: translateY(1px);
}

/* 展开箭头：靠右缘（margin-left:auto），弱化 55% 不抢组名；旋转/增亮 120ms（2026-10-09
   放宽口径：组展开折叠节奏仅限 transform/opacity 红线——箭头旋转是「组态翻转」的语义
   动效，EP 以内联 rotateZ(180deg) 翻转、此处只供过渡节奏；height 折叠动画保持关闭） */
.fuy-menu :deep(.el-sub-menu__icon-arrow) {
  margin-right: 0;
  margin-left: auto;
  font-size: 12px;
  opacity: 0.55;
  transition:
    transform var(--fuy-motion-fast) var(--fuy-ease-standard),
    opacity var(--fuy-motion-fast) var(--fuy-ease-standard);
}

/* 组头悬停时箭头增亮：悬停反馈不止底色，可动件（箭头）同步点亮提示可按 */
.fuy-menu :deep(.el-sub-menu__title:hover .el-sub-menu__icon-arrow) {
  opacity: 0.9;
}

/* 组内展开/折叠瞬切（契约铁律）：EP 内联子菜单走 ElCollapseTransition（max-height 过渡），
   挂类压制其过渡类=属性直跳终态（.fuy-snap-popper 压 el-zoom-in-top 同款手法） */
.fuy-menu :deep(.el-collapse-transition-enter-active),
.fuy-menu :deep(.el-collapse-transition-leave-active) {
  transition: none;
}

/* 含选中叶项的分组父节点：EP 默认改 active-color=浓墨，在墨脊底上不可读——组头恒持
   纸纱字（选中语义由叶项药丸独占，形状即选中通道） */
.fuy-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--fuy-shell-text);
}

/* 顶层项（首页）与组头的下缘分组呼吸：样稿 .tree-leaf--top margin-bottom 8px 同源 */
.fuy-menu :deep(.fuy-tree-root) {
  margin-bottom: var(--fuy-space-2);
}

/* 导轨上键盘焦点环＝最亮纸白环（--fuy-surface-input）：即墨环在墨脊底上不可见，焦点
   永远可见是底线。W-105 死规则收口（2026-10-12）：EP 以 outline:none 简写压死全局
   :focus-visible，仅写 outline-color 单属性环不渲染，须完整 outline 简写恢复（全局层
   element-plus.css .fuy-menu 焦点段已同款收口，此处 scoped 双保险跟随简写口径） */
.fuy-menu :deep(.el-menu-item:focus-visible),
.fuy-menu :deep(.el-sub-menu__title:focus-visible) {
  outline: 2px solid var(--fuy-surface-input);
  outline-offset: -2px;
}

/* 收起态（64px 图标条）：组头图标严格居中（与 element-plus.css 顶层项居中规则同语法，
   双保险 EP 版本差异防回归） */
.fuy-menu.el-menu--collapse :deep(.el-sub-menu__title) {
  padding: 0;
  justify-content: center;
}

/* 收起态选中项药丸压缩为图标块高亮（D-29① 材质随 .is-active 金洗纸承继，与组弹层
   选中同语义）：顶层项收边由 8px 收至 16px，药丸宽度收敛为 64-16×2=32px 图标块
   （静态 margin 不属动画，禁 width 铁律不受影响） */
.fuy-menu.el-menu--collapse :deep(.el-menu-item.is-active) {
  margin-right: var(--fuy-space-4);
  margin-left: var(--fuy-space-4);
}

/* 收起态：EP 顶层项内层 tooltip trigger 自带 0 20px 内边距（绝对定位铺满项体，
   EP el-menu.css .el-menu-item .el-menu-tooltip__trigger 实证），不清零则图标右移出
   选中药丸 9px（实测 svg 48..64 vs 药丸 16..47），药丸退化为无语义白块；组件级
   .el-icon 8px 右距同特异性后注入压过 EP 收起态 margin:0，图标整体左偏 4px——
   一并归零恢复图标严格居中于药丸（挂类+:deep() 通道，非裸改 .el-*） */
.fuy-menu.el-menu--collapse :deep(.el-menu-tooltip__trigger) {
  padding: 0;
  justify-content: center;
}
.fuy-menu.el-menu--collapse :deep(.el-menu-item .el-icon),
.fuy-menu.el-menu--collapse :deep(.el-sub-menu__title .el-icon) {
  margin-right: 0;
}

/* 收起态图标条悬停放大（图标条上图标即全部可供性，hover 放大 1.1 提供除底色外的第二
   重悬停反馈）：只动 transform（scale 合成器路径，120ms 同档），展开态树形菜单不放此
   微交互（行内有全称文字，图标放大反而破坏基线对齐） */
.fuy-menu.el-menu--collapse :deep(.el-menu-item:hover > .el-icon),
.fuy-menu.el-menu--collapse :deep(.el-sub-menu__title:hover > .el-icon) {
  transform: scale(1.1);
}
</style>
