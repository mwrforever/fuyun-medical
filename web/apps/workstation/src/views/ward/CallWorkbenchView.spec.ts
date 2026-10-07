// 呼叫工作台单测（/ward/call-workbench，M16 病区呼叫闭环面前端面）：待应答默认筛选出网携
// status=CREATED 与列表渲染（呼叫类型/来源/床位/升级标记 fuy-call-escalation 机器判据）、
// 应答出网携 callNo 并刷新、处理出网 progress、完成弹窗空摘要零出网与携摘要出网 complete、
// 转接与取消出网、声音提示缺位注记文案可见。
// api mock 承载零出网（vi.mock('@/api/ward') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { wardCalls } from '@/api/ward';
import type { WardCallVO } from '@/api/ward';
import { permDirective } from '@/directives/perm';
import CallWorkbenchView from './CallWorkbenchView.vue';

vi.mock('@/api/ward', () => ({
  CALL_STATUS_LABELS: {
    CREATED: '待应答',
    ANSWERED: '已应答',
    IN_PROGRESS: '处理中',
    COMPLETED: '已完成',
    TRANSFERRED: '已转接',
    CANCELLED: '已取消',
  },
  CALL_TYPE_LABELS: {
    NORMAL: '普通呼叫',
    EMERGENCY: '紧急呼叫',
    INFUSION: '输液呼叫',
    SERVICE: '服务请求',
  },
  CALL_SOURCE_LABELS: {
    BEDSIDE: '床头分机',
    BRROOM: '卫生间',
    PATIENT_PAD: '患者手环',
    NURSE_PAD: '护士 PDA',
    IOT: '物联网设备',
  },
  WARD_OPTIONS: [{ code: '1001', label: '1001 演示病区' }],
  wardCalls: {
    page: vi.fn(),
    create: vi.fn(),
    answer: vi.fn(),
    progress: vi.fn(),
    complete: vi.fn(),
    transfer: vi.fn(),
    cancel: vi.fn(),
  },
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 呼叫行（可覆写状态/类型/升级计数） */
function callMock(partial: Partial<WardCallVO> = {}): WardCallVO {
  return {
    id: '401',
    callNo: 'WC20260926001',
    wardId: '1001',
    bedId: '903',
    patientId: '1932000000000000001',
    deviceId: undefined,
    callType: 'NORMAL',
    source: 'BEDSIDE',
    status: 'CREATED',
    escalationCount: 0,
    processedBy: undefined,
    resultSummary: undefined,
    sourceRef: undefined,
    answeredAt: undefined,
    completedAt: undefined,
    createdAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 空呼叫分页出参 */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行 */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 在指定行内按按钮文案点击（行作用域操作，防跨行误中） */
async function clickRowButton(
  wrapper: VueWrapper,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = findRow(wrapper, rowText);
  const button = row?.findAll('button').find((b) => b.text() === buttonText);
  if (!button) {
    throw new Error(`未找到行内按钮：${buttonText}`);
  }
  await button.trigger('click');
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('病区呼叫工作台', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #39）：真实 NURSE 会话经登录契约导出含码，既有用例语义不变
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
          permissions: ['ward:call:btn:handle'],
        },
      }),
    );
    for (const fn of [
      wardCalls.page,
      wardCalls.create,
      wardCalls.answer,
      wardCalls.progress,
      wardCalls.complete,
      wardCalls.transfer,
      wardCalls.cancel,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(wardCalls.page).mockResolvedValue(emptyPage());
  });

  it('默认待应答筛选出网携 status=CREATED，列表渲染类型/来源/床位与升级标记徽标', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ escalationCount: 2, callType: 'EMERGENCY' }),
        callMock({
          callNo: 'WC20260926002',
          callType: 'INFUSION',
          source: 'IOT',
          escalationCount: 0,
        }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    expect(wardCalls.page).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'CREATED', wardId: '1001', page: 0, size: 50 }),
    );
    const text = wrapper.text();
    expect(text).toContain('WC20260926001');
    expect(text).toContain('紧急呼叫');
    expect(text).toContain('输液呼叫');
    expect(text).toContain('床头分机');
    expect(text).toContain('物联网设备');
    // 升级标记徽标（escalationCount>0 才渲染，机器判据）
    expect(wrapper.find('.fuy-call-escalation').exists()).toBe(true);
    expect(wrapper.text()).toContain('已升级 2 次');
  });

  it('应答出网携 callNo 并刷新列表（CREATED→ANSWERED 流转归后端承载）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.answer).mockResolvedValue(callMock({ status: 'ANSWERED' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '应答');
    await flushPromises();
    expect(wardCalls.answer).toHaveBeenCalledWith('WC20260926001');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（page 第二次调用）
    expect(wardCalls.page).toHaveBeenCalledTimes(2);
  });

  it('处理出网 progress（ANSWERED→IN_PROGRESS 开始到场处置）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock({ status: 'ANSWERED' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.progress).mockResolvedValue(callMock({ status: 'IN_PROGRESS' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '处理');
    await flushPromises();
    expect(wardCalls.progress).toHaveBeenCalledWith('WC20260926001');
    expect(wardCalls.page).toHaveBeenCalledTimes(2);
  });

  it('完成弹窗空摘要零出网拦截，携摘要出网 complete 并刷新', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock({ status: 'IN_PROGRESS' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.complete).mockResolvedValue(callMock({ status: 'COMPLETED' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '完成');
    await flushPromises();
    // 空摘要零出网（resultSummary 强制）
    await clickButton(wrapper, '确认完成');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写处置结果');
    expect(wardCalls.complete).not.toHaveBeenCalled();
    // 携摘要出网
    await wrapper.find('textarea[aria-label="处置结果"]').setValue('已到场处理完毕');
    await clickButton(wrapper, '确认完成');
    await flushPromises();
    expect(wardCalls.complete).toHaveBeenCalledWith('WC20260926001', {
      resultSummary: '已到场处理完毕',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(wardCalls.page).toHaveBeenCalledTimes(2);
  });

  it('转接与取消出网携 callNo 并刷新列表', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ callNo: 'WC20260926002', status: 'ANSWERED' }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    vi.mocked(wardCalls.transfer).mockResolvedValue(callMock({ status: 'TRANSFERRED' }));
    vi.mocked(wardCalls.cancel).mockResolvedValue(callMock({ status: 'CANCELLED' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '转接');
    await flushPromises();
    expect(wardCalls.transfer).toHaveBeenCalledWith('WC20260926001');
    await clickRowButton(wrapper, 'WC20260926001', '取消');
    await flushPromises();
    expect(wardCalls.cancel).toHaveBeenCalledWith('WC20260926001');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledTimes(2);
  });

  it('声音提示缺位注记文案可见（本批次静默提示面留痕）', async () => {
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    expect(wrapper.text()).toContain('声音提示本批次未接入');
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(CallWorkbenchView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 ward:call:btn:handle 时闭环五动作全隐藏（D-34）', async () => {
    // 三态行齐备（待应答/已应答/处理中）驱动应答/处理/完成/转接/取消五按钮全部出位
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ id: '402', callNo: 'WC20260926002', status: 'ANSWERED' }),
        callMock({ id: '403', callNo: 'WC20260926003', status: 'IN_PROGRESS' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 呼叫闭环五动作无码全隐藏（D-34 无码 DOM 移除）
    expect(buttonTexts).not.toContain('应答');
    expect(buttonTexts).not.toContain('处理');
    expect(buttonTexts).not.toContain('完成');
    expect(buttonTexts).not.toContain('转接');
    expect(buttonTexts).not.toContain('取消');
    // 呼叫清单读面不受元素码影响
    expect(wrapper.text()).toContain('WC20260926001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 ward:call:btn:handle 时闭环五动作按状态机出位可见', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ id: '402', callNo: 'WC20260926002', status: 'ANSWERED' }),
        callMock({ id: '403', callNo: 'WC20260926003', status: 'IN_PROGRESS' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('应答');
    expect(buttonTexts).toContain('处理');
    expect(buttonTexts).toContain('完成');
    expect(buttonTexts).toContain('转接');
    expect(buttonTexts).toContain('取消');
    wrapper.unmount();
  });
});
