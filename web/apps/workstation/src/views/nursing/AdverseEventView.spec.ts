// 护理不良事件上报页单测（PR-3 Task 14）：路由权限点 meta 登记断言、列表渲染锚点
// （单号/类别/分级+等级/状态标签/超时留痕标记）、空态、上报弹窗必填拦截零出网与成功
// 重拉、处理/关闭/退回操作留痕出网。api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { adverseEvents } from '@/api/nursing';
import type { AdverseEventVO } from '@/api/nursing';
import { permDirective } from '@/directives/perm';
import { router } from '@/router';
import AdverseEventView from './AdverseEventView.vue';

vi.mock('@/api/nursing', () => ({
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  ADVERSE_CATEGORY_OPTIONS: [
    { code: 'FALL', label: '跌倒坠床' },
    { code: 'MEDICATION_ERROR', label: '用药错误' },
  ],
  SEVERITY_CLASS_OPTIONS: [
    { code: 'I', label: 'I 级（最重）' },
    { code: 'II', label: 'II 级（重）' },
  ],
  SEVERITY_GRADE_OPTIONS: [{ code: 'A', label: 'A' }],
  adverseEvents: {
    list: vi.fn(),
    report: vi.fn(),
    handle: vi.fn(),
    close: vi.fn(),
    return: vi.fn(),
  },
}));

// 仅替身弹层件（提示与确认断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
      prompt: vi.fn().mockResolvedValue({ value: '处置记录' }),
    },
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

/** 会话种子（auth store 从 sessionStorage 恢复：操作人=登录用户 u1/李护士；
 * permissions 含 #31 元素码——真实 NURSE 会话经登录契约导出含码，既有用例语义不变） */
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
        permissions: ['nursing:adverse-event:btn:manage'],
      },
    }),
  );
}

/** 不良事件行（状态/时限可覆写） */
function eventMock(partial: Partial<AdverseEventVO> = {}): AdverseEventVO {
  return {
    eventNo: 'AE2026100100001',
    category: 'FALL',
    severityClass: 'II',
    severityGrade: 'B',
    wardId: 'W01',
    occurredAt: '2026-10-01T07:30:00',
    eventSummary: '病房走廊跌倒，右膝擦伤',
    isAnonymous: false,
    reportDeadline: '2026-10-02T07:30:00',
    deadlineMet: true,
    status: 'REPORTED',
    createdAt: '2026-10-01T08:00:00',
    ...partial,
  };
}

describe('护理不良事件上报页', () => {
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
    vi.mocked(adverseEvents.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(adverseEvents.report).mockReset().mockResolvedValue(eventMock());
    vi.mocked(adverseEvents.handle).mockReset().mockResolvedValue(eventMock());
    vi.mocked(adverseEvents.close).mockReset().mockResolvedValue(eventMock());
    vi.mocked(adverseEvents.return).mockReset().mockResolvedValue(eventMock());
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.prompt).mockClear();
  });

  it('路由登记权限点 nursing:adverse-event:report（路由=权限点清单审计形态）', () => {
    expect(router.resolve('/nursing/adverse-events').meta.permission).toBe(
      'nursing:adverse-event:report',
    );
  });

  it('列表渲染锚点：单号/类别/分级+等级/状态标签/超时留痕标记/匿名标记', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [
        eventMock(),
        eventMock({
          eventNo: 'AE2026100100002',
          status: 'HANDLING',
          deadlineMet: false,
          isAnonymous: true,
        }),
      ],
      total: '2',
    });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('AE2026100100001');
    expect(wrapper.text()).toContain('跌倒坠床');
    // 分级/等级列组合展示（II / B）
    expect(wrapper.text()).toContain('II / B');
    expect(wrapper.text()).toContain('待处理');
    expect(wrapper.text()).toContain('处理中');
    expect(wrapper.text()).toContain('超时留痕');
    expect(wrapper.text()).toContain('匿名');
    wrapper.unmount();
  });

  it('空列表渲染空态（暂无不良事件记录）', async () => {
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无不良事件记录');
    wrapper.unmount();
  });

  it('上报弹窗：必填缺失提示且零出网；补齐后出网一次并重拉列表', async () => {
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const buttons = wrapper.findAll('button');
    const reportButton = buttons.find((b) => b.text() === '上报事件');
    if (!reportButton) {
      throw new Error('未找到上报事件按钮');
    }
    await reportButton.trigger('click');
    await flushPromises();
    // 类别未选：显式校验零出网
    const submit = wrapper.findAll('button').find((b) => b.text() === '提交上报');
    if (!submit) {
      throw new Error('未找到提交上报按钮');
    }
    await submit.trigger('click');
    await flushPromises();
    expect(adverseEvents.report).not.toHaveBeenCalled();
    expect(ElMessage.warning).toHaveBeenCalledWith('请选择事件类别（必填）');
    // 补齐必填面（select 经组件事替身 emit 回填 v-model——存量 spec 同款口径；发生时点
    // datetime-local 为原生控件直填）
    const selects = wrapper.findAllComponents({ name: 'ElSelect' });
    await selects[0].vm.$emit('update:modelValue', 'FALL');
    await selects[1].vm.$emit('update:modelValue', 'II');
    await selects[2].vm.$emit('update:modelValue', 'B');
    await wrapper.find('input[type="datetime-local"]').setValue('2026-10-01T07:30');
    await wrapper.find('textarea[aria-label="事件经过"]').setValue('病房走廊跌倒');
    await submit.trigger('click');
    await flushPromises();
    expect(adverseEvents.report).toHaveBeenCalledTimes(1);
    // 上报成功回第一页重拉（onMounted 初拉一次 + 上报后 search 一次；弹窗关闭动画在
    // jsdom 不落定，关闭态经重拉行为断言而非 DOM 卸载断言）
    expect(adverseEvents.list).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('处理操作：角色留痕提示弹窗确认后出网一次（handlerId=会话用户）', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock()],
      total: '1',
    });
    const wrapper = mount(AdverseEventView, { global: { plugins: [pinia] } });
    await flushPromises();
    const handleButton = wrapper.findAll('button').find((b) => b.text() === '处理');
    if (!handleButton) {
      throw new Error('未找到处理按钮');
    }
    await handleButton.trigger('click');
    await flushPromises();
    // 弹窗文案含操作人留痕提示（角色留痕）
    expect(ElMessageBox.prompt).toHaveBeenCalledTimes(1);
    const promptMessage = vi.mocked(ElMessageBox.prompt).mock.calls[0]?.[0];
    expect(typeof promptMessage === 'string' && promptMessage.includes('u1')).toBe(true);
    expect(adverseEvents.handle).toHaveBeenCalledWith('AE2026100100001', {
      handlerId: 'u1',
      handlingNote: '处置记录',
    });
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(AdverseEventView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 nursing:adverse-event:btn:manage 时上报与行内操作全隐藏（D-34）', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock()],
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('上报事件');
    expect(buttonTexts).not.toContain('处理');
    expect(buttonTexts).not.toContain('退回');
    // 列表读面不受元素码影响（单号仍渲染）
    expect(wrapper.text()).toContain('AE2026100100001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 nursing:adverse-event:btn:manage 时上报入口与行内操作可见', async () => {
    vi.mocked(adverseEvents.list).mockResolvedValue({
      content: [eventMock()],
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('上报事件');
    expect(buttonTexts).toContain('处理');
    expect(buttonTexts).toContain('退回');
    wrapper.unmount();
  });
});
