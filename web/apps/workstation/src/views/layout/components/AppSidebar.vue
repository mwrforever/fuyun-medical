<script setup lang="ts">
// 侧边菜单（PR-3 B3.4 骨架，批次 1 菜单数据化 + 高亮修复，§9.3.1/§9.3.2）：品牌头 + 分组菜单。
// 菜单源为组件内常量数组（一份常量承载分组渲染/折叠单字缩写/高亮计算三个消费面）；
// 权限驱动动态菜单随 P1 交付（依据会话角色渲染），P0 不接角色接口。
import { computed } from 'vue';
import { useRoute } from 'vue-router';

/** 侧栏菜单项元数据：index=路由路径（兼作 el-menu index）、label=展开态全称、
 * abbr=折叠态单字缩写、group=分组名（空串=顶层项不入组） */
interface SidebarMenuItem {
  index: string;
  label: string;
  abbr: string;
  group: string;
}

/** 菜单常量（数组顺序即渲染序）：与 router 子路由清单一一对应，新增页面在此登记 */
const MENU_ITEMS: SidebarMenuItem[] = [
  { index: '/', label: '首页', abbr: '首', group: '' },
  { index: '/patient/create', label: '患者建档', abbr: '档', group: '患者管理' },
  { index: '/patients', label: '患者检索', abbr: '查', group: '患者管理' },
  { index: '/outpatient/registration-charge', label: '挂号收费', abbr: '挂', group: '门诊服务' },
  { index: '/outpatient/triage-board', label: '分诊台', abbr: '分', group: '门诊服务' },
  { index: '/outpatient/doctor-station', label: '门诊医生站', abbr: '医', group: '门诊服务' },
  { index: '/billing/pricing-settle', label: '划价结算', abbr: '价', group: '收费管理' },
  { index: '/billing/refunds', label: '退费审批', abbr: '退', group: '收费管理' },
  { index: '/billing/daily-list', label: '一日清单', abbr: '清', group: '收费管理' },
  { index: '/pharmacy/drug-dict', label: '药品字典', abbr: '药', group: '药房管理' },
  { index: '/pharmacy/dispense-workbench', label: '发药工作台', abbr: '发', group: '药房管理' },
  { index: '/pharmacy/dispense-return', label: '退药受理', abbr: '收', group: '药房管理' },
  { index: '/nursing/ward', label: '护士站', abbr: '护', group: '护理管理' },
  { index: '/pda', label: 'PDA 扫码', abbr: '扫', group: '护理管理' },
  { index: '/inpatient/admission', label: '入院登记台', abbr: '登', group: '住院管理' },
  { index: '/inpatient/beds', label: '病区床位图', abbr: '床', group: '住院管理' },
  { index: '/inpatient/station', label: '住院医生站', abbr: '住', group: '住院管理' },
  { index: '/inpatient/transfer', label: '转抄工作台', abbr: '抄', group: '住院管理' },
  { index: '/inpatient/discharge', label: '出院管理', abbr: '出', group: '住院管理' },
  { index: '/pharmacy/review', label: '住院审方台', abbr: '审', group: '药房管理' },
];

/** 顶层菜单项（group 空串），渲染在各分组之前 */
const ROOT_MENU_ITEMS = MENU_ITEMS.filter((item) => item.group === '');

/** 分组菜单结构（按 MENU_ITEMS 中首次出现顺序）：分组名 → 组内菜单项列表 */
const MENU_GROUPS = Array.from(
  new Set(MENU_ITEMS.map((item) => item.group).filter((name) => name !== '')),
).map((name) => ({
  name,
  items: MENU_ITEMS.filter((item) => item.group === name),
}));

/** 折叠态由父布局下行（§9.3.2 父子直连 props 下行/事件上行，不引 provide/store） */
const { collapsed = false } = defineProps<{ collapsed?: boolean }>();

const route = useRoute();

/** 当前高亮菜单 index（F-3 高亮修复）：路径与菜单 index 精确相等取之；否则取前缀最长
 * 匹配（如 /patients/P123 → /patients，患者详情不再整组失亮）；均无命中回落空串
 * （不高亮不误标）。'/' 排除在前缀匹配外，防任意路径都被首页前缀命中 */
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
    <!-- 品牌头（§9.3.1）：展开「富云」/折叠「富」单字共用一套字标形态 -->
    <div class="fuy-menu-head app-sidebar-head">
      <span class="fuy-menu-brand">{{ collapsed ? '富' : '富云' }}</span>
    </div>
    <!-- router 模式以 index 为目标路径导航；折叠瞬切：EP 内建宽度动画关闭（§6 铁律禁 width 动画）。
         菜单文案用文本节点而非 span：EP 折叠样式只隐藏顶层项直接子级 span，文本节点两态均可显示 -->
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
      <el-menu-item-group v-for="group in MENU_GROUPS" :key="group.name" :title="group.name">
        <el-menu-item v-for="item in group.items" :key="item.index" :index="item.index">
          {{ collapsed ? item.abbr : item.label }}
        </el-menu-item>
      </el-menu-item-group>
    </el-menu>
  </div>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：菜单态样式（项高/选中/分组标题）收编于全局 .fuy-menu 类
   （styles/element-plus.css，§9.2.2），本块只留组件私有布局 */
.app-sidebar {
  height: 100%;
}

/* 品牌头吸附侧栏顶缘：菜单超高随 aside 滚动时品牌区保持可见；背景取菜单底色防透字 */
.app-sidebar-head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--el-menu-bg-color);
}
</style>
