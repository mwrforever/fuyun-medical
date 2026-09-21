// 免登录预约页单测（FU-M03-02 portal 通道前端面）：渲染断言（站点名/三步卡/未到步锁定态）、
// 介质显式格式校验（违规贴字段 + 零出网——W-22⑦ 同款禁裸提交）、预约主链出网参数与出票渲染、
// OP-1003 错误码文案映射、提交中全表单禁用在途守卫（W-22⑥）。api mock 承载，不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PortalApiError } from '@/api/http';
import { bookPortalAppointment, listPortalPools } from '@/api/outpatient';
import type { AppointmentVO, NumberPoolVO } from '@/api/outpatient';
import AppointmentView from './AppointmentView.vue';

vi.mock('@/api/outpatient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/outpatient')>();
  return {
    ...actual,
    listPortalPools: vi.fn(),
    bookPortalAppointment: vi.fn(),
  };
});

function poolMock(remaining: number): NumberPoolVO {
  return {
    id: '501',
    scheduleId: '301',
    apptType: 'GENERAL',
    slotStart: '08:00',
    slotEnd: '11:30',
    totalQuota: 20,
    usedCount: 20 - remaining,
    remaining,
  };
}

/** 完成第 1 步（合规身份证号）并触发号源查询 */
async function passIdentity(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#credential-input').setValue('110101199001010022');
  await clickButton(wrapper, '下一步：选择号源');
  await flushPromises();
}

