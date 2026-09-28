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
        {
          path: 'pharmacy/drug-dict',
          name: 'pharmacy-drug-dict',
          component: () => import('@/views/pharmacy/DrugDictView.vue'),
          meta: { permission: 'pharmacy:drug:maintain' },
        },
        {
          path: 'pharmacy/dispense-workbench',
          name: 'pharmacy-dispense-workbench',
          component: () => import('@/views/pharmacy/DispenseWorkbenchView.vue'),
          meta: { permission: 'pharmacy:dispense:issue' },
        },
        {
          path: 'pharmacy/dispense-return',
          name: 'pharmacy-dispense-return',
          component: () => import('@/views/pharmacy/DispenseReturnView.vue'),
          meta: { permission: 'pharmacy:dispense:return' },
        },
        {
          path: 'outpatient/registration-charge',
          name: 'outpatient-registration-charge',
          component: () => import('@/views/outpatient/RegistrationChargeView.vue'),
          meta: { permission: 'outpatient:registration:register' },
        },
        {
          path: 'outpatient/triage-board',
          name: 'outpatient-triage-board',
          component: () => import('@/views/outpatient/TriageBoardView.vue'),
          meta: { permission: 'outpatient:triage:manage' },
        },
        {
          path: 'outpatient/doctor-station',
          name: 'outpatient-doctor-station',
          component: () => import('@/views/outpatient/DoctorStationView.vue'),
          meta: { permission: 'outpatient:doctor:consult' },
        },
        {
          path: 'nursing/ward',
          name: 'nursing-ward',
          component: () => import('@/views/nursing/WardBoardView.vue'),
          meta: { permission: 'nursing:ward:view' },
        },
        {
          path: 'inpatient/admission',
          name: 'inpatient-admission',
          component: () => import('@/views/inpatient/AdmissionView.vue'),
          meta: { permission: 'inpatient:admission:manage' },
        },
        {
          path: 'inpatient/beds',
          name: 'inpatient-beds',
          component: () => import('@/views/inpatient/BedMapView.vue'),
          meta: { permission: 'inpatient:bed:view' },
        },
        {
          path: 'inpatient/station',
          name: 'inpatient-station',
          component: () => import('@/views/inpatient/DoctorStationView.vue'),
          meta: { permission: 'inpatient:station:view' },
        },
        {
          path: 'inpatient/transfer',
          name: 'inpatient-transfer',
          component: () => import('@/views/inpatient/TransferWorklistView.vue'),
          meta: { permission: 'inpatient:transfer:check' },
        },
        {
          path: 'inpatient/discharge',
          name: 'inpatient-discharge',
          component: () => import('@/views/inpatient/DischargeManageView.vue'),
          meta: { permission: 'inpatient:discharge:manage' },
        },
        {
          path: 'pharmacy/review',
          name: 'pharmacy-review',
          component: () => import('@/views/pharmacy/ReviewTaskView.vue'),
          meta: { permission: 'pharmacy:review:audit' },
        },
        {
          // IoT 管理七路由第一批四条（第二批三路由归 M14 后续任务登记）
          path: 'iot/products',
          name: 'iot-products',
          component: () => import('@/views/iot/ProductManageView.vue'),
          meta: { permission: 'iot:product:manage' },
        },
        {
          path: 'iot/devices',
          name: 'iot-devices',
          component: () => import('@/views/iot/DeviceManageView.vue'),
          meta: { permission: 'iot:device:manage' },
        },
        {
          path: 'iot/bindings',
          name: 'iot-bindings',
          component: () => import('@/views/iot/BindingManageView.vue'),
          meta: { permission: 'iot:binding:manage' },
        },
        {
          path: 'iot/alarm-rules',
          name: 'iot-alarm-rules',
          component: () => import('@/views/iot/AlarmRuleView.vue'),
          meta: { permission: 'iot:alarm-rule:manage' },
        },
        {
          // IoT 管理第二批三路由（M16 命令/联动/质量面）
          path: 'iot/commands',
          name: 'iot-commands',
          component: () => import('@/views/iot/CommandCenterView.vue'),
          meta: { permission: 'iot:command:issue' },
        },
        {
          path: 'iot/linkage-rules',
          name: 'iot-linkage-rules',
          component: () => import('@/views/iot/LinkageRuleView.vue'),
          meta: { permission: 'iot:linkage:manage' },
        },
        {
          path: 'iot/quality',
          name: 'iot-quality',
          component: () => import('@/views/iot/QualityBoardView.vue'),
          meta: { permission: 'iot:quality:view' },
        },
        {
          // 病区视图分组三路由（M16 病区视图：输液看板/呼叫工作台/冷链台账）
          path: 'ward/infusion-board',
          name: 'ward-infusion-board',
          component: () => import('@/views/ward/InfusionBoardView.vue'),
          meta: { permission: 'ward:infusion:view' },
        },
        {
          path: 'ward/call-workbench',
          name: 'ward-call-workbench',
          component: () => import('@/views/ward/CallWorkbenchView.vue'),
          meta: { permission: 'ward:call:handle' },
        },
        {
          path: 'ward/cold-chain',
          name: 'ward-cold-chain',
          component: () => import('@/views/ward/ColdChainView.vue'),
          meta: { permission: 'ward:coldchain:manage' },
        },
      ],
    },
    {
      // PDA 移动护理页：顶层自持布局（MainLayout 之外，照 login 路由形态）——床旁
      // 单手操作面不载侧栏/顶栏；权限语义登记，403 接线随 P1 鉴权拦截接入（patient 三页先例）
      path: '/pda',
      name: 'pda',
      component: () => import('@/views/nursing/PdaView.vue'),
      meta: { permission: 'nursing:pda:use' },
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
