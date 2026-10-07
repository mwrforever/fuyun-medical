// 护理执行工作台页单测（PR-3 Task 14）：路由权限点 meta 登记断言（路由=权限点清单）、
// 四列看板渲染锚点（列名/行卡床号/医嘱摘要/计划时间/锚行类型标签/升级标记）、空态、
// 行卡点击开抽屉渲染执行单号与闭环时间线、LONG 锚行抽屉不进操作流（操作组隐藏）、
// 输液遥测条按 patientId 组合渲染（护理侧在途行 × ward 侧设备遥测）。api mock 承载。
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { executions, infusions } from '@/api/nursing';
import type { OrderExecutionVO } from '@/api/nursing';
import { infusionBoard } from '@/api/ward';
import { permDirective } from '@/directives/perm';
import { router } from '@/router';
import ExecutionWorkbenchView from './ExecutionWorkbenchView.vue';

vi.mock('@/api/nursing', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  SHIFT_OPTIONS: [
    { code: 'DAY', label: '白班' },
    { code: 'EVENING', label: '小夜班' },
    { code: 'NIGHT', label: '大夜班' },
  ],
  EXECUTION_TYPE_LABELS: { GENERIC: '通用给药', INFUSION: '输液' },
  CHECK_TYPE_OPTIONS: [
    { code: 'WRISTBAND', label: '腕带' },
    { code: 'BAG_LABEL', label: '瓶签' },
    { code: 'DEVICE', label: '执行单' },
  ],
  executions: {
    list: vi.fn(),
    trace: vi.fn(),
    signReceive: vi.fn(),
    check: vi.fn(),
    start: vi.fn(),
    finish: vi.fn(),
    cancel: vi.fn(),
  },
  infusions: { active: vi.fn() },
}));

vi.mock('@/api/ward', () => ({
  infusionBoard: { byWard: vi.fn() },
  INFUSION_ALERT_LABELS: { NONE: '正常', YELLOW: '黄档预警', ORANGE: '橙档告急', RED: '红档危急' },
}));

// 仅替身 ElMessage（复制成功提示断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table/抽屉布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 会话种子（auth store 从 sessionStorage 恢复：执行人=登录用户 u1/李护士；
 * permissions 含 #29 元素码——真实 NURSE 会话经登录契约导出含码，既有用例语义不变） */
function seedAuthSession(): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'nursedemo',
        displayName: '李护士',
        orgId: null,
        roles: ['nurse'],
        permissions: ['nursing:execution:btn:perform'],
      },
    }),
  );
}

/** 执行单行（状态/锚行/升级标记可覆写） */
function rowMock(partial: Partial<OrderExecutionVO> = {}): OrderExecutionVO {
  return {
    executionNo: 'EX2026100100001',
    m04OrderNo: 'M04202610010001',
    m04PlanNo: 'M04P2026100100001',
    visitId: 'I2026092300001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    bedNo: '01',
    executionType: 'GENERIC',
    execItemName: '0.9% 氯化钠注射液',
    dosageText: '250ml qd',
    planTime: '2026-10-01T08:00:00',
    status: 'CREATED',
    ...partial,
  };
}

