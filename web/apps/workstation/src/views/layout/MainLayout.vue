<script setup lang="ts">
// 主布局（「纸质病案 Paper Chart」世界壳层 · 树形菜单形态）：蓝黑墨书脊导轨 + 亮纸白
// 顶条 + 纸白工作面三段。侧栏宽度态（展开 240px / 收起 64px）在本组件持有（父子直连
// props 下行/事件上行，不引 store；内存态不持久化，刷新复位）；岗位维度已随批次 2
// 裁决废除，本组件不再 provide 任何岗位状态。aside 宽度瞬切零动画（铁律禁 width 动画）。
import { ref } from 'vue';
import { RouterView } from 'vue-router';
import AppHeader from './components/AppHeader.vue';
import AppSidebar from './components/AppSidebar.vue';

/** 侧栏折叠态：true 收起为 64px 图标条（组图标悬浮弹层），false 展开 240px 树形全称菜单 */
const collapsed = ref(false);
</script>

<template>
  <el-container class="main-layout">
    <!-- 宽度随折叠态瞬切（零动画）：展开 240px / 折叠 64px，与菜单折叠宽对齐 -->
    <el-aside class="main-aside" :width="collapsed ? '64px' : '240px'">
      <AppSidebar :collapsed="collapsed" />
    </el-aside>
    <el-container class="main-body">
      <el-header class="main-header" height="56px">
        <!-- 折叠开关：Header 按钮发事件上行，本组件翻转状态后经 props 下行侧栏 -->
        <AppHeader :collapsed="collapsed" @toggle="collapsed = !collapsed" />
      </el-header>
      <el-main class="main-content">
        <!-- 路由切换过渡（暖纸卷宗基础册 · 契约 ⑦.4）：旧页 120ms 直退、新页 240ms
             上浮显影（out-in：旧页先退新页后进），过渡类唯一来源 motion.css .fuy-page-*；
             页内 stagger 自带节奏不叠加（页面根节点不挂 .fuy-stagger） -->
        <RouterView v-slot="{ Component }">
          <Transition name="fuy-page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
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
