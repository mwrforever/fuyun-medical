<script setup lang="ts">
// 首页骨架（PR-5 B5.1 最小推导，批次 1 升级 §9.3.3）：MainLayout 内容区内、面向已登录
// 用户的最小工作台语义视图——会话问候区（displayName/loginName 取既有 auth store）+ 业务
// 开通占位卡（文案与 AppSidebar 占位口径一致）。零新增依赖、零出网调用（P0 无首页数据接口，
// 引入调用即属推测性设计）；业务模块随 P1+ 逐步开通，禁预列模块卡片与伪数据。
// 站点名 h1 已删除：与 AppHeader 站点名重复，「医护工作站」冒烟锚点由 AppHeader 承载（§9.3.3）。
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
  <section class="fuy-page">
    <!-- 会话问候区：问候语升为页面题（--fuy-font-size-xl/600，§9.3.3） -->
    <el-card>
      <p class="home-greeting-name">{{ displayName }}，欢迎回来</p>
      <p class="home-greeting-login">登录名：{{ loginName }}</p>
    </el-card>

    <!-- 业务开通占位区：与 AppSidebar 占位口径一致，模块卡片随各业务 PR 逐个落地 -->
    <el-card class="home-placeholder">
      <p>业务功能随各模块逐步开通，当前可经左侧菜单访问已开通功能。</p>
    </el-card>
  </section>
</template>

<style scoped>
/* 视图级样式隔离（web A.1-2）：页面骨架（限宽/居中/内边距/纵向间距）由 .fuy-page 全局类承载，
   本块只留问候与占位两卡的私有样式；字号全部 token 化（§9.3.3） */
.home-greeting-name {
  margin: 0;
  font-size: var(--fuy-font-size-xl);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.home-greeting-login {
  margin: var(--fuy-space-1) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--fuy-font-size-sm);
}

/* 占位卡保持虚线弱形态（§9.3.3）：el-card 描边虚线化 + space-4 内边距，弱于正式业务卡 */
.home-placeholder {
  border: 1px dashed var(--el-border-color);
}

.home-placeholder :deep(.el-card__body) {
  padding: var(--fuy-space-4);
}

.home-placeholder p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--fuy-font-size-md);
}
</style>
