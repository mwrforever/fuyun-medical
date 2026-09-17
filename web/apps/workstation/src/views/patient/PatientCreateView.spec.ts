// 建档页单测（FU-M02-01 前端面）：知情同意缺失被校验拦截不出网、预检 SUSPECT 页内提示转人工核对、
// 建档成功跳详情路由；api 层与 useRouter mock 承载（不打真实网络、不做懒加载真导航），
// 弹错口径归 http.spec 覆盖不重复断言。
import { mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElFormItem, ElSelect } from 'element-plus';
import { createPatient, matchCheck } from '@/api/patient';
import PatientCreateView from './PatientCreateView.vue';

// useRouter 替身：建档成功后的跳转以 push spy 断言
const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock('@/api/patient', () => ({
  matchCheck: vi.fn(),
  createPatient: vi.fn(),
}));

/** 按按钮文案点击 el-button（避免 DOM 结构序号耦合） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/**
 * 按表单 label 语义填充必填项（label 定位防结构耦合）：姓名 / 性别（select 以 emit 回填 v-model）。
 *
 * @param withConsent 是否同时填写知情同意凭证（用例①留空以触发校验拦截）
 */
async function fillRequired(wrapper: VueWrapper, withConsent: boolean): Promise<void> {
  const items = wrapper.findAllComponents(ElFormItem);
  const itemOf = (label: string) => items.find((item) => item.props('label') === label);
  await itemOf('姓名')?.find('input').setValue('张三');
  itemOf('性别')?.findComponent(ElSelect).vm.$emit('update:modelValue', '1');
  if (withConsent) {
    await itemOf('知情同意凭证')?.find('input').setValue('CONSENT-001');
  }
}

describe('患者建档页', () => {
  beforeEach(() => {
    vi.mocked(matchCheck).mockReset();
    vi.mocked(createPatient).mockReset();
    pushMock.mockReset();
  });

  it('知情同意凭证未填点击建档被校验拦截，不调用建档接口', async () => {
    const wrapper = mount(PatientCreateView);
    // 复现高频漏填场景：姓名/性别已填，仅缺知情同意凭证（个保法单独同意留痕必填口径）
    await fillRequired(wrapper, false);

    vi.useFakeTimers();
    await clickButton(wrapper, '建档');
    // Element Plus 校验链含 100ms 防抖：推进假时钟让 validate 拒绝链与错误文案渲染落定
    await vi.advanceTimersByTimeAsync(300);
    vi.useRealTimers();

    expect(vi.mocked(createPatient)).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('知情同意凭证引用必填');
    wrapper.unmount();
  });

  it('预检返回 SUSPECT 后页内提示疑似重复转人工核对', async () => {
    vi.mocked(matchCheck).mockResolvedValue({ outcome: 'SUSPECT' });
    const wrapper = mount(PatientCreateView);
    await fillRequired(wrapper, true);

    await clickButton(wrapper, '匹配预检');

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('疑似重复');
    });
    // 预检载荷=表单身份要素五字段（只读不落库）
    expect(vi.mocked(matchCheck)).toHaveBeenCalledWith({
      name: '张三',
      sex: '1',
      birthDate: '',
      idCardNo: '',
      mobile: '',
    });
    wrapper.unmount();
  });

  it('建档成功跳转患者详情页（candidatePatientId 即档案 id）', async () => {
    // 后端 Long→String 全局序列化：运行时 candidatePatientId 为雪花 ID 字符串
    // （D-18 根治后生成契约同为 string，与运行时单口径，无需类型断言强转）
    vi.mocked(createPatient).mockResolvedValue({
      outcome: 'NO_MATCH',
      candidatePatientId: '1932000000000000001',
    });
    const wrapper = mount(PatientCreateView);
    await fillRequired(wrapper, true);

    await clickButton(wrapper, '建档');

    await vi.waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith('/patients/1932000000000000001');
    });
    wrapper.unmount();
  });
});
