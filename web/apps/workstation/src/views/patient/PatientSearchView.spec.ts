// 检索页单测（FU-M02-02 前端面 · 暖纸卷宗重排批次）：业务行为断言全量保留（空关键词前置
// 拦截不出网、检索结果行渲染后端脱敏文本、翻页 1 基→0 基边界转换），并按逐页蓝图 P02 spec
// 锚点新增构图断言（门牌页首 / 三段流水挂类 / .fuy-empty 空态脸两态禁纸箱 / 筛选联动 /
// 行点击雪花 ID String 归一）；api 与 ElMessage mock 承载，组件模板经 unplugin 解析器真实
// 装配（校验/渲染行为断言的前提）。
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import PatientSearchView from './PatientSearchView.vue';

// 路由跳转收集器（vi.hoisted 提升入 mock 工厂）：行点击/详情钮断言雪花 ID String 归一入路径
const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

// useRouter 替身承载视图跳转断言；createRouter/createWebHistory 最小壳：门牌批注行引入
// auth store（会话真值）→ 模块级 import @/router，替身面须覆盖其模块级调用（单测不导航）
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  createRouter: vi.fn(() => ({
    currentRoute: { value: { path: '/' } },
    push: vi.fn(),
    beforeEach: vi.fn(),
  })),
  createWebHistory: vi.fn(() => ({})),
}));

vi.mock('@/api/patient', () => ({
  searchPatients: vi.fn(),
}));

// 仅替身 ElMessage（空关键词前置提示断言用），element-plus 其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return { ...mod, ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖，补空壳避免挂载即抛（测试环境无真实 resize）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 构造检索页返回的一行脱敏档案（证件/手机号为后端 Task 6 脱敏输出原文） */
function maskedRow(): PatientVO {
  return {
    patientId: '1932000000000000001',
    name: '张三',
    sex: '1',
    idCardNo: '110***********1234',
    mobile: '138****0000',
    status: 'NORMAL',
    createdAt: '2026-09-01 10:00:00',
  };
}

/** 检索成功回包（单行/空行两形态共用壳） */
function pageOf(content: PatientVO[]): {
  content: PatientVO[];
  page: number;
  size: number;
  total: number;
} {
  return { content, page: 0, size: 20, total: content.length };
}

