// 住院审方台单测（/pharmacy/review，M06 审方薄切片工作台 · 暖纸卷宗 P13 蓝图重排）：
// 待审任务列表加载（医嘱号/患者摘要/药品明细/申请科室）、驳回弹窗意见必填零出网校验、
// 审方通过出网并刷新列表、驳回意见经表单录入后出网（trim 后原样下发）并关窗刷新、
// PR-4F 权限态（pharmacy:review:btn:audit 有码可见/无码 DOM 移除）。
// api mock 承载零出网（vi.mock('@/api/pharmacy') 整模块替身），不打真实网络；
// 断言业务结果不绑定实现细节。
// 暖纸换脸新增锚点（蓝图 P13.2/P13.7，换脸不换业务）：本页为域内唯一 native→EP 控件替换
// 页——状态过滤 native select → el-select（popper 挂 fuy-snap-popper、native select 零残留）、
// 驳回意见 native textarea → el-input type=textarea；门牌页首（衬线标题/签认人·时刻批注行/
// 待审 amber 计数胶囊=行集本地 reduce 零额外出网）；任务卡 fuy-card/fuy-dense 挂卡容器 +
// 筛选 .fuy-filter + 卡头计数；驳回弹窗 .fuy-dialog + .fuy-form 挂类；页根不挂 .fuy-stagger
// （契约 ⑦.4 迁页内区块，stagger 两档=门牌 0/任务卡 1，P01 先例）；队列表空态 .fuy-empty 脸
//（「暂无」语法，禁默认纸箱插画）。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElInput, ElMessage, ElSelect } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { reviewTasks } from '@/api/pharmacy';
import type { ReviewTaskVO } from '@/api/pharmacy';
import { permDirective } from '@/directives/perm';
import ReviewTaskView from './ReviewTaskView.vue';