/** 按按钮文案点击（portal 原生 button） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('免登录预约页', () => {
  beforeEach(() => {
    vi.mocked(listPortalPools).mockReset();
    vi.mocked(bookPortalAppointment).mockReset();
    // 兜底空号源：防未 stub 的 resolve 断链
    vi.mocked(listPortalPools).mockResolvedValue([]);
  });

  it('渲染断言：站点名/三步卡标题/未完成步锁定态与「先完成上一步」副文案', async () => {
    const wrapper = mount(AppointmentView);
    await flushPromises();
    expect(wrapper.text()).toContain('富云患者门户');
    expect(wrapper.text()).toContain('身份确认');
    expect(wrapper.text()).toContain('选择号源');
    expect(wrapper.text()).toContain('确认出票');
    // 未完成第 1 步：第 2/3 步卡锁定（60% 透明 class）+ 副文案可预览
    const lockedCards = wrapper.findAll('.appt-card.is-locked');
    expect(lockedCards.length).toBe(2);
    expect(wrapper.text()).toContain('先完成上一步');
    wrapper.unmount();
  });

  it('证件号显式格式校验：违规文案贴字段 + aria-describedby + 零出网（W-22⑦ 口径）', async () => {
    const wrapper = mount(AppointmentView);
    await flushPromises();

    // 失焦双触发之一：填违规值 → 失焦校验
    await wrapper.find('#credential-input').setValue('123');
    await wrapper.find('#credential-input').trigger('blur');
    const error = wrapper.find('#credential-error');
    expect(error.exists()).toBe(true);
    expect(error.text()).toContain('身份证号应为 18 位数字（末位可为 X），请核对后重新输入');
    expect(wrapper.find('#credential-input').attributes('aria-describedby')).toBe(
      'credential-error',
    );

    // 提交前校验兜底：违规值直接点下一步，零出网
    await clickButton(wrapper, '下一步：选择号源');
    await flushPromises();
    expect(vi.mocked(listPortalPools)).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('预约主链：合规证件号 → 查号源 → 选卡 → 提交出票（payload 携大写证件号与 poolId）', async () => {
    vi.mocked(listPortalPools).mockResolvedValue([poolMock(6)]);
    vi.mocked(bookPortalAppointment).mockResolvedValue({
      id: '601',
      apptNo: 'OAPPT-20260921-0009',
      poolId: '501',
      apptType: 'GENERAL',
      schedDate: '2026-09-21',
      slotStart: '08:00',
      slotEnd: '11:30',
      channel: 'PORTAL',
      feeStatus: 'UNPAID',
      payDeadline: new Date(Date.now() + 30 * 60000).toISOString(),
      status: 'RESERVED',
    });
    const wrapper = mount(AppointmentView);
    await flushPromises();

    await passIdentity(wrapper);
    expect(vi.mocked(listPortalPools)).toHaveBeenCalledWith({
      deptCode: 'DEPT-INT',
      date: expect.any(String),
    });
    // 第 2 步解锁 + 号源卡渲染（余号渲染）
    expect(wrapper.find('#step-pool.is-locked').exists()).toBe(false);
    expect(wrapper.text()).toContain('余 6');
    await wrapper.find('button.appt-pool').trigger('click');
    await flushPromises();

    // 第 3 步解锁 → 提交预约 → 出票卡（apptNo 24px/.fuy-num + 倒计时文案）
    await clickButton(wrapper, '确认预约');
    await flushPromises();
    expect(vi.mocked(bookPortalAppointment)).toHaveBeenCalledWith({
      credentialType: 'ID_CARD',
      credentialNo: '110101199001010022',
      poolId: '501',
    });
    expect(wrapper.text()).toContain('预约成功');
    expect(wrapper.text()).toContain('OAPPT-20260921-0009');
    expect(wrapper.text()).toContain('内完成缴费');
    wrapper.unmount();
  });

  it('OP-1003 错误码映射：约满时展示「该号源刚被约满」患者文案并驻留所选号源卡', async () => {
    vi.mocked(listPortalPools).mockResolvedValue([poolMock(1)]);
    vi.mocked(bookPortalAppointment).mockRejectedValue(
      new PortalApiError('号源池余量不足', 'OP-1003', 409),
    );
    const wrapper = mount(AppointmentView);
    await flushPromises();

    await passIdentity(wrapper);
    await wrapper.find('button.appt-pool').trigger('click');
    await flushPromises();
    await clickButton(wrapper, '确认预约');
    await flushPromises();

    // §5.4 文案映射（OP-1003 转译，禁裸后端文案直出给患者）
    expect(wrapper.text()).toContain('该号源刚被约满，请选择其他时段');
    // 失败驻留：所选号源卡仍选中（确认按钮仍在，可改选或重试）
    expect(wrapper.find('button.appt-pool.is-selected').exists()).toBe(true);
    expect(wrapper.text()).toContain('确认预约');
    wrapper.unmount();
  });

  it('提交中全表单禁用：慢响应窗口二次点击零出网（W-22⑥ 防抖）', async () => {
    vi.mocked(listPortalPools).mockResolvedValue([poolMock(6)]);
    let releaseBook: () => void = () => {};
    vi.mocked(bookPortalAppointment).mockImplementation(
      () =>
        new Promise<AppointmentVO>((resolve) => {
          releaseBook = () =>
            resolve({
              id: '601',
              apptNo: 'OAPPT-20260921-0010',
              poolId: '501',
              channel: 'PORTAL',
              status: 'RESERVED',
            });
        }),
    );
    const wrapper = mount(AppointmentView);
    await flushPromises();
    await passIdentity(wrapper);
    await wrapper.find('button.appt-pool').trigger('click');
    await flushPromises();

    await clickButton(wrapper, '确认预约');
    await flushPromises();
    // 提交中：主按钮禁用 + 提交中文案
    const submitButton = wrapper.findAll('button').find((b) => b.text().includes('提交中'));
    expect(submitButton).toBeDefined();
    expect(submitButton?.attributes('disabled')).toBeDefined();
    // 在途窗口内介质输入也禁用（提交中全表单禁用）
    expect(wrapper.find('#credential-input').attributes('disabled')).toBeDefined();
    // 二次点击零第二次出网
    await wrapper.findAll('button')[0].trigger('click');
    await flushPromises();
    expect(vi.mocked(bookPortalAppointment)).toHaveBeenCalledTimes(1);

    releaseBook();
    await flushPromises();
    wrapper.unmount();
  });
});
