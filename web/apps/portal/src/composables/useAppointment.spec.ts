// 预约提交状态机单测（EX-48 自 AppointmentView 下沉面）：正常出票（载荷透传/出票落定/在途
// 复位）、出票成功回调先于出票卡焦点移入（时序逐字保持）、在途守卫二次触发零出网、校验失败
// 零出网回调焦点、号源未选守卫文案、OP-1007/网络异常错误文案映射、复位出票态。api mock
// 承载（与页面 spec 同桩面 '@/api/outpatient'，resolveErrorCopy 保持真实实现），不打真实网络。
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PortalApiError } from '@/api/http';
import { bookPortalAppointment } from '@/api/outpatient';
import type { AppointmentVO } from '@/api/outpatient';
import { useAppointment } from './useAppointment';
import type { UseAppointmentOptions } from './useAppointment';

vi.mock('@/api/outpatient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/outpatient')>();
  return {
    ...actual,
    bookPortalAppointment: vi.fn(),
  };
});

/** 消费组合实例 API 类型 */
type AppointmentApi = ReturnType<typeof useAppointment>;

/** 出票回执桩（apptNo 唯一化供断言） */
function ticketMock(apptNo: string): AppointmentVO {
  return { id: '601', apptNo, poolId: '501', channel: 'PORTAL', status: 'RESERVED' };
}

/** 校验失败回调桩与出票成功回调桩（挂载前建好供断言） */
interface ConsumerCallbacks {
  invalid: ReturnType<typeof vi.fn>;
  booked: ReturnType<typeof vi.fn>;
}

/**
 * 挂载消费组件：setup 内以默认注入面（校验恒过 + 固定载荷）调用 useAppointment，渲染
 * 出票卡锚 div（ref 绑 api.ticketCard，焦点移入断言前提）；options 覆写守卫/载荷用例。
 */
function mountConsumer(options: Partial<UseAppointmentOptions> = {}): {
  unmount: () => void;
  api: AppointmentApi;
  callbacks: ConsumerCallbacks;
} {
  let api!: AppointmentApi;
  const invalid = vi.fn();
  const booked = vi.fn();
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useAppointment({
          validate: () => true,
          onInvalid: invalid,
          buildPayload: () => ({
            credentialType: 'ID_CARD',
            credentialNo: '110101199001010022',
            poolId: '501',
          }),
          onBooked: booked,
          ...options,
        });
        return () => h('div', { ref: api.ticketCard, tabindex: '-1' });
      },
    }),
  );
  return { unmount: () => wrapper.unmount(), api, callbacks: { invalid, booked } };
}

