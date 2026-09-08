<script setup lang="ts">
// 应用根组件：仅承载路由出口（页面文案由各视图承载，站点中文名为冒烟测试锚点）
import { RouterView } from 'vue-router';
</script>

<template>
  <div class="app-shell">
    <RouterView />
  </div>
</template>
