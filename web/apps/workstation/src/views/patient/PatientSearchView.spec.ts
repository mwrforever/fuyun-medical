// 检索页单测（FU-M02-02 前端面）：空关键词前置拦截不出网、检索结果行渲染后端脱敏文本
// （前端不二次处理明文）、翻页 1 基→0 基边界转换；api 与 ElMessage mock 承载，
// 组件模板经 unplugin 解析器真实装配（校验/渲染行为断言的前提）。
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import PatientSearchView from './PatientSearchView.vue';

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
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

describe('患者检索页', () => {
  beforeEach(() => {
    vi.mocked(searchPatients).mockReset();
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
    vi.mocked(searchPatients).mockResolvedValue({
      content: [maskedRow()],
      page: 0,
      size: 20,
      total: 1,
    });
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
});
