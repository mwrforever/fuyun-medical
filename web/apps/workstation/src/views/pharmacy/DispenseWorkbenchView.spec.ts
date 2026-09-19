// 药房发药工作台单测（FU-M06-04 前端面）：追溯码为空点配药被前置拦截不出网（「无码不结」
// 前端面）、配药→核对→发药签名依次出网且都以回传单号调用（单据锚点由后端回传，页面不自造）。
// api mock 承载，不打真实网络；双签同人拒绝由后端 PH-1011 硬守卫，不在前端断言。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  issueDispense,
  listDispenses,
  listPrescriptions,
  pickDispense,
  verifyDispense,
} from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';
import DispenseWorkbenchView from './DispenseWorkbenchView.vue';

vi.mock('@/api/pharmacy', () => ({
  listPrescriptions: vi.fn(),
  listDispenses: vi.fn(),
  pickDispense: vi.fn(),
  verifyDispense: vi.fn(),
  issueDispense: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（拦截提示与发药确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') },
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

/**
 * 构造发药单（status/picker 由用例指定：status 驱动按钮启停，picker 驱动双签辅助启停面——
 * 后端留痕操作者标识为 userId，配药后 picker=调配人 userId，与测试态未登录（undefined）
 * 不等即可核对/发药）。
 */
function dispenseMock(status: string): DispenseVO {
  return {
    id: '100',
    dispenseNo: 'D1',
    dispenseType: 'OUTPATIENT',
    rxNo: 'RX-20260919-001',
    patientId: '1932000000000000002',
    visitId: 'V001',
    storehouse: 'MAIN',
    picker: status === 'CREATED' ? undefined : 'pharmacist-a',
    verifier: undefined,
    issuer: undefined,
    status,
    items: [
      {
        id: '10',
        prescriptionItemId: '5',
        itemCode: 'C001',
        requestedQuantity: '2',
        issuedQuantity: '0',
        returnedQuantity: '0',
        batchNo: 'B20260101',
        traceCodes: [],
        itemStatus: 'PENDING',
      },
    ],
  };
}

describe('发药工作台', () => {
  /** 文件级 Pinia：组件读取 auth 会话 store（双签辅助启停面），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    vi.mocked(listPrescriptions).mockReset();
    vi.mocked(listDispenses).mockReset();
    vi.mocked(pickDispense).mockReset();
    vi.mocked(verifyDispense).mockReset();
    vi.mocked(issueDispense).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    // 队列兜底：待发/调剂中两段并查（loadQueue 契约），默认全空防未 stub 的 resolve 断链
    vi.mocked(listPrescriptions).mockImplementation(() =>
      Promise.resolve({ content: [], page: 0, size: 20, total: '0' }),
    );
  });

  /** 队列分段 stub：仅待发段返回一张处方（DISPENSING 段空），与 loadQueue 两段并查对齐 */
  function mockQueueWithPendingRx(): void {
    vi.mocked(listPrescriptions).mockImplementation((params) =>
      Promise.resolve(
        params.status === 'PENDING_DISPENSE'
          ? {
              content: [
                {
                  id: '1',
                  rxNo: 'RX-20260919-001',
                  patientId: '1932000000000000002',
                  visitId: 'V001',
                  status: 'PENDING_DISPENSE',
                },
              ],
              page: 0,
              size: 20,
              total: '1',
            }
          : { content: [], page: 0, size: 20, total: '0' },
      ),
    );
  }

  it('追溯码为空点配药被前置拦截不出网', async () => {
    // 队列一张处方 + CREATED 发药单：配药按钮启用，但逐码未录即须拦截
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('CREATED')]);
    const wrapper = mount(DispenseWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();

    // 选队列行 → 回显发药单
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('D1');

    // 逐码录入留空（无码不结）直接点配药
    await clickButton(wrapper, '配药');
    await flushPromises();

    // 断言业务结果：拦截不出网 + 前置提示
    expect(vi.mocked(pickDispense)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('配药→核对→发药依次出网且都以回传单号调用', async () => {
    mockQueueWithPendingRx();
    // 三段状态机：选单 CREATED → 配药后回显 PICKING → 核对后回显 PICKED
    vi.mocked(listDispenses)
      .mockResolvedValueOnce([dispenseMock('CREATED')])
      .mockResolvedValueOnce([dispenseMock('PICKING')])
      .mockResolvedValueOnce([dispenseMock('PICKED')]);
    vi.mocked(pickDispense).mockResolvedValue();
    vi.mocked(verifyDispense).mockResolvedValue();
    vi.mocked(issueDispense).mockResolvedValue();
    const wrapper = mount(DispenseWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();

    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    // 逐盒录入（逗号分隔两码）→ 配药锁定
    await wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').setValue('T1,T2');
    await clickButton(wrapper, '配药');
    await flushPromises();
    expect(vi.mocked(pickDispense)).toHaveBeenCalledWith('D1', {
      items: [{ prescriptionItemId: '5', traceCodes: ['T1', 'T2'] }],
    });

    // 核对（第二药师入口）→ 发药签名（确认弹框已替身放行）
    await clickButton(wrapper, '核对');
    await flushPromises();
    expect(vi.mocked(verifyDispense)).toHaveBeenCalledWith('D1');

    await clickButton(wrapper, '发药签名');
    await flushPromises();
    // 发药不可逆：终笔签名前必须经确认弹框（防误触面）
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '发药签名后药品出库且不可逆，确认发药？',
      '发药签名',
    );
    expect(vi.mocked(issueDispense)).toHaveBeenCalledWith('D1');

    // 出网顺序：配药 → 核对 → 发药签名（三段不可跳跃，调用序即证）
    expect(vi.mocked(pickDispense).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(verifyDispense).mock.invocationCallOrder[0],
    );
    expect(vi.mocked(verifyDispense).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(issueDispense).mock.invocationCallOrder[0],
    );
    wrapper.unmount();
  });
});