describe('护理执行工作台页', () => {
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
    vi.mocked(executions.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(executions.trace).mockReset().mockResolvedValue({
      executionNo: 'EX2026100100001',
      planTime: '2026-10-01T08:00:00',
      checkLogs: [],
    });
    vi.mocked(infusions.active).mockReset().mockResolvedValue([]);
    vi.mocked(infusionBoard.byWard).mockReset().mockResolvedValue({ devices: [] });
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
  });

  it('路由登记权限点 nursing:execution:perform（路由=权限点清单审计形态）', () => {
    expect(router.resolve('/nursing/execution').meta.permission).toBe('nursing:execution:perform');
  });

  it('四列看板渲染锚点：列名/行卡床号/医嘱摘要/计划时间/锚行标签/升级标记', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [
        rowMock({ executionNo: 'E1', bedNo: '01', status: 'CREATED' }),
        rowMock({
          executionNo: 'E2',
          bedNo: '02',
          status: 'CREATED',
          m04PlanNo: undefined,
          executionType: 'INFUSION',
          overrideFlag: true,
          latestAlarmNo: 'AL2026100100001',
        }),
        rowMock({ executionNo: 'E3', bedNo: '03', status: 'EXECUTING' }),
      ],
      total: '3',
    });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 四列列名（spec 冻结渲染序）
    const headers = wrapper.findAll('.exec-column-title').map((node) => node.text());
    expect(headers).toEqual(['待签收', '待核对', '待执行', '执行中']);
    // 行卡锚点：床号 + 医嘱摘要 + 计划时间
    const cards = wrapper.findAll('.exec-card');
    expect(cards).toHaveLength(3);
    expect(wrapper.find('.exec-card-bed').text()).toBe('01');
    expect(wrapper.find('.exec-card-item').text()).toContain('0.9% 氯化钠注射液');
    expect(wrapper.find('.exec-card-plan').text()).toContain('08:00');
    // 锚行类型标签区分两种快照语义 + 升级标记（越权/告警）
    expect(wrapper.text()).toContain('类型锚行');
    expect(wrapper.text()).toContain('执行快照');
    expect(wrapper.text()).toContain('越权');
    expect(wrapper.text()).toContain('告警');
    wrapper.unmount();
  });

  it('空看板渲染空态（暂无执行单）', async () => {
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无执行单');
    wrapper.unmount();
  });

  it('点击行卡开抽屉：执行单号+复制按钮+闭环时间线渲染；锚行不进操作流', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [
        // 01 床 SIGNED（快照行，落待核对列）+ 02 床 CREATED 锚行（落待签收列=首列）
        rowMock({ executionNo: 'EX2026100100001', bedNo: '01', status: 'SIGNED' }),
        rowMock({
          executionNo: 'EX2026100100002',
          bedNo: '02',
          status: 'CREATED',
          m04PlanNo: undefined,
        }),
      ],
      total: '2',
    });
    vi.mocked(executions.trace).mockResolvedValue({
      executionNo: 'EX2026100100001',
      planTime: '2026-10-01T08:00:00',
      signedAt: '2026-10-01T08:05:00',
      checkLogs: [
        { checkType: 'WRISTBAND', checkResult: 'PASS', occurredAt: '2026-10-01T08:06:00' },
      ],
    });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    const cards = wrapper.findAll('.exec-card');
    expect(cards).toHaveLength(2);
    // 快照行（01 床，第二列）：抽屉开 + 单号 + 复制按钮 + 时间线 + 操作按钮组（SIGNED=核对/撤销）
    await cards[1].trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('modelValue')).toBe(true);
    expect(wrapper.find('.exec-drawer-no').text()).toContain('EX2026100100001');
    expect(wrapper.text()).toContain('复制单号');
    expect(wrapper.text()).toContain('计划');
    expect(wrapper.text()).toContain('签收');
    expect(wrapper.text()).toContain('核对通过');
    const buttons = wrapper.findAll('.exec-drawer-actions button').map((b) => b.text());
    expect(buttons).toContain('核对');
    expect(buttons).toContain('撤销');
    // 锚行（02 床，首列）：换选后操作组隐藏（不进操作流，时间线仍可追溯）
    await cards[0].trigger('click');
    await flushPromises();
    expect(wrapper.find('.exec-drawer-actions').exists()).toBe(false);
    expect(wrapper.text()).toContain('类型锚行不进操作流');
    wrapper.unmount();
  });

  it('输液遥测条按 patientId 组合渲染（护理侧在途行 × ward 侧设备遥测）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'E1', status: 'EXECUTING', executionType: 'INFUSION' })],
      total: '1',
    });
    vi.mocked(infusions.active).mockResolvedValue([
      {
        executionNo: 'EX2026100100001',
        patientId: '1932000000000000001',
        visitId: 'I2026092300001',
        wardId: 'W01',
        bedNo: '01',
        execItemName: '0.9% 氯化钠注射液',
        startedAt: '2026-10-01T08:10:00',
        iotDeviceId: 'DEV-001',
        escalationCount: 2,
      },
    ]);
    vi.mocked(infusionBoard.byWard).mockResolvedValue({
      devices: [
        { deviceId: 'DEV-001', remainLatest: 120, dropRateLatest: 45, alertLevel: 'YELLOW' },
      ],
    });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    // 遥测条组合值：余量 120ml + 滴速 45 + 黄档预警（组合降级 UI 内不体现，仅数据缺位不渲染）
    expect(wrapper.find('.exec-infusion-strip').exists()).toBe(true);
    expect(wrapper.find('.exec-infusion-strip').text()).toContain('120');
    expect(wrapper.find('.exec-infusion-strip').text()).toContain('45');
    expect(wrapper.find('.exec-infusion-strip').text()).toContain('黄档预警');
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(ExecutionWorkbenchView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 nursing:execution:btn:perform 时抽屉五动作全隐藏（D-34 无码全移除）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'SIGNED' })],
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    // 抽屉可开（读面不受限）；动作容器由 v-if（状态机）渲染，内部按钮经 v-perm 逐个
    // DOM 移除——无码即五动作全隐藏（D-34），容器残留空壳不构成可达入口
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('modelValue')).toBe(true);
    expect(wrapper.find('.exec-drawer-actions').findAll('button')).toHaveLength(0);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 nursing:execution:btn:perform 时抽屉五动作按状态机出位', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'SIGNED' })],
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    const buttons = wrapper.findAll('.exec-drawer-actions button').map((b) => b.text());
    expect(buttons).toContain('核对');
    expect(buttons).toContain('撤销');
    wrapper.unmount();
  });
});
