// 退药受理页单测（FU-M06-05 前端面）：实物退缺追溯码被前端前置拦截不出网（「无码不结」
// 受理面）、提交以回传单号+受理模式+逐行集调 createDispenseReturn（单号与明细锚点均由
// 后端回传承载，页面不自造）。api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createDispenseReturn, listDispenses } from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';
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
  beforeEach(() => {
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
});
