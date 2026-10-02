<script setup lang="ts">
// 侧边菜单（PR-3 B3.4 骨架，批次 1 菜单数据化 + 高亮修复，§9.3.1/§9.3.2）：品牌头 + 分组菜单。
// 菜单源为组件内常量数组（一份常量承载分组渲染/折叠单字缩写/高亮计算三个消费面）；
// 权限过滤（BUG-14 守卫骨架）与会话权限点集联动：权限点集为空（数据源缺失）全量显示，
// 集非空时按路由登记的权限点过滤，角色驱动的细粒度菜单随 P1 鉴权接线演进。
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

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
  { index: '/nursing/execution', label: '护理执行工作台', abbr: '执', group: '护理管理' },
  { index: '/nursing/adverse-events', label: '不良事件上报', abbr: '报', group: '护理管理' },
  { index: '/pda', label: 'PDA 扫码', abbr: '扫', group: '护理管理' },
  { index: '/inpatient/admission', label: '入院登记台', abbr: '登', group: '住院管理' },
  { index: '/inpatient/beds', label: '病区床位图', abbr: '床', group: '住院管理' },
  { index: '/inpatient/station', label: '住院医生站', abbr: '住', group: '住院管理' },
  { index: '/inpatient/transfer', label: '转抄工作台', abbr: '抄', group: '住院管理' },
  { index: '/inpatient/discharge', label: '出院管理', abbr: '出', group: '住院管理' },
  { index: '/pharmacy/review', label: '住院审方台', abbr: '审', group: '药房管理' },
  // IoT 管理分组（M14 管理台四项 + M16 命令/联动/质量三页）
  { index: '/iot/products', label: '产品与物模型', abbr: '物', group: 'IoT 管理' },
  { index: '/iot/devices', label: '设备管理', abbr: '备', group: 'IoT 管理' },
  { index: '/iot/bindings', label: '设备绑定', abbr: '绑', group: 'IoT 管理' },
  { index: '/iot/alarm-rules', label: '告警规则', abbr: '警', group: 'IoT 管理' },
  { index: '/iot/commands', label: '命令中心', abbr: '令', group: 'IoT 管理' },
  { index: '/iot/linkage-rules', label: '联动规则', abbr: '联', group: 'IoT 管理' },
  { index: '/iot/quality', label: '质量看板', abbr: '质', group: 'IoT 管理' },
  // 病区视图分组（M16 病区视图三页：输液看板/呼叫工作台/冷链台账）
  { index: '/ward/infusion-board', label: '输液看板', abbr: '液', group: '病区视图' },
  { index: '/ward/call-workbench', label: '呼叫工作台', abbr: '呼', group: '病区视图' },
  { index: '/ward/cold-chain', label: '冷链台账', abbr: '冷', group: '病区视图' },
];

/** 折叠态由父布局下行（§9.3.2 父子直连 props 下行/事件上行，不引 provide/store） */
const { collapsed = false } = defineProps<{ collapsed?: boolean }>();

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

/** 权限过滤（BUG-14 守卫骨架，与守卫共用 hasRoutePermission 口径）：权限点集为空
 * （数据源缺失）全量显示；集非空时仅保留「未登记权限点」与「集内权限点」的菜单项 */
const visibleMenuItems = computed(() =>
  MENU_ITEMS.filter((item) => auth.hasRoutePermission(routePermissionOf(item.index))),
);

/** 顶层菜单项（group 空串），渲染在各分组之前 */
const ROOT_MENU_ITEMS = computed(() => visibleMenuItems.value.filter((item) => item.group === ''));

/** 分组菜单结构（按 MENU_ITEMS 中首次出现顺序）：分组名 → 组内可见菜单项列表；
 * 过滤后组内清空的分组整组剔除（防空标题组残留） */
const MENU_GROUPS = computed(() => {
  const visible = visibleMenuItems.value;
  return Array.from(new Set(visible.map((item) => item.group).filter((name) => name !== ''))).map(
    (name) => ({
      name,
      items: visible.filter((item) => item.group === name),
    }),
  );
});

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