vi.mock('@/api/pharmacy', () => ({
  REVIEW_TASK_STATUS_OPTIONS: [
    { code: 'PENDING', label: '待审' },
    { code: 'APPROVED', label: '已通过' },
    { code: 'REJECTED', label: '已驳回' },
  ],
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
  /** 文件级 Pinia：v-perm 指令与门牌签认人读取 auth 会话 store，每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #24）：真实 PHARMACIST 会话经登录契约导出含码+显示名，既有用例语义不变
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
          permissions: ['pharmacy:review:btn:audit'],
        },
      }),
    );
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

  it('状态过滤 EP 化（蓝图 P13.1 唯一控件替换页）：el-select 挂 fuy-snap-popper 且 native select 零残留，切换按新状态出网', async () => {
    const wrapper = mount(ReviewTaskView);
    await flushPromises();

    // native select 已退役（EP 控件替换锚，蓝图 P13.7）
    expect(wrapper.find('select').exists()).toBe(false);
    const statusSelect = wrapper.findComponent(ElSelect);
    expect(statusSelect.exists()).toBe(true);
    // 显式 popper 统一挂硬 snap 脸（契约 ⑤#5/⑦.5）
    expect(statusSelect.props('popperClass')).toBe('fuy-snap-popper');

    // 切换状态过滤 → 按新状态出网（既有 onFilterChange 语义，page 恒 0、size 50 不变）
    statusSelect.vm.$emit('update:modelValue', 'APPROVED');
    statusSelect.vm.$emit('change');
    await flushPromises();
    expect(reviewTasks.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'APPROVED', page: 0, size: 50 }),
    );
    wrapper.unmount();
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

  it('驳回意见经表单录入后确认出网（trim 原样下发）、弹窗关闭并刷新列表', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    vi.mocked(reviewTasks.reject).mockResolvedValue();
    const wrapper = mount(ReviewTaskView);
    await flushPromises();
    // 行内「驳回」打开弹窗 → 弹窗回显目标医嘱号
    await clickButton(wrapper, '驳回');
    expect(wrapper.find('.fuy-dialog').text()).toContain('MO2026092500001');
    // 意见经 el-input textarea 录入（EP 控件替换锚：v-model 接线与 native 等价）
    await wrapper.find('textarea').setValue('  溶媒选择不适宜  ');
    await clickButton(wrapper, '确认驳回');
    await flushPromises();
    // 意见 trim 后随驳回请求原样下发（PH-1020 服务端兜底同语义，前端显式校验前置）
    expect(reviewTasks.reject).toHaveBeenCalledTimes(1);
    expect(reviewTasks.reject).toHaveBeenCalledWith('9001', { opinion: '溶媒选择不适宜' });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith(
      expect.stringContaining('MO2026092500001'),
    );
    // 操作后列表刷新（初载+刷新=2 次）
    expect(reviewTasks.list).toHaveBeenCalledTimes(2);
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

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(ReviewTaskView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 pharmacy:review:btn:audit 时通过/驳回隐藏（D-34 DOM 移除）', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u9' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 待审行读面回显不受元素码影响；通过/驳回两写入口经 v-perm DOM 移除
    expect(wrapper.text()).toContain('MO2026092500001');
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('通过');
    expect(buttonTexts).not.toContain('驳回');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 pharmacy:review:btn:audit 时通过/驳回可见', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('通过');
    expect(buttonTexts).toContain('驳回');
    wrapper.unmount();
  });

  it('门牌页首锚点（蓝图 P13.2）：衬线标题「住院审方台」/ 签认人·时刻批注行 / 待审 amber 计数胶囊', () => {
    const wrapper = mount(ReviewTaskView);

    // 门牌页首锚（契约 ⑧.1）：衬线标题承接页面名 + 「谁·何时」批注行 + 时刻元素 +
    // 「先到先审」业务注记随批注行尾（蓝图 P13.2 注记归属）
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-title').text()).toBe('住院审方台');
    expect(wrapper.find('.fuy-page-note').text()).toContain('签认人 王药师');
    expect(wrapper.find('.fuy-page-note time').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-note').text()).toContain('先到先审');
    // 状态位=待审计数胶囊 amber 变体（蓝图 P13.2 待审量前置感知）
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.exists()).toBe(true);
    expect(pill.classes()).toContain('fuy-status-pill--amber');
    expect(pill.text()).toContain('待审');
    wrapper.unmount();
  });

  it('待审计数胶囊：行集本地 reduce（PENDING 行计数），零额外出网（蓝图 P13.7）', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [
        taskMock({ id: '9001' }),
        taskMock({ id: '9002', m04OrderNo: 'MO2026092500002', status: 'APPROVED' }),
        taskMock({ id: '9003', m04OrderNo: 'MO2026092500003', status: 'REJECTED' }),
      ],
      page: '0',
      size: '10',
      total: '3',
    });
    const wrapper = mount(ReviewTaskView);

    // 胶囊双通道铁律（契约 ⑧.6）：文字 + 数字并陈；等待行集异步回填后计数兑现
    await vi.waitFor(() => {
      expect(wrapper.find('.fuy-page-status .fuy-status-pill .fuy-num').text()).toBe('1');
    });

    // 零额外出网：挂载加载恰一次，胶囊计数不触发第二次清单请求（本地 reduce 语义）
    expect(vi.mocked(reviewTasks.list)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('构图挂类（蓝图 P13.2/P13.3）：.fuy-page 根不挂 stagger + stagger 两档（门牌 0/任务卡 1）+ 卷宗卡脸 + fuy-dense 挂卡 + .fuy-filter + 卡头计数 + fuy-ops-8', async () => {
    // 操作列 class-name 落在 td 上，空队列不渲染行内单元格——播种一行使操作列结构成立
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    const wrapper = mount(ReviewTaskView);
    await flushPromises();

    // 页根只挂 .fuy-page（契约 ⑦.4：stagger 迁页内区块，路由进场过渡归 MainLayout）
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');

    // stagger 两档级联容器：门牌页首 0 档、任务卡 1 档（P01 先例门牌入级联）
    const flow = wrapper.find('.fuy-stagger.review-flow');
    expect(flow.exists()).toBe(true);
    expect(flow.find('.fuy-page-head').attributes('style')).toContain('--fuy-stagger-index: 0');
    expect(flow.find('.fuy-card').attributes('style')).toContain('--fuy-stagger-index: 1');

    // 任务卡=卷宗卡脸 + fuy-dense 挂卡容器唯一密度通道（表格 size="small" 退役）
    const card = wrapper.find('.fuy-card');
    expect(card.classes()).toContain('fuy-dense');
    expect(card.find('.fuy-card-title').text()).toBe('审方任务');
    // 卡头右挂节内计数（既有「审方任务（共 N 条）」卡头标题拆题+计数两语义位）
    expect(wrapper.find('.fuy-card-extra').text()).toContain('共');
    expect(wrapper.find('.fuy-card-extra').text()).toContain('1');

    // 筛选区升 .fuy-filter 独立语义位（状态下拉 + 刷新钮右挂）
    expect(wrapper.findAll('.fuy-filter').length).toBe(1);
    expect(wrapper.find('.fuy-filter > label').text()).toBe('状态');

    // 操作列间距收窄走全局工具类（td 由 el-table 内部渲染，scoped 零匹配）
    expect(wrapper.find('.fuy-ops-8').exists()).toBe(true);
    wrapper.unmount();
  });

  it('驳回弹窗挂类（蓝图 P13.2）：.fuy-dialog 弹层脸 + .fuy-form 表单脸 + 意见录入为 el-input textarea', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    const wrapper = mount(ReviewTaskView);
    await flushPromises();
    await clickButton(wrapper, '驳回');
    await flushPromises();

    // 弹层脸锚（契约 ⑤#9/⑧.5）：el-dialog 挂 .fuy-dialog（卡面底+radius 14+shadow-lg+
    // 衬线标题），表单挂 .fuy-form（label 疏排/聚焦墨环经全局脸承载）
    const dialog = wrapper.find('.fuy-dialog');
    expect(dialog.exists()).toBe(true);
    expect(dialog.classes()).toContain('el-dialog');
    expect(dialog.find('.fuy-form').exists()).toBe(true);
    // native textarea 已归 EP 体系：意见录入 = el-input type=textarea（唯一输入件）
    expect(wrapper.findAllComponents(ElInput).length).toBe(1);
    expect(wrapper.findComponent(ElInput).props('type')).toBe('textarea');
    wrapper.unmount();
  });

  it('驳回确认钮承危险语义：弹窗 footer「确认驳回」挂 danger 实底（双红分工——状态朱承驳回，墨实底仅承常规主操作）', async () => {
    vi.mocked(reviewTasks.list).mockResolvedValue({
      content: [taskMock()],
      page: '0',
      size: '10',
      total: '1',
    });
    const wrapper = mount(ReviewTaskView);
    await flushPromises();
    await clickButton(wrapper, '驳回');
    await flushPromises();

    // 驳回=状态语义危险动作（契约 ③.1 状态朱通道：危急/停用/作废/校验错误/驳回）：
    // 弹窗确认钮与行内驳回 link 钮同语义位（danger），不得回落 primary 墨实底
    // （墨即操作仅承常规主操作——与收费域驳回 prompt danger 确认同口径）
    const dialog = wrapper.find('.fuy-dialog');
    const confirmButton = dialog.findAll('button').find((b) => b.text() === '确认驳回');
    expect(confirmButton).toBeDefined();
    expect(confirmButton?.classes()).toContain('el-button--danger');
    expect(confirmButton?.classes()).not.toContain('el-button--primary');
    // 弹窗脸一致（六弹窗族）：footer 双钮随 P07/P10 默认尺寸档（size=small 退役）
    expect(confirmButton?.classes()).not.toContain('el-button--small');
    wrapper.unmount();
  });

  it('空态脸（蓝图 P13.7/契约 ⑥）：队列表空数据走 .fuy-empty（「暂无」语法 + 下一步指引，禁默认纸箱插画）', async () => {
    const wrapper = mount(ReviewTaskView);
    await flushPromises();

    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-mark').exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无审方任务');
    expect(empty.find('.fuy-empty-hint').text()).not.toBe('');
    wrapper.unmount();
  });
});
