<script setup lang="ts">
// 主布局（PR-3 B3.4 骨架，批次 1 升级为设计文档 §9.3.1 布局树）：侧边菜单 + 顶栏 + 内容区三段。
// 折叠状态在本组件持有（§9.3.2：父子直连 props 下行/事件上行，不引 provide/store；
// 内存态不持久化，刷新复位）。aside 宽度瞬切零动画（§6 铁律禁 width 动画）。
import { ref } from 'vue';
import { RouterView } from 'vue-router';
import AppHeader from './components/AppHeader.vue';
import AppSidebar from './components/AppSidebar.vue';

/** 侧栏折叠态：true 收起为 64px 图标条（仅品牌单字+菜单缩写），false 展开 220px 全文菜单 */
const collapsed = ref(false);
</script>

<template>
  <el-container class="main-layout">
    <!-- 宽度随折叠态瞬切（零动画）：展开 220px / 折叠 64px，与菜单折叠宽对齐 -->
    <el-aside class="main-aside" :width="collapsed ? '64px' : '220px'">
      <AppSidebar :collapsed="collapsed" />
    </el-aside>
    <el-container class="main-body">
      <el-header class="main-header" height="56px">
        <!-- 折叠开关：Header 按钮发事件上行，本组件翻转状态后经 props 下行两侧 -->
        <AppHeader :collapsed="collapsed" @toggle="collapsed = !collapsed" />
      </el-header>
      <el-main class="main-content">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* 布局级样式隔离（web A.1-2）：三段骨架尺寸（§9.3.1 布局树），业务区留白由 .fuy-page 承载 */
.main-layout {
  height: 100dvh; /* dvh 兼容移动端浏览器地址栏收展，避免视口高度误判 */
  overflow: hidden; /* 滚动收敛到内容区（.main-content），三段骨架自身不滚 */
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
