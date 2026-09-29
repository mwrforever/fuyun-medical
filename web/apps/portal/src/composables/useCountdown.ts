/**
 * 支付时限倒计时（FU-M03-02 portal 预约出票卡，§5.4）：mm:ss 逐秒刷新，剩余 ≤5 分钟转预警色，
 * 到期定格 00:00。自 AppointmentView 下沉（EX-48 巨型组件收口，web 宪法 B.3-6 业务逻辑禁入
 * 组件）：节奏与文案与原内联实现逐字一致（单一 setInterval、启动先清旧定时器、无时限重置
 * --:-- 不起表），组件卸载 onBeforeUnmount 统一清理定时器（禁悬挂计时器跨实例跑秒）。
 */
import { onBeforeUnmount, ref } from 'vue';
import type { Ref } from 'vue';

/** 预警阈值：剩余 ≤5 分钟转预警色（§5.4 定稿口径） */
const URGENT_THRESHOLD_MS = 5 * 60 * 1000;

/** 刷新间隔：逐秒节奏（毫秒） */
const TICK_INTERVAL_MS = 1000;

/** useCountdown 返回面（页面只读消费文案/预警态，启停由出票流程驱动） */
export interface UseCountdownApi {
  /** 倒计时文案（mm:ss；未启动/无时限为 --:--，到期定格 00:00） */
  countdownText: Readonly<Ref<string>>;
  /** 预警态（剩余 ≤5 分钟或已到期为 true；无时限重置时不清翻转——与原实现一致） */
  countdownUrgent: Readonly<Ref<boolean>>;
  /** 启动倒计时（先清旧定时器再起表，重启语义；时限为空时重置 --:-- 不起表） */
  startCountdown: (deadline?: string | null) => void;
  /** 停止并清理定时器（「再约一个」复位/到期定格时调用；幂等） */
  stopCountdown: () => void;
}

/**
 * 支付倒计时组合入口（仅 setup 同步调用——内部注册卸载清理钩子）。
 *
 * @return 文案/预警态 + 启停函数；启动时以调用方传入的时限为准（出票卡 payDeadline）
 */
export function useCountdown(): UseCountdownApi {
  const countdownText = ref('--:--');
  const countdownUrgent = ref(false);
  let countdownTimer: ReturnType<typeof setInterval> | null = null;

  /** 清理定时器（幂等：无定时器时零动作） */
  function stopCountdown(): void {
    if (countdownTimer !== null) {
      clearInterval(countdownTimer);
      countdownTimer = null;
    }
  }

  /**
   * 启动倒计时：立即首拍 + 逐秒 setInterval；到期定格 00:00 并自停。
   *
   * @param deadline 支付时限（ISO 时间串，来源：出票卡 AppointmentVO.payDeadline）；
   *                 空值时仅重置文案 --:-- 不起表（后端未下发时限的兜底）
   */
  function startCountdown(deadline?: string | null): void {
    stopCountdown();
    if (!deadline) {
      countdownText.value = '--:--';
      return;
    }
    const tick = (): void => {
      const remainMs = new Date(deadline).getTime() - Date.now();
      if (remainMs <= 0) {
        countdownText.value = '00:00';
        countdownUrgent.value = true;
        stopCountdown();
        return;
      }
      const totalSeconds = Math.floor(remainMs / 1000);
      const mm = String(Math.floor(totalSeconds / 60)).padStart(2, '0');
      const ss = String(totalSeconds % 60).padStart(2, '0');
      countdownText.value = `${mm}:${ss}`;
      countdownUrgent.value = remainMs <= URGENT_THRESHOLD_MS;
    };
    tick();
    countdownTimer = setInterval(tick, TICK_INTERVAL_MS);
  }

  // 卸载即清：防出票卡销毁后定时器仍驱动已失联的 ref（§5.4 单一定时源口径）
  onBeforeUnmount(() => {
    stopCountdown();
  });

  return { countdownText, countdownUrgent, startCountdown, stopCountdown };
}
