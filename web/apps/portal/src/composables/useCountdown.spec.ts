// 支付倒计时组合单测（EX-48 自 AppointmentView 下沉面）：启动即首拍与 mm:ss 格式、逐秒节奏、
// 剩 5 分钟预警阈值边界、到期定格 00:00 并自停、无时限重置 --:-- 不起表、重启清旧定时器、
// 组件卸载即清。fake timers 承载（vi.getTimerCount 断言定时器生命周期），消费组件挂载模式
// 供 onBeforeUnmount 钩子生效（与 bigscreen/workstation composable spec 同款组织）。
import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCountdown } from './useCountdown';

/** 消费组合实例 API 类型 */
type CountdownApi = ReturnType<typeof useCountdown>;

/** 挂载消费组件：setup 内调用 useCountdown 并回传实例 API（卸载钩子生效前提） */
function mountConsumer(): { unmount: () => void; api: CountdownApi } {
  let api!: CountdownApi;
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useCountdown();
        return () => h('div');
      },
    }),
  );
  return { unmount: () => wrapper.unmount(), api };
}

describe('支付倒计时组合', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // 系统时间锚定 10:00：与各用例时限差值即剩余时长（本地时区解析，无 UTC 偏移干扰）
    vi.setSystemTime(new Date('2026-09-28T10:00:00'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('启动即首拍：30 分钟时限立即渲染 30:00 且非预警态', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T10:30:00').toISOString());
    expect(api.countdownText.value).toBe('30:00');
    expect(api.countdownUrgent.value).toBe(false);
    unmount();
  });

  it('逐秒节奏：1 秒后递减为 29:59（单一 setInterval 在册）', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T10:30:00').toISOString());
    vi.advanceTimersByTime(1000);
    expect(api.countdownText.value).toBe('29:59');
    expect(vi.getTimerCount()).toBe(1);
    unmount();
  });

  it('预警阈值边界：剩余恰 5 分钟（300 秒）转预警色', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T10:05:00').toISOString());
    expect(api.countdownText.value).toBe('05:00');
    expect(api.countdownUrgent.value).toBe(true);
    unmount();
  });

  it('到期定格：时限已过立即 00:00 + 预警态，残表至下一拍自清（原节奏逐字保持）', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T09:59:00').toISOString());
    expect(api.countdownText.value).toBe('00:00');
    expect(api.countdownUrgent.value).toBe(true);
    // 原实现首拍先于 setInterval 建表执行，停表不触及未建之表：残表至下一拍（≤1s）自停
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(1000);
    expect(vi.getTimerCount()).toBe(0);
    expect(api.countdownText.value).toBe('00:00');
    unmount();
  });

  it('无时限兜底：重置 --:-- 不起表（后端未下发 payDeadline 场景）', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(undefined);
    expect(api.countdownText.value).toBe('--:--');
    expect(vi.getTimerCount()).toBe(0);
    unmount();
  });

  it('重启清旧：二次启动文案随新时限刷新且仅保留一个定时器（重启语义）', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T10:30:00').toISOString());
    api.startCountdown(new Date('2026-09-28T11:00:00').toISOString());
    // 分钟位不进位（60 分钟照显 60:00）——与原内联格式化逐字一致
    expect(api.countdownText.value).toBe('60:00');
    expect(vi.getTimerCount()).toBe(1);
    unmount();
  });

  it('组件卸载即清：unmount 后定时器归零（禁悬挂计时器跨实例跑秒）', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T10:30:00').toISOString());
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('手动停表：stopCountdown 清定时器且幂等（重复调用零异常）', () => {
    const { api, unmount } = mountConsumer();
    api.startCountdown(new Date('2026-09-28T10:30:00').toISOString());
    api.stopCountdown();
    api.stopCountdown();
    expect(vi.getTimerCount()).toBe(0);
    unmount();
  });
});
