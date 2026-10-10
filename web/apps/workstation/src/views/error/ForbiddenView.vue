<script setup lang="ts">
// 403 无权限页（暖纸卷宗基础册 · 契约 ⑪.7 门牌构图重写）：权限守卫拦截「路由登记权限点
// 且会话权限点集不含」的导航后渲染。门牌页首（衬线标题 + 「谁·何时」批注行，空值以 —
// 占位零伪数据）+ 书法「禁」静态水印装饰层（业务页禁氛围动效，保持静态不消费任何
// keyframes）+ 诚实错误脸（.fuy-empty 承载：说问题 + 给恢复，role=alert 提示即时性）。
// 不提供回跳重试——被拒路由的唯一确定出口是回首页（未登记权限点），自动重试被拒路由
// 会造成重定向死循环；权限开通走线下管理员流程（P1 用户管理前无自助入口）。
// 零出网零 STOMP：本页无任何数据依赖，页面进场随路由 .fuy-page 过渡自然显影，页内
// 不另加动画。
import { useRouter } from 'vue-router';

const router = useRouter();

/** 回首页：无权限会话确定可达的路由（未登记权限点，守卫恒放行） */
function goHome(): void {
  void router.push({ path: '/' });
}
</script>

<template>
  <section class="fuy-page forbidden-page">
    <!-- 书法底字水印：装饰层 aria-hidden 不进读屏；朱砂 @0.06 静态巨字（280px 级） -->
    <div class="forbidden-mark" aria-hidden="true">禁</div>
    <header class="fuy-page-head">
      <div class="fuy-page-head-main">
        <h1 class="fuy-page-title">403 · 无访问权限</h1>
      </div>
      <!-- 「谁·何时」批注行：会话身份/时刻不可信（被拒场景无签认事实），空值一律 — 占位 -->
      <p class="fuy-page-note">签认人 — · 时刻 —</p>
    </header>
    <!-- 诚实错误脸：说问题（主句）+ 给恢复（说明）；错误态不伪装成空态 -->
    <div class="fuy-empty forbidden-body" role="alert">
      <p class="fuy-empty-title">当前会话未获授权访问该功能</p>
      <p class="fuy-empty-hint">请联系管理员开通权限；被拒路由不提供自动重试。</p>
    </div>
    <el-button class="forbidden-home" type="primary" @click="goHome">返回首页</el-button>
  </section>
</template>

<style scoped>
/* 门牌构图局部样式（全局脸样式由 element-plus.css .fuy-page-head/.fuy-empty 承载，
   本块只承载 403 页私有构图位） */
.forbidden-page {
  position: relative;
  z-index: 0; /* 建立层叠上下文：水印 z-index -1 压在页面底之上、内容之下 */
  overflow: hidden; /* 水印出血位裁切（登录封面 deco 同构），防横向滚动破版 */
}

/* 书法「禁」水印：装饰层静态巨字（朱砂 @0.06，opacity 承弱化不引新色值；
   业务页禁氛围动效——静态呈现零动画） */
.forbidden-mark {
  position: absolute;
  right: -20px;
  top: -60px;
  z-index: -1;
  font-family: var(--fuy-font-family-brush);
  font-size: 280px;
  line-height: 1;
  color: var(--fuy-cinnabar);
  opacity: 0.06;
  pointer-events: none;
  user-select: none;
}

/* 错误脸承文区限宽：防巨字水印下长行扫读困难（说问题+给恢复的文书脸） */
.forbidden-body {
  max-width: 560px;
}

/* 返回首页墨实底钮：左挂自起（.fuy-page 纵向 flex 的拉伸默认改列首对齐） */
.forbidden-home {
  align-self: flex-start;
}
</style>
