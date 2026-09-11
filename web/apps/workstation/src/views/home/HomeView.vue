<script setup lang="ts">
// 首页骨架（PR-5 B5.1，计划原文「首页骨架」的最小推导）：MainLayout 内容区内、面向已登录
// 用户的最小工作台语义视图——会话问候区（displayName/loginName 取既有 auth store）+ 业务
// 开通占位卡（文案与 AppSidebar 占位口径一致）。零新增依赖、零出网调用（P0 无首页数据接口，
// 引入调用即属推测性设计）；业务模块随 P1+ 逐步开通，禁预列模块卡片与伪数据。
import { computed } from 'vue';
import { useAuthStore } from '@/stores/auth';

const authStore = useAuthStore();

/** 问候主文案：displayName 空值兜底「未登录用户」——路由守卫已默认拒绝未登录，
 * 兜底仅防组件被直接挂载的防御场景 */
const displayName = computed(() => authStore.user?.displayName ?? '未登录用户');

/** 次行小字：登录名（空会话以 — 占位，不渲染裸空值） */
const loginName = computed(() => authStore.user?.loginName ?? '—');
</script>

<template>
  <section class="home-view">
    <h1>富云医护工作站</h1>

    <!-- 会话问候区 -->
    <section class="home-greeting">
      <p class="home-greeting-name">{{ displayName }}，欢迎回来</p>
      <p class="home-greeting-login">登录名：{{ loginName }}</p>
    </section>

    <!-- 业务开通占位区：与 AppSidebar 占位口径一致，模块卡片随各业务 PR 逐个落地 -->
    <section class="home-placeholder">
      <p>业务功能随各模块逐步开通，当前可经左侧菜单访问已开通功能。</p>
    </section>
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：最小工作台骨架样式，完整工作台设计随 P1 权限菜单演进 */
.home-view {
  padding: 16px;
}

.home-greeting {
  margin-top: 16px;
}

.home-greeting-name {
  font-size: 18px;
  font-weight: 600;
}

.home-greeting-login {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-top: 4px;
}

.home-placeholder {
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  color: var(--el-text-color-secondary);
  margin-top: 24px;
  max-width: 560px;
  padding: 24px;
}
</style>
