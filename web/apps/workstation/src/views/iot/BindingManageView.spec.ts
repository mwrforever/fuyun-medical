// 设备绑定页单测（/iot/bindings，M14 FU-M14-03 绑定生命周期前端面）：绑定列表加载与
// 五元组列（设备×患者×就诊×床位×病区）渲染与状态三态徽标（fuy-binding-tag--{status}
// 机器判据）、绑定表单 visit_id 14 位格式显式校验（非 I+13 位数字零出网拦截）、绑定提交
// 出网携五元组并刷新、解绑原因强制弹窗（空原因零出网拦截/携原因出网）。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { bindings } from '@/api/iot';
import type { BindingVO } from '@/api/iot';
import { permDirective } from '@/directives/perm';
import BindingManageView from './BindingManageView.vue';

vi.mock('@/api/iot', () => ({
  BINDING_STATUS_LABELS: {
    BOUND: '绑定中',
    UNBINDING: '解绑中',
    UNBOUND: '已解绑',
  },
  BIND_TYPE_LABELS: {
    FIXED: '固定式',
    MOBILE: '移动式',
  },
  IOT_WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  bindings: { list: vi.fn(), bind: vi.fn(), unbind: vi.fn() },
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

/** 绑定行（五元组快照；状态可覆写） */
function bindingMock(partial: Partial<BindingVO> = {}): BindingVO {
  return {
    id: '901',
    deviceId: 'dev-001',
    patientId: '1932000000000000001',
    visitId: 'I2026092500001',
    bedId: '903',
    wardId: 'W01',
    bindType: 'FIXED',
    status: 'BOUND',
    bindReason: '术后监护',
    boundBy: 'N001',
    boundAt: '2026-09-25T10:00:00+08:00',
    ...partial,
  };
}

/** 空绑定分页出参（page/size/total 由后端 long→string 全局口径） */
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

describe('设备绑定页', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #33）：真实 IOT_ADMIN 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u5',
          loginName: 'iotadmindemo',
          displayName: '陆物联',
          orgId: null,
          roles: ['iot_admin'],
          permissions: ['iot:binding:btn:manage'],
        },
      }),
    );
    for (const fn of [bindings.list, bindings.bind, bindings.unbind]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(bindings.list).mockResolvedValue(emptyPage());
  });

  it('绑定列表加载渲染五元组列与状态三态徽标（状态类机器判据）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [
        bindingMock({ id: '901', deviceId: 'dev-001', status: 'BOUND' }),
        bindingMock({ id: '902', deviceId: 'dev-002', status: 'UNBINDING' }),
        bindingMock({
          id: '903',
          deviceId: 'dev-003',
          status: 'UNBOUND',
          bindType: 'MOBILE',
          unbindReason: '转床',
        }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mount(BindingManageView);
    await flushPromises();
    const text = wrapper.text();
    // 五元组列可见（设备/患者/就诊/床位/病区）
    expect(text).toContain('dev-001');
    expect(text).toContain('1932000000000000001');
    expect(text).toContain('I2026092500001');
    expect(text).toContain('903');
    expect(text).toContain('W01');
    expect(text).toContain('固定式');
    expect(text).toContain('移动式');
    // 解绑原因留痕可见（历史只增口径）
    expect(text).toContain('转床');
    // 三态色标状态类契约（机器判据；色值经语义 token 承载）
    expect(wrapper.find('.fuy-binding-tag--bound').exists()).toBe(true);
    expect(wrapper.find('.fuy-binding-tag--unbinding').exists()).toBe(true);
    expect(wrapper.find('.fuy-binding-tag--unbound').exists()).toBe(true);
  });

  it('绑定表单 visit_id 非 14 位 I 格式零出网显式校验拦截', async () => {
    const wrapper = mount(BindingManageView);
    await flushPromises();
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-009');
    await wrapper.find('input[aria-label="患者号"]').setValue('1932000000000000009');
    // 14 位校验：缺 I 前缀且位数不足
    await wrapper.find('input[aria-label="就诊号"]').setValue('I123');
    await clickButton(wrapper, '确认绑定');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith(
      '就诊号应以 I 开头共 14 位（I+日期+流水）',
    );
    expect(bindings.bind).not.toHaveBeenCalled();
  });

  it('绑定提交出网携五元组并刷新绑定列表', async () => {
    vi.mocked(bindings.bind).mockResolvedValue(bindingMock({ deviceId: 'dev-009' }));
    const wrapper = mount(BindingManageView);
    await flushPromises();
    await wrapper.find('input[aria-label="设备 ID"]').setValue('dev-009');
    await wrapper.find('input[aria-label="患者号"]').setValue('1932000000000000009');
    await wrapper.find('input[aria-label="就诊号"]').setValue('I2026092600009');
    await wrapper.find('input[aria-label="床位号"]').setValue('905');
    await wrapper.find('select[aria-label="绑定模式"]').setValue('MOBILE');
    await clickButton(wrapper, '确认绑定');
    await flushPromises();
    expect(bindings.bind).toHaveBeenCalledWith({
      deviceId: 'dev-009',
      patientId: '1932000000000000009',
      visitId: 'I2026092600009',
      bedId: '905',
      wardId: 'W01',
      bindType: 'MOBILE',
      bindReason: undefined,
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(bindings.list).toHaveBeenCalledTimes(2);
  });

  it('解绑原因强制：空原因零出网拦截', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ id: '901', deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mount(BindingManageView);
    await flushPromises();
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    // 不填原因直接确认 → 零出网拦截
    await clickButton(wrapper, '确认解绑');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写解绑原因');
    expect(bindings.unbind).not.toHaveBeenCalled();
  });

  it('解绑携原因出网并刷新绑定列表（解绑受理置 UNBINDING 由后端承载）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ id: '901', deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mount(BindingManageView);
    await flushPromises();
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    await wrapper.find('textarea[aria-label="解绑原因"]').setValue('转床更换床位');
    await clickButton(wrapper, '确认解绑');
    await flushPromises();
    expect(bindings.unbind).toHaveBeenCalledWith('dev-001', { reason: '转床更换床位' });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（list 第二次调用）
    expect(bindings.list).toHaveBeenCalledTimes(2);
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(BindingManageView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 iot:binding:btn:manage 时绑定/解绑出网口全隐藏（D-34）', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 绑定表单提交口无码隐藏（右栏表单可填不可出网）
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('确认绑定');
    // 解绑入口（盘点口径不挂码）可开窗读上下文，解绑出网口无码隐藏
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('确认解绑');
    // 列表读面不受元素码影响（绑定行仍渲染）
    expect(wrapper.text()).toContain('dev-001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:binding:btn:manage 时确认绑定与确认解绑可见', async () => {
    vi.mocked(bindings.list).mockResolvedValue({
      content: [bindingMock({ deviceId: 'dev-001', status: 'BOUND' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('确认绑定');
    await clickRowButton(wrapper, 'dev-001', '解绑');
    await flushPromises();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('确认解绑');
    wrapper.unmount();
  });
});
