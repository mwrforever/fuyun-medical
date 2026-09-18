// 一日清单页单测（FU-M13-03 前端面）：未选日期查询被前置拦截不出网、清单渲染大类汇总且
// 三分区合计金额经 fenToYuanDisplay 元文本展示、勾稽一致露「已核对」绿标（第三层校验 UI 佐证）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElDatePicker } from 'element-plus';
import { dailyList } from '@/api/billing';
import DailyListView from './DailyListView.vue';

vi.mock('@/api/billing', () => ({
  dailyList: vi.fn(),
}));

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

describe('一日清单页', () => {
  beforeEach(() => {
    vi.mocked(dailyList).mockReset();
  });

  it('未选日期点击查询被前置拦截，不触达清单接口', async () => {
    const wrapper = mount(DailyListView);
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');

    await clickButton(wrapper, '查询');
    await flushPromises();

    // 断言业务结果：日期缺失拦截出网（防无日期全量扫描）
    expect(vi.mocked(dailyList)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('清单渲染大类汇总且合计经 fenToYuanDisplay', async () => {
    // 三层勾稽样例：Σ明细(3000+2500)=Σ大类(5500)=合计(5500)，分单位 string
    vi.mocked(dailyList).mockResolvedValue({
      visitId: 'V001',
      date: '2026-09-18',
      items: [
        {
          itemNameSnapshot: '阿司匹林肠溶片',
          unitPriceSnapshot: '1500',
          quantity: 2,
          amount: '3000',
        },
        {
          itemNameSnapshot: '头孢曲松钠',
          unitPriceSnapshot: '2500',
          quantity: 1,
          amount: '2500',
        },
      ],
      categories: [{ feeCategory: 'WEST_DRUG', amount: '5500' }],
      totalAmount: '5500',
    });
    const wrapper = mount(DailyListView);
    await wrapper.find('input[placeholder="就诊号"]').setValue('V001');
    // 日期选择器以 emit 回填 v-model（jsdom 无日历弹层交互，口径同 patient spec 的 select 替身）
    wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-09-18');
    await flushPromises();

    await clickButton(wrapper, '查询');

    await vi.waitFor(() => {
      // 明细/大类/合计三层齐备，金额全部为分→元两位小数展示串
      expect(wrapper.text()).toContain('WEST_DRUG');
      expect(wrapper.text()).toContain('55.00');
      expect(wrapper.text()).toContain('30.00');
      expect(wrapper.text()).toContain('已核对');
    });
    expect(vi.mocked(dailyList)).toHaveBeenCalledWith('V001', '2026-09-18');
    wrapper.unmount();
  });
});
