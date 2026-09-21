<script setup lang="ts">
// 顶栏（PR-3 B3.4，批次 1 升级 §9.3.1 布局树）：折叠按钮 + 系统名 + 用户区下拉。
// 折叠按钮仅发 toggle 事件上行（状态翻转在 MainLayout）；系统名「富云医护工作站」为
// App.spec 冒烟测试文本锚点，禁改名。退出统一走 store.logout()：api 失败忽略 → 清 state
// 与 sessionStorage → 回登录页。
import { useAuthStore } from '@/stores/auth';

/** 折叠态由父布局下行，仅驱动按钮的无障碍标签文案与汉堡图形语义 */
const { collapsed = false } = defineProps<{ collapsed?: boolean }>();

/** 折叠开关事件：负载为空，状态翻转归 MainLayout（单一持有者） */
const emit = defineEmits<{ toggle: [] }>();

const authStore = useAuthStore();

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
      <span class="app-header-title">富云医护工作站</span>
    </div>
    <el-dropdown class="app-header-user" @command="handleCommand">
      <span class="app-header-user-name">
        {{ authStore.user?.displayName ?? '未登录' }}
        <span class="app-header-user-caret" aria-hidden="true">▾</span>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：左「折叠按钮+站点名」右「用户区」的两端布局 */
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
}

.app-header-left {
  display: flex;
  align-items: center;
  gap: var(--fuy-space-2);
}

/* 折叠按钮本体：透明底无边框，悬停/聚焦以浅底反馈（背景色 120ms 过渡，单元素状态反馈） */
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
  background: var(--fuy-palette-brand-50);
}

/* 汉堡三条横线：2px×16px，主文本色呈现 */
.app-header-toggle-line {
  display: block;
  width: 16px;
  height: 2px;
  border-radius: 1px;
  background: var(--el-text-color-primary);
}

.app-header-title {
  font-size: var(--fuy-font-size-lg);
  font-weight: 600;
}

/* 用户区点击域：40px 高（≥最小点击尺寸），悬停底 brand-50 120ms（§9.3.1 布局树） */
.app-header-user-name {
  display: inline-flex;
  align-items: center;
  gap: var(--fuy-space-1);
  height: 40px;
  padding: 0 var(--fuy-space-2);
  border-radius: var(--fuy-radius-md);
  color: var(--el-text-color-primary);
  cursor: pointer;
  transition: background-color var(--fuy-motion-fast) var(--fuy-ease-standard);
}

.app-header-user-name:hover {
  background: var(--fuy-palette-brand-50);
}

/* 下拉指示箭头：纯文本字符承载（零图标依赖），aria-hidden 防读屏重复播报 */
.app-header-user-caret {
  font-size: var(--fuy-font-size-xs);
  color: var(--el-text-color-secondary);
}
</style>
