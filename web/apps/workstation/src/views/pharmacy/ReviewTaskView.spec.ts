// 住院审方台单测（/pharmacy/review，M06 审方薄切片工作台）：待审任务列表加载（医嘱号/
// 患者摘要/药品明细/申请科室）、驳回弹窗意见必填零出网校验、审方通过出网并刷新列表。
// api mock 承载零出网（vi.mock('@/api/pharmacy') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { reviewTasks } from '@/api/pharmacy';
import type { ReviewTaskVO } from '@/api/pharmacy';
import ReviewTaskView from './ReviewTaskView.vue';

vi.mock('@/api/pharmacy', () => ({
  REVIEW_TASK_STATUS_OPTIONS: [
    { code: 'PENDING', label: '待审' },
    { code: 'APPROVED', label: '已通过' },
    { code: 'REJECTED', label: '已驳回' },
  ],
  searchDrugs: vi.fn(),
  createDrug: vi.fn(),
  updateDrug: vi.fn(),
  mapInsurance: vi.fn(),
  createPrescription: vi.fn(),
  listPrescriptions: vi.fn(),
  cancelPrescription: vi.fn(),
  listDispenses: vi.fn(),
  pickDispense: vi.fn(),
  verifyDispense: vi.fn(),
  issueDispense: vi.fn(),
  createDispenseReturn: vi.fn(),
  listMedicationOccupancy: vi.fn(),
  reviewTasks: { list: vi.fn(), approve: vi.fn(), reject: vi.fn() },
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

/** 审方任务行（M06 ReviewTaskVO 契约形状；items 为药品明细拼接串） */
function taskMock(partial: Partial<ReviewTaskVO> = {}): ReviewTaskVO {
  return {
    id: '9001',
    m04OrderNo: 'MO2026092500001',
    visitId: 'I2026092500001',
    patientId: '1932000000000000001',
    freqCode: 'bid',
    items: '阿司匹林肠溶片 100mg×30',
    applyDept: 'W01',
    applyDoctor: 'D001',
    status: 'PENDING',
    pharmacistId: undefined,
    opinion: undefined,
    ...partial,
  };
}

/** 空审方分页出参（page/size/total 由后端 long→string 全局口径） */
function emptyTasks() {
  return { content: [], page: '0', size: '10', total: '0' };
}

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('住院审方台', () => {
  beforeEach(() => {
    for (const fn of [reviewTasks.list, reviewTasks.approve, reviewTasks.reject]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(reviewTasks.list).mockResolvedValue(emptyTasks());
  });

  it('待审任务列表加载渲染医嘱号/患者摘要/申请科室（默认待审态）', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [
        taskMock(),
        taskMock({ id: '9002', m04OrderNo: 'MO2026092500002', applyDept: 'W02' }),
      ],
      page: '0',
      size: '10',
      total: '2',
    });
    const wrapper = mount(ReviewTaskView);
    await flushPromises();
    const text = wrapper.text();
    // 医嘱号/患者摘要（号面脱敏口径）/申请科室逐一渲染
    expect(text).toContain('MO2026092500001');
    expect(text).toContain('MO2026092500002');
    expect(text).toContain('I2026092500001');
    expect(text).toContain('W01');
    // 药品明细渲染
    expect(text).toContain('阿司匹林肠溶片 100mg×30');
    // 默认按待审态出网
    expect(reviewTasks.list).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING', page: 0, size: 50 }),
    );
  });

  it('驳回缺意见零出网显式校验拦截', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    const wrapper = mount(ReviewTaskView);
    await flushPromises();
    // 行内「驳回」打开弹窗 → 意见留空提交 → 显式校验拦截（零出网）
    await clickButton(wrapper, '驳回');
    await clickButton(wrapper, '确认驳回');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写驳回意见（必填）');
    expect(reviewTasks.reject).not.toHaveBeenCalled();
  });

  it('审方通过出网并刷新待审列表', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    const wrapper = mount(ReviewTaskView);
    await flushPromises();
    await clickButton(wrapper, '通过');
    await flushPromises();
    expect(reviewTasks.approve).toHaveBeenCalledWith('9001');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith(
      expect.stringContaining('MO2026092500001'),
    );
    // 操作后列表刷新（初载+刷新=2 次）
    expect(reviewTasks.list).toHaveBeenCalledTimes(2);
  });
});
