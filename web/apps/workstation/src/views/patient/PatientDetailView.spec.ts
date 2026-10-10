// 详情页单测（FU-M02-03 前端面 · 暖纸卷宗 P03 蓝图重排）：门牌页首（患者姓名升格衬线
// 主标题 .fuy-page-title / 状态胶囊 .fuy-status-pill 语义四色 / 动作位挂 .fuy-page-status /
// 批注行=建档时间 · 档案来源）+ 档案卡三态（骨架 / 空态脸 / 内容 el-descriptions）。
// 既有业务断言全量保留：FROZEN 渲染 warning 语义 tag（冻结态直达用户；2026-10-11 主控
// 裁决对齐契约 §③.2 唯一映射：冻结=警示族）；解冻触发成对动作
// changeFreeze(patientId, false)（不带原因）并回刷档案；权限双判（PR-4F #2 / D-34）。
// 路由参数与 api mock 承载；冻结 prompt 交互链路（ElMessageBox）归人工验证，不在此断言。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { changeFreeze, getPatient } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import PatientDetailView from './PatientDetailView.vue';

// 仅替身 ElMessage（动作成功批注条断言用，拦截 jsdom 真实弹条），其余导出原样保留供
// 组件解析（PricingSettleView.spec 同款口径：解构展开不破坏 el-* 组件与真实 ElMessageBox）
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return { ...mod, ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } };
});

// useRoute 替身：路径参数 patientId 以 string 承载（雪花 ID 经路由段传入）。
// createRouter/createWebHistory 最小壳：视图挂接元素权限后引入 auth store（PR-4F #2）
// → 模块级 import @/router，替身面须覆盖其模块级调用（单测不导航）
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { patientId: '1932000000000000001' } }),
  createRouter: vi.fn(() => ({
    currentRoute: { value: { path: '/' } },
    push: vi.fn(),
    beforeEach: vi.fn(),
  })),
  createWebHistory: vi.fn(() => ({})),
}));

vi.mock('@/api/patient', () => ({
  getPatient: vi.fn(),
  changeFreeze: vi.fn(),
}));

/** 构造指定档案状态的脱敏详情（id 与路由参数一致） */
function patientWithStatus(status: string): PatientVO {
  return {
    patientId: '1932000000000000001',
    name: '张三',
    sex: '1',
    idCardNo: '110***********1234',
    mobile: '138****0000',
    status,
    realNameFlag: true,
    registerChannel: 'WINDOW',
    archiveSource: 'STANDARD',
    createdAt: '2026-09-01 10:00:00',
  };
}