describe('患者检索页', () => {
  beforeEach(() => {
    vi.mocked(searchPatients).mockReset();
    pushMock.mockReset();
    // 门牌批注行「谁」取会话显示名真值：激活 pinia 并播种会话（auth store 挂载时自恢复）
    setActivePinia(createPinia());
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, displayName: '李窗口', permissions: [] },
      }),
    );
  });

  it('空关键词点击查询被前置拦截，不触达检索接口', async () => {
    const wrapper = mount(PatientSearchView);

    const button = wrapper.findAll('button').find((b) => b.text() === '查询');
    if (!button) {
      throw new Error('未找到查询按钮');
    }
    await button.trigger('click');
    await flushPromises();

    // 断言业务结果：前置校验拦截出网请求（防全量扫描拖库），仅页内提示
    expect(vi.mocked(searchPatients)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('检索后表格渲染脱敏证件号文本且页码按 0 基转换出网', async () => {
    vi.mocked(searchPatients).mockResolvedValue(pageOf([maskedRow()]));
    const wrapper = mount(PatientSearchView);

    await wrapper.find('input').setValue('110101199003074567');
    const button = wrapper.findAll('button').find((b) => b.text() === '查询');
    if (!button) {
      throw new Error('未找到查询按钮');
    }
    await button.trigger('click');

    await vi.waitFor(() => {
      // 脱敏原文透传渲染，前端不做二次处理
      expect(wrapper.text()).toContain('110***********1234');
      expect(wrapper.text()).toContain('138****0000');
    });
    // 边界转换：用户视角第一页 → 契约 page=0
    expect(vi.mocked(searchPatients)).toHaveBeenCalledWith({
      keyword: '110101199003074567',
      page: 0,
      size: 20,
    });
    wrapper.unmount();
  });

  it('门牌页首承载衬线标题与签认人·时刻批注行（会话真值非伪数据）', () => {
    const wrapper = mount(PatientSearchView);

    // 门牌页首锚（蓝图 P02.7）：衬线标题承接原卡头页面名 + 「谁·何时」批注行取会话真值
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('患者检索');
    const note = wrapper.find('.fuy-page-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('签认人');
    expect(note.text()).toContain('李窗口');
    expect(note.find('time').exists()).toBe(true);
    wrapper.unmount();
  });

  it('三段流水挂类：根 .fuy-page + 筛选卡 .fuy-filter + 结果卡 .fuy-dense 密排', () => {
    const wrapper = mount(PatientSearchView);

    // 构图锚（蓝图 P02.2/⑧）：门牌 → 独立筛选卡 → 结果卡 三段流水；fuy-dense 必须挂
    // 表格容器（密度规则为后代选择器），fuy-filter 承载筛选语义位
    expect(wrapper.find('.fuy-page').exists()).toBe(true);
    expect(wrapper.find('.fuy-filter').exists()).toBe(true);
    expect(wrapper.find('.fuy-filter').text()).toContain('关键词');
    expect(wrapper.find('.fuy-filter .fuy-filter-actions').exists()).toBe(true);
    expect(wrapper.find('.fuy-dense .el-table').exists()).toBe(true);
    wrapper.unmount();
  });

  it('初始未检索空态走 .fuy-empty 脸（禁纸箱插画）并给操作指引', () => {
    const wrapper = mount(PatientSearchView);

    // 空态脸锚（契约 ⑥/蓝图 P02.7）：el-empty 默认纸箱插画不渲染，两态门控保留——初始态
    // 主句合「尚无」语法 + 说明给下一步
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.attributes('role')).toBe('status');
    expect(empty.find('.fuy-empty-title').text()).toBe('尚无检索结果');
    expect(empty.find('.fuy-empty-hint').text()).toContain('输入证件号');
    wrapper.unmount();
  });

  it('查无结果空态脸切换为「暂无匹配患者」业务结果（searched 门控保留）', async () => {
    vi.mocked(searchPatients).mockResolvedValue(pageOf([]));
    const wrapper = mount(PatientSearchView);

    await wrapper.find('input').setValue('王不存');
    const button = wrapper.findAll('button').find((b) => b.text() === '查询');
    if (!button) {
      throw new Error('未找到查询按钮');
    }
    await button.trigger('click');

    await vi.waitFor(() => {
      expect(wrapper.find('.fuy-empty-title').text()).toBe('暂无匹配患者');
    });
    // 已检索态说明句给下一步（可更换关键词重试），仍无纸箱插画
    expect(wrapper.find('.fuy-empty-hint').text()).toContain('更换关键词');
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    wrapper.unmount();
  });

  it('检索词输入回车触发筛选联动出网（空词拦截既有口径）', async () => {
    vi.mocked(searchPatients).mockResolvedValue(pageOf([maskedRow()]));
    const wrapper = mount(PatientSearchView);

    await wrapper.find('input').setValue('13800138000');
    await wrapper.find('input').trigger('keyup.enter');

    // 联动断言：回车与查询按钮同走 handleQuery 出口（trim 后出网，0 基转换一致）
    await vi.waitFor(() => {
      expect(vi.mocked(searchPatients)).toHaveBeenCalledWith({
        keyword: '13800138000',
        page: 0,
        size: 20,
      });
    });
    wrapper.unmount();
  });

  it('行点击跳详情经雪花 ID String 归一入路由路径', async () => {
    vi.mocked(searchPatients).mockResolvedValue(pageOf([maskedRow()]));
    const wrapper = mount(PatientSearchView);

    // 先真实检索出行（空词会被前置拦截，行不渲染）
    await wrapper.find('input').setValue('110101199003074567');
    const button = wrapper.findAll('button').find((b) => b.text() === '查询');
    if (!button) {
      throw new Error('未找到查询按钮');
    }
    await button.trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('.el-table__row').exists()).toBe(true);
    });

    // 行点击与「详情」link 双通道既有：路径段以 string 承载雪花 ID（禁 number 处理）
    await wrapper.find('.el-table__row').trigger('click');
    expect(pushMock).toHaveBeenCalledWith('/patients/1932000000000000001');
    wrapper.unmount();
  });
});
