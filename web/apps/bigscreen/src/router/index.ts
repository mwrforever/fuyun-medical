// 路由 = 权限点清单（web B.3-2）：每条路由 meta 承载权限语义，路由组件全部懒加载
import { createRouter, createWebHistory } from 'vue-router';

export const router = createRouter({
  // 基期与 vite base 同源（BASE_URL=/bigscreen/），保证子路径部署下路由正确匹配
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      // 路由组件全懒加载（web B.3-2 红线），禁静态导入
      component: () => import('@/views/home/HomeView.vue'),
      // meta 预留权限语义：后续按权限点补 requiresAuth / permission 等字段（医疗系统审计形态）
      meta: {},
    },
  ],
});
