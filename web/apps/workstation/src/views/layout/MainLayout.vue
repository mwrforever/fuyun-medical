<script setup lang="ts">
// 主布局（PR-3 B3.4）：侧边菜单 + 顶栏 + 内容区三段（el-container 嵌套）。
// 只承载骨架与用户区，业务菜单与工作台内容随后续 PR 实装（权限驱动菜单归 P1）。
import { RouterView } from 'vue-router';
import AppHeader from './components/AppHeader.vue';
import AppSidebar from './components/AppSidebar.vue';
</script>

<template>
  <el-container class="main-layout">
    <el-aside class="main-aside" width="220px">
      <AppSidebar />
    </el-aside>
    <el-container class="main-body">
      <el-header class="main-header" height="56px">
        <AppHeader />
      </el-header>
      <el-main class="main-content">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* 布局级样式隔离（web A.1-2）：三段骨架尺寸，业务区留白随工作台设计演进 */
.main-layout {
  height: 100vh;
}

.main-aside {
  border-right: 1px solid var(--el-border-color-light);
}

.main-header {
  border-bottom: 1px solid var(--el-border-color-light);
}

.main-content {
  background: var(--el-fill-color-lighter);
}
</style>