describe('患者详情页', () => {
  beforeEach(() => {
    vi.mocked(getPatient).mockReset();
    vi.mocked(changeFreeze).mockReset();
    vi.mocked(ElMessage.success).mockClear();
    // PR-4F #2 后冻结/解冻按钮挂元素权限（行内与运算）：激活 pinia 并播种含码会话，
    // 既有用例语义不变（解冻按钮按权限+状态正常渲染，断言零改动）
    setActivePinia(createPinia());
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['patient:archive:btn:freeze'] },
      }),
    );
  });

  it('冻结状态档案渲染 warning 语义状态标签与 amber 语义状态胶囊', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('FROZEN'));

    const wrapper = mount(PatientDetailView);

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('已冻结');
    });
    // 断言业务结果：FROZEN → warning 琥珀 tag（2026-10-11 主控裁决对齐契约 §③.2 唯一映射：
    // 冻结=警示族，danger 印泥朱只承危急/停用/作废），证件号按脱敏原文渲染
    const tag = wrapper.find('.el-tag');
    expect(tag.classes()).toContain('el-tag--warning');
    expect(wrapper.text()).toContain('110***********1234');
    // 门牌页首状态位（P03 蓝图）：状态升 .fuy-status-pill 胶囊，语义档随 patientStatusTagType
    // 单源派生（冻结=warning → amber 胶囊，与唯一映射同源同族），胶囊必带文字（双通道铁律）
    const pill = wrapper.find('.fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--amber');
    expect(pill.text()).toBe('已冻结');
    wrapper.unmount();
  });

  it('点击解冻触发成对动作 changeFreeze(patientId, false) 并回刷档案，成功批注条提示「已解冻」', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('FROZEN'));
    vi.mocked(changeFreeze).mockResolvedValue(undefined);

    const wrapper = mount(PatientDetailView);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('解冻');
    });

    const button = wrapper.findAll('button').find((b) => b.text() === '解冻');
    if (!button) {
      throw new Error('未找到解冻按钮');
    }
    await button.trigger('click');

    // 动作成功后回刷档案（终态一致性：以服务端为准重新渲染）——回刷紧随解冻完成，纳入同一轮询等待
    await vi.waitFor(() => {
      expect(vi.mocked(changeFreeze)).toHaveBeenCalledWith('1932000000000000001', false);
      expect(vi.mocked(getPatient)).toHaveBeenCalledTimes(2);
    });
    // 页面级成功反馈=ElMessage 批注条（总则 1：页面级不钤印），文案=动词结果
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalledWith('已解冻');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 patient:archive:btn:freeze 时正常档冻结按钮不渲染（D-34 无码全隐藏）', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('NORMAL'));
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mount(PatientDetailView);
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('张三');
    });
    expect(wrapper.text()).not.toContain('冻结');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 patient:archive:btn:freeze 时正常档冻结按钮渲染', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('NORMAL'));
    const wrapper = mount(PatientDetailView);
    await vi.waitFor(() => {
      expect(wrapper.findAll('button').some((b) => b.text() === '冻结')).toBe(true);
    });
    wrapper.unmount();
  });

  it('门牌页首：患者姓名升格衬线主标题并右挂 success 语义状态胶囊与批注行', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('NORMAL'));

    const wrapper = mount(PatientDetailView);
    await vi.waitFor(() => {
      expect(wrapper.find('.fuy-page-title').text()).toBe('张三');
    });

    // 门牌页首为页面第一节（契约 ⑧.1）：患者名动态承接衬线主标题
    expect(wrapper.find('.fuy-page-head').exists()).toBe(true);
    // 状态胶囊 success 语义档 + 文字「正常」（双通道铁律：必带文字）
    const pill = wrapper.find('.fuy-status-pill');
    expect(pill.classes()).toContain('fuy-status-pill--success');
    expect(pill.text()).toBe('正常');
    // 批注行=建档时间 · 档案来源（P03 蓝图），空值 — 占位禁伪数据
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('2026-09-01 10:00:00');
    expect(note).toContain('正式档案');
    wrapper.unmount();
  });

  it('动作位挂页首（.fuy-page-status）且档案卡落主工作区（.fuy-card 全宽）', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('NORMAL'));

    const wrapper = mount(PatientDetailView);
    await vi.waitFor(() => {
      expect(wrapper.find('.fuy-page-status button').text()).toBe('冻结');
    });

    // 页根挂 .fuy-page（暖纸工作面）；stagger 挂页内区块容器不挂根（契约 ⑦.4：路由过渡
    // 已占根进场，页根再挂 .fuy-stagger 会叠出双重进场节奏——与建档/检索两页级联通律）
    const rootClasses = wrapper.element.classList;
    expect(rootClasses.contains('fuy-page')).toBe(true);
    expect(rootClasses.contains('fuy-stagger')).toBe(false);
    const flow = wrapper.find('.fuy-page > .fuy-stagger');
    expect(flow.exists()).toBe(true);
    expect(flow.find('.fuy-page-head').exists()).toBe(true);
    expect(flow.find('.fuy-card').exists()).toBe(true);
    // 冻结/解冻动作位挂页首状态位容器（蓝图：挂状态胶囊左侧 baseline 对齐）
    expect(wrapper.find('.fuy-page-status button').text()).toBe('冻结');
    // 主工作区=档案卡（.fuy-card 全宽，880 上限撤销），三态区 min-height 锁在卡体内
    expect(wrapper.find('.fuy-card').exists()).toBe(true);
    expect(wrapper.find('.fuy-card .patient-detail-body').exists()).toBe(true);
    wrapper.unmount();
  });

  it('空态脸：未查询到患者档案给恢复指引，不再渲染默认纸箱插画', async () => {
    // 档案缺失（后端 404 经拦截器弹错后 detail 拉空）→ 诚实空态脸（契约 ⑥）
    vi.mocked(getPatient).mockResolvedValue(null as unknown as PatientVO);

    const wrapper = mount(PatientDetailView);

    // 首帧防闪现：首轮加载未落定前空态脸不入场（骨架先行，同步首帧即可断言）
    expect(wrapper.find('.el-skeleton').exists()).toBe(true);
    expect(wrapper.find('.fuy-empty').exists()).toBe(false);

    await vi.waitFor(() => {
      expect(wrapper.find('.fuy-empty-title').exists()).toBe(true);
    });

    // 主句沿既有文案「未查询到患者档案」，说明句给恢复指引（可返回检索重新查询）
    expect(wrapper.find('.fuy-empty-title').text()).toBe('未查询到患者档案');
    expect(wrapper.find('.fuy-empty-hint').text()).toContain('重新查询');
    // el-empty 默认纸箱插画不再入场（空态脸 DOM 替换，非挂类压制）
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    wrapper.unmount();
  });
});
