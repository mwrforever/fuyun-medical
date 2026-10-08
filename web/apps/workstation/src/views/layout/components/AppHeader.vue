<script setup lang="ts">
// 顶栏（「纸质病案」亮纸白顶条）：折叠按钮 + 系统名 + 全局患者检索入口 + 用户区下拉。
// 折叠按钮仅发 toggle 事件上行（状态翻转在 MainLayout）；系统名「富云医护工作站」为
// App.spec 冒烟测试文本锚点，禁改名。患者检索入口跳转既有 /patients 路由（真实路由
// 入口零新增出网），可见性随会话权限过滤（与侧栏同口径）。退出统一走 store.logout()：
// api 失败忽略 → 清 state 与 sessionStorage → 回登录页。
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

/** 折叠态由父布局下行，仅驱动按钮的无障碍标签文案与汉堡图形语义 */
const { collapsed = false } = defineProps<{ collapsed?: boolean }>();

/** 折叠开关事件：负载为空，状态翻转归 MainLayout（单一持有者） */
const emit = defineEmits<{ toggle: [] }>();

const router = useRouter();
const authStore = useAuthStore();

/** 患者检索入口可见性：权限语义只在路由 meta（单一事实源），经 hasRoutePermission
 * 与侧栏同口径过滤——无权限会话不渲染（与守卫「已登记且集不含=拒绝」一致） */
const canSearchPatient = computed(() =>
  authStore.hasRoutePermission(router.resolve('/patients').meta.permission),
);

/** 患者检索入口点击：跳转既有检索路由，失败（导航中断）静默无业务影响 */
function openPatientSearch(): void {
  void router.push('/patients');
}

/**
 * 下拉命令处理：command 仅承载"退出登录"一个命令（修改口令等入口随 P1 用户管理接入）。
 *
 * @param command Element Plus 下拉命令值（来源：模板内 command 属性字面量）
 */
function handleCommand(command: string | number | object): void {
  if (command === 'logout') {
    void authStore.logout();
  }
}
</script>

<template>
  <div class="app-header">
    <div class="app-header-left">
      <!-- 折叠按钮：纯 CSS 汉堡（三条横线 span 叠放），零图标依赖；32×32 点击域满足
           无障碍最小点击尺寸，aria-label 随折叠态动态描述动作 -->
      <button
        type="button"
        class="app-header-toggle"
        :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="emit('toggle')"
      >
        <span class="app-header-toggle-line"></span>
        <span class="app-header-toggle-line"></span>
        <span class="app-header-toggle-line"></span>
      </button>
      <!-- 全局患者检索入口：纯文字（零图标），无权限会话随权限过滤不渲染；归右组容器 -->
      <span class="app-header-title">富云医护工作站</span>
    </div>
    <!-- 右组容器（margin-left: auto 收拢右侧动作区，样稿 .top-right 同构）：
         患者检索文字钮 + 用户下拉 -->
    <div class="app-header-right">
      <button
        v-if="canSearchPatient"
        type="button"
        class="app-header-search"
        aria-label="全局患者检索"
        @click="openPatientSearch"
      >
        患者检索
      </button>
      <!-- popper-class 挂弹层硬 snap 过渡（motion.css .fuy-snap-popper 唯一定义处） -->
      <el-dropdown class="app-header-user" popper-class="fuy-snap-popper" @command="handleCommand">
        <span class="app-header-user-name">
          {{ authStore.user?.displayName ?? '未登录' }}
          <!-- 下拉指示箭头：纯 CSS 三角（零字符图标、零图标依赖），aria-hidden 防读屏播报 -->
          <span class="app-header-user-caret" aria-hidden="true"></span>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：亮纸白顶条左「折叠按钮+站点名」右「检索+用户区」的
   两端布局；底色与描边由 MainLayout .main-header 承载，本块不重复声明 */
.app-header {
  display: flex;
  align-items: center;
  height: 100%;
}

.app-header-left {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 右组容器：检索 + 用户区收拢至顶条右缘（顶条是亮纸白压条，右侧动作与其左缘呼吸分开） */
.app-header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 折叠按钮本体：透明底无边框，悬停/聚焦以纸面暖底反馈（背景色 120ms 过渡，单元素状态反馈） */
.app-header-toggle {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px; /* 三条 2px 线 + 2×5px 间距 = 16px 图形高度 */
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: var(--fuy-radius-md);
  background: transparent;
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.app-header-toggle:hover {
  background: var(--fuy-palette-gray-50);
}

/* 患者检索入口：亮纸白顶条语法与折叠开关同族（透明底 + 纸面暖底悬停 + 焦点环），纯文字
   零图标；32px 高满足工作站触控底线，灰墨承文悬停转浓墨（色彩不承载唯一语义） */
.app-header-search {
  height: 32px;
  padding: 0 var(--fuy-space-3);
  border: none;
  border-radius: var(--fuy-radius-md);
  background: transparent;
  color: var(--fuy-color-text-secondary);
  font-family: inherit;
  font-size: var(--fuy-font-size-sm);
  cursor: pointer;
  transition:
    background-color var(--fuy-motion-fast) var(--fuy-ease-standard),
    color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.app-header-search:hover {
  background: var(--fuy-palette-gray-50);
  color: var(--fuy-color-text-emphasis);
}

/* 汉堡三条横线：2px×16px，墨字色呈现 */
.app-header-toggle-line {
  display: block;
  width: 16px;
  height: 2px;
  border-radius: 1px;
  background: var(--el-text-color-primary);
}

/* 站点名「富云医护工作站」：App.spec 冒烟测试文本锚点，禁改名；纸面浓墨 600 字重 */
.app-header-title {
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
  color: var(--fuy-color-text-emphasis);
}

/* 用户区点击域：40px 高（≥最小点击尺寸），悬停纸面暖底 120ms */
.app-header-user-name {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  height: 40px;
  padding: 0 var(--fuy-space-2);
  border-radius: var(--fuy-radius-md);
  color: var(--fuy-color-text-emphasis);
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.app-header-user-name:hover {
  background: var(--fuy-palette-gray-50);
}

/* 下拉指示箭头：纯 CSS 三角（border 绘制，随文字色承墨），替代旧字符图标 */
.app-header-user-caret {
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid currentColor;
  opacity: 0.55;
}
</style>
