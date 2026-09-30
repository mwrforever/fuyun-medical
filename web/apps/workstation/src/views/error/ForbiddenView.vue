<script setup lang="ts">
// 403 无权限页（BUG-14 守卫骨架落点）：权限守卫拦截「路由登记权限点且会话权限点集不含」
// 的导航后渲染。不提供回跳重试——被拒路由的唯一确定出口是回首页（未登记权限点），
// 自动重试被拒路由会造成重定向死循环；权限开通走线下管理员流程（P1 用户管理前无自助入口）。
import { useRouter } from 'vue-router';

const router = useRouter();

/** 回首页：无权限会话确定可达的路由（未登记权限点，守卫恒放行） */
function goHome(): void {
  void router.push({ path: '/' });
}
</script>

<template>
  <section class="fuy-page">
    <el-result
      icon="warning"
      title="403 无访问权限"
      sub-title="当前会话未获授权访问该功能，请联系管理员开通。"
    >
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </section>
</template>
