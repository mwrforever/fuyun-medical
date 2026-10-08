<script setup lang="ts">
// 主布局（「纸质病案 Paper Chart」世界壳层）：蓝黑墨书脊导轨 + 亮纸白顶条 + 纸白工作面三段。
// 折叠态与岗位选择均在本组件持有（父子直连 props 下行/事件上行 + provide 跨层下行，
// 不引 store；内存态不持久化，刷新复位）。aside 宽度瞬切零动画（铁律禁 width 动画）。
import { computed, provide, ref } from 'vue';
import { RouterView } from 'vue-router';
import AppHeader from './components/AppHeader.vue';
import AppSidebar from './components/AppSidebar.vue';
import { POST_SELECTION_KEY, type PostSelection } from './menu';

/** 侧栏折叠态：true 收起为 64px 图标条（仅品牌单字+菜单缩写），false 展开 240px 全文菜单 */
const collapsed = ref(false);

/**
 * 当前岗位选择（组件内存态，默认「全部」，刷新复位，与折叠态同等语义）：
 * 唯一写入口 = 侧栏岗位切换器事件上行；经 POST_SELECTION_KEY 只读下行给路由级视图
 * （首页消费），跨 RouterView 层级走 provide/inject（web A.7-4），防 attrs 穿透污染
 * 其余 38 个视图。
 */
const post = ref<PostSelection>('all');
provide(
  POST_SELECTION_KEY,
  computed(() => post.value),
);
</script>

<template>
  <el-container class="main-layout">
    <!-- 宽度随折叠态瞬切（零动画）：展开 240px / 折叠 64px，与菜单折叠宽对齐 -->
    <el-aside class="main-aside" :width="collapsed ? '64px' : '240px'">
      <AppSidebar :collapsed="collapsed" :post="post" @change="post = $event" />
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
/* 布局级样式隔离（web A.1-2）：三段骨架尺寸，业务区留白由 .fuy-page 承载 */
.main-layout {
  height: 100dvh; /* dvh 兼容移动端浏览器地址栏收展，避免视口高度误判 */
  overflow: hidden; /* 滚动收敛到内容区（.main-content），三段骨架自身不滚 */
}

/* 蓝黑墨书脊导轨：外缘缝用书脊深墨（缝在封皮上），不用灰线（灰缝属于桌面世界）；
   滚动轴用纸色细轴（石规灰轴在深墨底上不可见，穿纸细轴与样稿 .rail 同源） */
.main-aside {
  background: var(--fuy-shell-bg);
  border-right: 1px solid var(--fuy-shell-hairline);
  scrollbar-color: rgba(253, 252, 248, 0.25) transparent;
}

.main-aside::-webkit-scrollbar-thumb {
  background: rgba(253, 252, 248, 0.25);
  background-clip: content-box;
}

/* 亮纸白顶条：与工作面之间一道面板石规（顶栏是搁在工作面上的一页纸压条，样稿 topbar 同款） */
.main-header {
  background: var(--fuy-surface-card);
  border-bottom: var(--fuy-border-panel);
}

/* 纸白工作面：卡面亮纸白（--fuy-surface-card）浮于其上，靠规线三级区隔 */
.main-content {
  background: var(--fuy-surface-page);
}
</style>
