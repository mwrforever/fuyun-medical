<script setup lang="ts">
// 顶栏（PR-3 B3.4）：系统名 + 用户区下拉（显示名取会话 store + 退出登录命令）。
// 退出统一走 store.logout()：api 失败忽略 → 清 state 与 sessionStorage → 回登录页。
import { useAuthStore } from '@/stores/auth';

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
    <span class="app-header-title">富云医护工作站</span>
    <el-dropdown class="app-header-user" @command="handleCommand">
      <span class="app-header-user-name">{{ authStore.user?.displayName ?? '未登录' }}</span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：标题居左、用户区居右的两端布局 */
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
}

.app-header-title {
  font-size: 16px;
  font-weight: 600;
}

.app-header-user-name {
  color: var(--el-text-color-primary);
  cursor: pointer;
}
</style>
