/**
 * 路由 = 权限点清单（web B.3-2）：每条路由 meta 承载权限语义，路由组件全部懒加载；
 * beforeEach 只做认证判定（默认拒绝 + 防死循环），权限点校验随 P1 鉴权拦截接入。
 */
import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

/** RouteMeta 类型增补：权限语义字段集中声明（医疗系统"路由 = 权限点清单"审计形态） */
declare module 'vue-router' {
  interface RouteMeta {
    /** 免认证公开路由：true 无需登录即可访问；缺省（undefined）= 受保护 */
    public?: boolean;
    /** 权限点语义（医疗系统"路由 = 权限点清单"审计形态；鉴权拦截随 P1 接入，先登记语义） */
    permission?: string;
  }
}

export const router = createRouter({
  // 基期与 vite base 同源（BASE_URL=/workstation/），保证子路径部署下路由正确匹配
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      // 路由组件全懒加载（web B.3-2 红线），禁静态导入
      component: () => import('@/views/login/LoginView.vue'),
      // 免认证公开路由：登录页允许未登录直通
      meta: { public: true },
    },
    {
      path: '/',
      name: 'layout',
      component: () => import('@/views/layout/MainLayout.vue'),
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/views/home/HomeView.vue'),
          // meta 预留权限语义：P1 鉴权拦截接入后补 permission 权限点字段
          meta: {},
        },
        {
          path: 'patient/create',
          name: 'patient-create',
          component: () => import('@/views/patient/PatientCreateView.vue'),
          meta: { permission: 'patient:archive:create' },
        },
        {
          path: 'patients',
          name: 'patient-search',
          component: () => import('@/views/patient/PatientSearchView.vue'),
          meta: { permission: 'patient:archive:search' },
        },
        {
          path: 'patients/:patientId',
          name: 'patient-detail',
          component: () => import('@/views/patient/PatientDetailView.vue'),
          meta: { permission: 'patient:archive:search' },
        },
        {
          path: 'billing/pricing-settle',
          name: 'billing-pricing-settle',
          component: () => import('@/views/billing/PricingSettleView.vue'),
          meta: { permission: 'billing:charge:settle' },
        },
        {
          path: 'billing/refunds',
          name: 'billing-refunds',
          component: () => import('@/views/billing/RefundApprovalView.vue'),
          meta: { permission: 'billing:refund:approve' },
        },
        {
          path: 'billing/daily-list',
          name: 'billing-daily-list',
          component: () => import('@/views/billing/DailyListView.vue'),
          meta: { permission: 'billing:statement:daily-list' },
        },
      ],
    },
  ],
});

// 认证守卫（web B.3-2 分层：beforeEach 只做认证/权限；数据预取/埋点归后续分层钩子）
router.beforeEach((to) => {
  const auth = useAuthStore();
  // 默认拒绝：非公开路由未登录一律重定向登录页，并携带回跳地址供登录成功后还原
  if (!to.meta.public && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  // 已登录访问登录页回首页，防重定向死循环
  if (to.path === '/login' && auth.isLoggedIn) {
    return { path: '/' };
  }
});
