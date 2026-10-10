// 一日清单页单测（FU-M13-03 前端面 · 暖纸卷宗 P09 蓝图重排）：既有业务断言全量保留（未选日期
// 前置拦截不出网、三层勾稽渲染 + fenToYuanDisplay 元文本、勾稽绿标），并按逐页蓝图 P09 spec
// 锚点新增构图断言——门牌页首锚点、检索卡 .fuy-filter、双列勾稽汇聚（大类汇总升右列与合计条
// 同列纵叠）、数字规线 .fuy-num、空态 .fuy-empty 脸禁纸箱插画、首查骨架两态保留、勾稽红标
// 异常路。其中门牌/双列/空态脸为构图性断言（先红后绿）；骨架/红标/数字规线为保留性锚（对
// 重排前实现即绿，防换脸过程丢失既有分支与等宽口径）。api mock 承载不打真实网络；门牌批注行
// 取 auth 会话真值（pinia 播种，门诊域 spec 同款基建）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElDatePicker } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { dailyList } from '@/api/billing';
import type { DailyListVO } from '@/api/billing';
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

/** 查询前置：填就诊号 + 经 emit 回填日期（jsdom 无日历弹层，既有口径）后点击查询 */
async function fillAndQuery(wrapper: VueWrapper, visitNo: string, day: string): Promise<void> {
  await wrapper.find('input[placeholder="就诊号"]').setValue(visitNo);
  wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', day);
  await flushPromises();
  await clickButton(wrapper, '查询');
}

/** 三层勾稽一致回包（Σ明细=Σ大类=合计=5500 分；分单位 string 承载） */
function reconciledMock(): DailyListVO {
  return {
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
  };
}

describe('一日清单页', () => {
  beforeEach(() => {
    vi.mocked(dailyList).mockReset();
    // 门牌批注行「谁」取会话显示名真值：激活 pinia 并播种会话（auth store 挂载时自恢复）
    setActivePinia(createPinia());
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, displayName: '收费员甲', permissions: [] },
      }),
    );
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
    vi.mocked(dailyList).mockResolvedValue(reconciledMock());
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

  it('门牌页首锚点：衬线标题「一日清单」/ 签认人·时刻批注行（蓝图 P09.2）', () => {
    const wrapper = mount(DailyListView);

    // 门牌页首锚（蓝图 P09.2）：衬线标题承接原卡头页面名 + 「谁·何时」批注行取会话真值
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('一日清单');
    const note = wrapper.find('.fuy-page-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('签认人');
    expect(note.text()).toContain('收费员甲');
    expect(note.find('time').exists()).toBe(true);
    wrapper.unmount();
  });

  it('双列勾稽汇聚：明细左列 + 大类汇总右列，合计条与汇总同列纵叠（蓝图 P09.2 禁照抄点）', async () => {
    vi.mocked(dailyList).mockResolvedValue(reconciledMock());
    const wrapper = mount(DailyListView);
    await fillAndQuery(wrapper, 'V001', '2026-09-18');

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('WEST_DRUG');
    });

    // 双列锚（蓝图 P09.3 grid 2fr/1fr）：左列费用明细、右列大类汇总两列并存
    const detail = wrapper.find('.daily-list-detail');
    const summary = wrapper.find('.daily-list-summary');
    expect(detail.exists()).toBe(true);
    expect(summary.exists()).toBe(true);
    expect(detail.find('table').exists()).toBe(true);
    expect(summary.find('table').exists()).toBe(true);
    // 勾稽视线汇聚：三分区合计条挂在大类汇总同列（Σ大类紧贴合计，禁再回落纵叠第三段）
    expect(summary.find('.fuy-total-strip').exists()).toBe(true);
    // 压轴既有（蓝图 P09.4）：合计条 .fuy-stagger 延迟档 index 1=40ms 保留
    const stripSlot = summary.find('.fuy-stagger');
    expect(stripSlot.exists()).toBe(true);
    expect(stripSlot.attributes('style')).toContain('--fuy-stagger-index: 1');
    wrapper.unmount();
  });

  it('数字规线：合计值与明细数字列走 .fuy-num 等宽（蓝图 P09.3）', async () => {
    vi.mocked(dailyList).mockResolvedValue(reconciledMock());
    const wrapper = mount(DailyListView);
    await fillAndQuery(wrapper, 'V001', '2026-09-18');

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('55.00');
    });

    // 合计数字等宽：三分区合计条内 grand 值承载 .fuy-num（⑨.4-⑥ 三分区强调口径）
    expect(wrapper.find('.fuy-total-strip .fuy-num').exists()).toBe(true);
    // 明细表单价/数量/金额数字列经 class-name 全列落 .fuy-num（⑧.4 数字列规线重排零丢失）
    expect(wrapper.findAll('td.fuy-num').length).toBeGreaterThan(0);
    wrapper.unmount();
  });

  it('初始未查询空态走 .fuy-empty 脸（禁纸箱插画）并给操作指引', () => {
    const wrapper = mount(DailyListView);

    // 空态脸锚（契约 ⑥/蓝图 P09.2）：el-empty 默认纸箱插画不渲染，未查询引导换 .fuy-empty 脸，
    // 主句合「尚无」语法 + 说明句保留既有指路文案
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.attributes('role')).toBe('status');
    expect(empty.find('.fuy-empty-title').text()).toBe('尚无清单结果');
    expect(empty.find('.fuy-empty-hint').text()).toContain('输入就诊号与清单日期');
    wrapper.unmount();
  });

  it('首查骨架态：清单在途且无结果时呈现骨架而非空态（两态分支保留）', async () => {
    // 永挂起的清单请求：稳定复现首查在途窗口
    vi.mocked(dailyList).mockImplementation(() => new Promise<DailyListVO>(() => {}));
    const wrapper = mount(DailyListView);
    await fillAndQuery(wrapper, 'V001', '2026-09-18');

    // 首查骨架（§4.4 二分：首屏骨架/结果区刷新 v-loading）优先于引导空态呈现
    await vi.waitFor(() => {
      expect(wrapper.find('.el-skeleton').exists()).toBe(true);
    });
    expect(wrapper.find('.fuy-empty').exists()).toBe(false);
    wrapper.unmount();
  });

  it('勾稽不一致时露「合计不一致，请核对」红标且绿标不显（异常路既有口径）', async () => {
    // 三层失衡样例：Σ明细(1000)≠Σ大类(2000)≠合计(3000)，前端佐证须亮红拒绿
    vi.mocked(dailyList).mockResolvedValue({
      visitId: 'V001',
      date: '2026-09-18',
      items: [
        { itemNameSnapshot: '挂号费', unitPriceSnapshot: '1000', quantity: 1, amount: '1000' },
      ],
      categories: [{ feeCategory: 'CLINIC', amount: '2000' }],
      totalAmount: '3000',
    });
    const wrapper = mount(DailyListView);
    await fillAndQuery(wrapper, 'V001', '2026-09-18');

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('合计不一致，请核对');
    });
    expect(wrapper.text()).not.toContain('已核对');
    wrapper.unmount();
  });
});
