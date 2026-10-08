<!-- 图标注册表经普通 script 块导出：menu.ts 只持图标名字符串（数据层零组件依赖），
     组件解析收敛在本注册表，导出供 AppSidebar.spec 对照契约 §1 全量核验 -->
<script lang="ts">
import type { Component } from 'vue';
import {
  AlarmClock,
  Bell,
  Box,
  Compass,
  Connection,
  CopyDocument,
  Cpu,
  Discount,
  DocumentAdd,
  DocumentChecked,
  EditPen,
  Files,
  Finished,
  FirstAidKit,
  Grid,
  HomeFilled,
  Iphone,
  Key,
  Link,
  List,
  Memo,
  Monitor,
  Notebook,
  OfficeBuilding,
  Odometer,
  Operation,
  Pouring,
  PriceTag,
  Promotion,
  Reading,
  Refrigerator,
  Search,
  Sell,
  Service,
  Setting,
  ShoppingTrolley,
  SuitcaseLine,
  TakeawayBox,
  Tickets,
  TrendCharts,
  User,
  Wallet,
  Warning,
} from '@element-plus/icons-vue';

/**
 * 侧栏图标注册表（图标名 → EP 图标组件）：批次 2 册 1 契约 §1 全 43 枚（9 组图标 +
 * 34 项图标）经 @element-plus/icons-vue 2.3.2 显式引入，禁 other 来源；组图标与项图标
 * 不重名（扫读双通道不混淆）。名单外图标名渲染期降级为空图标，值域完整性由
 * menu.spec（数据侧）与 AppSidebar.spec（注册表侧）双测守护。
 */
export const MENU_ICON_REGISTRY: Record<string, Component> = {
  AlarmClock,
  Bell,
  Box,
  Compass,
  Connection,
  CopyDocument,
  Cpu,
  Discount,
  DocumentAdd,
  DocumentChecked,
  EditPen,
  Files,
  Finished,
  FirstAidKit,
  Grid,
  HomeFilled,
  Iphone,
  Key,
  Link,
  List,
  Memo,
  Monitor,
  Notebook,
  OfficeBuilding,
  Odometer,
  Operation,
  Pouring,
  PriceTag,
  Promotion,
  Reading,
  Refrigerator,
  Search,
  Sell,
  Service,
  Setting,
  ShoppingTrolley,
  SuitcaseLine,
  TakeawayBox,
  Tickets,
  TrendCharts,
  User,
  Wallet,
  Warning,
};
</script>

<script setup lang="ts">
// 侧边菜单（「纸质病案」墨脊书脊导轨 · 树形形态，批次 2 册 1 契约 §3）：品牌头 +
// 树形图标菜单。分组=el-sub-menu 可折叠父节点（组图标+组名+展开箭头，默认全展开——
// 上班扫读第一优先，折叠是用户主动行为）；页项=el-menu-item 叶节点（项图标+全称，
// 40px 高亮纸白药丸选中语法延续）；收起态=el-menu :collapse 图标条（64px，组图标悬浮
// 弹出组内页项【EP 内建 popover】+ 顶层项 tooltip 全名，均瞬切零动画）。
// 权限口径不变（BUG-14 守卫骨架 + PR-4D 空集语义）：经 hasRoutePermission 过滤路由
// meta 权限点（单一事实源），空权限会话=仅恒显项（首页），契约空集诚实语义。
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
    <!-- 树形菜单：router 模式以页项 index 为目标路径导航（分组 index=组名仅作展开锚不参与
         导航）；收起态瞬切=EP 折叠宽度动画关闭（铁律禁 width 动画）；popper-effect=light
         使收起态 tooltip/组弹层落亮纸白卡面（纸墨世界）；popper-class 挂 .fuy-tree-popper
         供弹层瞬切与纸面收编（样式收编于 element-plus.css .fuy-tree-popper 段，弹层
         teleported 至 body） -->
    <div class="app-sidebar-menu">
      <el-menu
        class="fuy-menu"
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
        >
          <el-icon><component :is="MENU_ICON_REGISTRY[item.icon]" /></el-icon>
          <template #title>{{ item.label }}</template>
        </el-menu-item>
        <!-- 分组父节点：组图标+组名（span 供 EP 收起态隐藏规则命中）+展开箭头（EP 内建）；
             键盘可达随 el-sub-menu 内建 aria（aria-expanded/aria-haspopup） -->
        <el-sub-menu v-for="group in MENU_GROUPS" :key="group.name" :index="group.name">
          <template #title>
            <el-icon><component :is="MENU_ICON_REGISTRY[group.icon]" /></el-icon>
            <span>{{ group.name }}</span>
          </template>
          <el-menu-item v-for="item in group.items" :key="item.index" :index="item.index">
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
   即图标尺寸；右侧 8px 呼吸与样稿 gap 9/10px 同档 */
.fuy-menu :deep(.el-icon) {
  margin-right: var(--fuy-space-2);
  font-size: 16px;
}

/* 组头图标 17px 微占层级优势（样稿 .tree-ico--group 17px 对叶项 16px）：组与项的扫读
   双通道除图标语义不重名外，再以 1px 尺寸差拉开父子层级 */
.fuy-menu :deep(.el-sub-menu__title > .el-icon) {
  font-size: 17px;
}

/* 分组父节点（组头）：40px 高与页项同拍，药丸收边同语法（左右 8px 收边 + 2px 直角）；
   组名 14px/500 纸纱（样稿 .tree-group-head 同款，父节点以字重与缩进区分叶项而非字号） */
.fuy-menu :deep(.el-sub-menu__title) {
  display: flex;
  align-items: center;
  margin: 0 var(--fuy-space-2);
  border-radius: var(--fuy-radius-sm);
  font-weight: 500;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

/* 展开箭头：靠右缘（margin-left:auto），弱化 55% 不抢组名，旋转瞬切（契约铁律：组展开
   折叠零动画——EP 内建 .3s transform 过渡一并关闭） */
.fuy-menu :deep(.el-sub-menu__icon-arrow) {
  margin-right: 0;
  margin-left: auto;
  font-size: 12px;
  opacity: 0.55;
  transition: none;
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

/* 导轨上键盘焦点环改白色：即墨环在墨脊底上不可见，焦点永远可见是底线 */
.fuy-menu :deep(.el-menu-item:focus-visible),
.fuy-menu :deep(.el-sub-menu__title:focus-visible) {
  outline-color: #ffffff;
}

/* 收起态（64px 图标条）：组头图标严格居中（与 element-plus.css 顶层项居中规则同语法，
   双保险 EP 版本差异防回归） */
.fuy-menu.el-menu--collapse :deep(.el-sub-menu__title) {
  padding: 0;
  justify-content: center;
}

/* 收起态选中项药丸压缩为图标块高亮（样稿 32px 亮纸白块同构）：顶层项收边由 8px 收至
   16px，药丸宽度收敛为 64-16×2=32px 图标块（静态 margin 不属动画，禁 width 铁律不受影响） */
.fuy-menu.el-menu--collapse :deep(.el-menu-item.is-active) {
  margin-right: var(--fuy-space-4);
  margin-left: var(--fuy-space-4);
}
</style>
