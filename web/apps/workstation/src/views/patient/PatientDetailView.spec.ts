// 详情页单测（FU-M02-03 前端面）：FROZEN 状态渲染 danger 语义 tag（冻结态直达用户）；
// 解冻按钮触发成对动作 changeFreeze(patientId, false)（不带原因）并回刷档案。
// 路由参数与 api mock 承载；冻结 prompt 交互链路（ElMessageBox）归人工验证，不在此断言。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { changeFreeze, getPatient } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import PatientDetailView from './PatientDetailView.vue';

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

  it('冻结状态档案渲染 danger 语义状态标签', async () => {
    vi.mocked(getPatient).mockResolvedValue(patientWithStatus('FROZEN'));

    const wrapper = mount(PatientDetailView);

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('已冻结');
    });
    // 断言业务结果：FROZEN → danger 红色 tag（与检索页同词表口径），证件号按脱敏原文渲染
    const tag = wrapper.find('.el-tag');
    expect(tag.classes()).toContain('el-tag--danger');
    expect(wrapper.text()).toContain('110***********1234');
    wrapper.unmount();
  });

  it('点击解冻触发成对动作 changeFreeze(patientId, false) 并回刷档案', async () => {
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
});
