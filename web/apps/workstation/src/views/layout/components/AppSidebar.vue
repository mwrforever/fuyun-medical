<script setup lang="ts">
// 侧边菜单（PR-3 B3.4 静态骨架）：首页入口 + 患者管理分组（M02 三页路由，详情页经检索行点击进入）；
// 权限驱动动态菜单随 P1 交付（依据会话角色渲染），P0 不接角色接口。
import { useRoute } from 'vue-router';

const route = useRoute();
</script>

<template>
  <el-menu class="app-sidebar" :default-active="route.path" router>
    <el-menu-item index="/">
      <span>首页</span>
    </el-menu-item>
    <el-menu-item-group title="患者管理">
      <!-- 菜单项随各业务模块 PR 逐个替换 -->
      <el-menu-item index="/patient/create">患者建档</el-menu-item>
      <el-menu-item index="/patients">患者检索</el-menu-item>
    </el-menu-item-group>
  </el-menu>
</template>

<style scoped>
/* 组件级样式隔离（web A.1-2）：菜单占满侧栏高度，无边框与侧栏分隔线重合 */
.app-sidebar {
  height: 100%;
  border-right: none;
}
</style>
