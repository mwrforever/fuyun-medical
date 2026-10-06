// 退药受理页单测（FU-M06-05 前端面）：实物退缺追溯码被前端前置拦截不出网（「无码不结」
// 受理面）、提交以回传单号+受理模式+逐行集调 createDispenseReturn（单号与明细锚点均由
// 后端回传承载，页面不自造）；提交在途防抖（W-22⑥）：慢响应窗口内按钮禁用且二次点击零出网。
// api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { createDispenseReturn, listDispenses } from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';
import { permDirective } from '@/directives/perm';
import DispenseReturnView from './DispenseReturnView.vue';

vi.mock('@/api/pharmacy', () => ({
  listDispenses: vi.fn(),
  createDispenseReturn: vi.fn(),
}));

// 仅替身 ElMessage（前置拦截提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖，补空壳避免挂载即抛（测试环境无真实 resize）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 按按钮文案点击 el-button（避免 DOM 结构序号耦合，patient 三页 spec 同款） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位 el-button 包装（在途断言用；非点击入口） */
function findButton(wrapper: VueWrapper, text: string): DOMWrapper<Element> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

/** 构造已检回的发药单（明细一行；单号/处方号/明细 id 定值承载回传出参） */
function dispenseMock(): DispenseVO {
  return {
    id: '100',
    dispenseNo: 'D1',
    dispenseType: 'OUTPATIENT',
    rxNo: 'RX-20260919-001',
    patientId: '1932000000000000002',
    visitId: 'V001',
    storehouse: 'MAIN',
    picker: 'admin',
    verifier: 'reviewer',
    issuer: 'admin',
    status: 'ISSUED',
    items: [
      {
        id: '10',
        prescriptionItemId: '5',
        itemCode: 'C001',
        requestedQuantity: '2',
        issuedQuantity: '2',
        returnedQuantity: '0',
        batchNo: 'B20260101',
        traceCodes: ['T1', 'T2'],
        itemStatus: 'ISSUED',
      },
    ],
  };
}

/** 挂载并检回发药单（处方号录入 → 检索 → 回显编辑行） */
async function mountWithSheet(): Promise<VueWrapper> {
  const wrapper = mount(DispenseReturnView);
  await wrapper.find('input[placeholder="处方号"]').setValue('RX-20260919-001');
  await clickButton(wrapper, '检索发药单');
  await flushPromises();
  return wrapper;
}

describe('退药受理页', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #21）：真实 PHARMACIST 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u9',
          loginName: 'pharmacistdemo',
          displayName: '王药师',
          orgId: null,
          roles: ['pharmacist'],
          permissions: ['pharmacy:dispense:btn:return'],
        },
      }),
    );
    vi.mocked(listDispenses).mockReset();
    vi.mocked(createDispenseReturn).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(createDispenseReturn).mockResolvedValue();
  });

  it('实物退缺追溯码被前端拦截不出网', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 模式保持缺省 ISSUED_RETURN（实物退），追溯码留空直接提交
    await clickButton(wrapper, '提交退药');
    await flushPromises();

    // 断言业务结果：无码不结前置拦截 + 零出网
    expect(vi.mocked(createDispenseReturn)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('提交以单号+模式+行集调 createDispenseReturn', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountWithSheet();

    // 逐码录入齐备（退药数量保持默认 1）→ 提交
    await wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').setValue('T1,T2');
    await clickButton(wrapper, '提交退药');
    await flushPromises();

    // 出网载荷：回传单号 + 缺省实物退模式 + 行集（处方明细 id string 原样，追溯码拆分）
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledWith({
      dispenseNo: 'D1',
      mode: 'ISSUED_RETURN',
      items: [
        {
          prescriptionItemId: '5',
          returnQuantity: '1',
          traceCodes: ['T1', 'T2'],
        },
      ],
    });
    wrapper.unmount();
  });

  it('提交退药在途：按钮禁用且二次点击零出网，结束后复位可再点（W-22⑥ 防抖）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    // 慢响应：提交挂起至用例放行，稳定复现「请求在途」窗口（双击的第二个事件必落在窗口内）
    let releaseSubmit: () => void = () => {};
    vi.mocked(createDispenseReturn).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releaseSubmit = resolve;
        }),
    );
    const wrapper = await mountWithSheet();

    // 逐码录入齐备（退药数量保持默认 1）→ 首击提交（在途窗开启）
    await wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').setValue('T1,T2');
    await clickButton(wrapper, '提交退药');
    await flushPromises();

    // 在途态：按钮原生 disabled 置位（:disabled 与 :loading 同挂在途标志）
    expect(findButton(wrapper, '提交退药').attributes('disabled')).toBeDefined();
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledTimes(1);

    // 在途窗口内二次点击：handler 入口守卫 + 组件 loading 双保险，零第二次出网
    await clickButton(wrapper, '提交退药');
    await flushPromises();
    expect(vi.mocked(createDispenseReturn)).toHaveBeenCalledTimes(1);

    // 在途结束后恢复可点（防抖标记须经 finally 复位，禁把按钮永久锁死）
    releaseSubmit();
    await flushPromises();
    expect(findButton(wrapper, '提交退药').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DispenseReturnView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  /** 权限态挂载并检回发药单（检索链路不受元素码影响，读面照常回显） */
  async function mountViewWithSheet(): Promise<VueWrapper> {
    const wrapper = mountView();
    await wrapper.find('input[placeholder="处方号"]').setValue('RX-20260919-001');
    await clickButton(wrapper, '检索发药单');
    await flushPromises();
    return wrapper;
  }

  it('PR-4F 权限态：会话无 pharmacy:dispense:btn:return 时提交退药隐藏（D-34 DOM 移除）', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u9' } }),
    );
    const wrapper = await mountViewWithSheet();
    // 发药单读面回显不受元素码影响；提交入口经 v-perm DOM 移除（检索按钮为读面不挂码）
    expect(wrapper.text()).toContain('D1');
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('提交退药');
    expect(buttonTexts).toContain('检索发药单');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 pharmacy:dispense:btn:return 时提交退药可见', async () => {
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock()]);
    const wrapper = await mountViewWithSheet();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('提交退药');
    wrapper.unmount();
  });
});