describe('预约提交状态机', () => {
  beforeEach(() => {
    vi.mocked(bookPortalAppointment).mockReset();
  });

  it('正常出票：载荷透传出网、出票落定、成功回调收到出票单、在途标志复位', async () => {
    const ticket = ticketMock('OAPPT-20260928-0001');
    vi.mocked(bookPortalAppointment).mockResolvedValue(ticket);
    const { api, unmount, callbacks } = mountConsumer();

    await api.submit();
    await flushPromises();

    expect(vi.mocked(bookPortalAppointment)).toHaveBeenCalledWith({
      credentialType: 'ID_CARD',
      credentialNo: '110101199001010022',
      poolId: '501',
    });
    // ref 深层代理包裹出票单（与原组件一致），引用断言不可用，改深度等值断言业务字段
    expect(api.ticket.value).toStrictEqual(ticket);
    expect(callbacks.booked).toHaveBeenCalledWith(ticket);
    expect(api.submitting.value).toBe(false);
    expect(api.ticketError.value).toBe('');
    unmount();
  });

  it('出票时序：成功回调先于出票卡焦点移入（倒计时起表先于 focus，§5.4 原序保持）', async () => {
    vi.mocked(bookPortalAppointment).mockResolvedValue(ticketMock('OAPPT-20260928-0002'));
    const { api, unmount, callbacks } = mountConsumer();
    const anchor = api.ticketCard.value;
    if (anchor === null) {
      throw new Error('出票卡锚未挂载：消费组件渲染异常');
    }
    const focusSpy = vi.spyOn(anchor, 'focus');

    await api.submit();
    await flushPromises();

    expect(focusSpy).toHaveBeenCalledTimes(1);
    // invocationCallOrder 断言先后：booked 回调的调用序号必须小于 focus
    expect(callbacks.booked.mock.invocationCallOrder[0]).toBeLessThan(
      focusSpy.mock.invocationCallOrder[0],
    );
    unmount();
  });

  it('在途守卫：慢响应窗口内二次提交零出网且 submitting 全程为真（W-22⑥ 防重复出号）', async () => {
    let releaseBook: () => void = () => {};
    vi.mocked(bookPortalAppointment).mockImplementation(
      () =>
        new Promise<AppointmentVO>((resolve) => {
          releaseBook = () => resolve(ticketMock('OAPPT-20260928-0003'));
        }),
    );
    const { api, unmount } = mountConsumer();

    void api.submit();
    await flushPromises();
    expect(api.submitting.value).toBe(true);

    await api.submit();
    await flushPromises();
    expect(vi.mocked(bookPortalAppointment)).toHaveBeenCalledTimes(1);

    releaseBook();
    await flushPromises();
    expect(api.submitting.value).toBe(false);
    unmount();
  });

  it('校验失败兜底：validate 不过零出网，onInvalid 回调承接焦点回置', async () => {
    vi.mocked(bookPortalAppointment).mockResolvedValue(ticketMock('OAPPT-20260928-0004'));
    const { api, unmount, callbacks } = mountConsumer({ validate: () => false });

    await api.submit();
    await flushPromises();

    expect(vi.mocked(bookPortalAppointment)).not.toHaveBeenCalled();
    expect(callbacks.invalid).toHaveBeenCalledTimes(1);
    expect(api.ticket.value).toBeNull();
    unmount();
  });

  it('号源未选守卫：buildPayload 返 null 置「请先选择号源」零出网', async () => {
    vi.mocked(bookPortalAppointment).mockResolvedValue(ticketMock('OAPPT-20260928-0005'));
    const { api, unmount } = mountConsumer({ buildPayload: () => null });

    await api.submit();
    await flushPromises();

    expect(vi.mocked(bookPortalAppointment)).not.toHaveBeenCalled();
    expect(api.ticketError.value).toBe('请先选择号源');
    expect(api.ticket.value).toBeNull();
    unmount();
  });

  it('OP-1007 错误码映射：PortalApiError 转译为患者可读文案且不出票（真实 resolveErrorCopy）', async () => {
    vi.mocked(bookPortalAppointment).mockRejectedValue(
      new PortalApiError('患者冻结拦截', 'OP-1007', 409),
    );
    const { api, unmount, callbacks } = mountConsumer();

    await api.submit();
    await flushPromises();

    expect(api.ticketError.value).toBe('该证件存在未完成缴费的挂号，请先完成缴费');
    expect(api.ticket.value).toBeNull();
    expect(callbacks.booked).not.toHaveBeenCalled();
    expect(api.submitting.value).toBe(false);
    unmount();
  });

  it('网络异常兜底：非业务错误统一「网络异常，请稍后重试」', async () => {
    vi.mocked(bookPortalAppointment).mockRejectedValue(new Error('timeout'));
    const { api, unmount } = mountConsumer();

    await api.submit();
    await flushPromises();

    expect(api.ticketError.value).toBe('网络异常，请稍后重试');
    unmount();
  });

  it('复位出票态：resetTicket 清出票结果与错误文案', async () => {
    vi.mocked(bookPortalAppointment).mockResolvedValue(ticketMock('OAPPT-20260928-0006'));
    const { api, unmount } = mountConsumer();
    await api.submit();
    await flushPromises();
    expect(api.ticket.value).not.toBeNull();

    api.resetTicket();
    expect(api.ticket.value).toBeNull();
    expect(api.ticketError.value).toBe('');
    unmount();
  });
});
