/**
 * portal 预约提交状态机（FU-M03-02 §5.4/§6.8）：在途守卫（提交中重复触发零出网）→ 显式校验
 * 兜底 → 号源未选守卫 → 出网 bookPortalAppointment → 出票/错误码文案映射。自 AppointmentView
 * 下沉（EX-48 巨型组件收口，web 宪法 B.3-6）：提交时序与原内联实现逐字一致（成功路径倒计时
 * 启动先于出票卡焦点移入、失败驻留错误文案、finally 复位 submitting），校验/载荷/焦点等视图
 * 动作经回调注入，出网只走 api 层（A.3 唯一出口）。
 */
import { nextTick, ref } from 'vue';
import type { Ref } from 'vue';
import { bookPortalAppointment, resolveErrorCopy } from '@/api/outpatient';
import type { AppointmentVO, PortalAppointmentRequest } from '@/api/outpatient';
import { PortalApiError } from '@/api/http';

/** useAppointment 注入面（视图动作回调化，composable 不反向依赖视图层） */
export interface UseAppointmentOptions {
  /** 提交前显式校验兜底（§5.4 双触发之一；false=校验失败，中止提交并回调 onInvalid） */
  validate: () => boolean;
  /** 校验失败回调（焦点回置字段等视图动作；await 后中止，与原内联时序一致） */
  onInvalid: () => void | Promise<void>;
  /** 提交载荷构建（null=号源未选：置「请先选择号源」守卫文案，零出网） */
  buildPayload: () => PortalAppointmentRequest | null;
  /** 出票成功回调（支付倒计时启动等；出票落定后、焦点移入前触发） */
  onBooked?: (ticket: AppointmentVO) => void;
}

/** useAppointment 返回面（页面消费 submitting/ticket 驱动禁用态与出票卡渲染） */
export interface UseAppointmentApi {
  /** 提交中标志（true=在途，全表单禁用防重复出号） */
  submitting: Readonly<Ref<boolean>>;
  /** 出票结果（null=未出票；出票卡数据源，替换第 3 步表单区渲染） */
  ticket: Readonly<Ref<AppointmentVO | null>>;
  /** 提交失败文案（错误码映射后的患者可读文案，贴错误条展示；空串=无错误） */
  ticketError: Readonly<Ref<string>>;
  /** 出票卡 DOM 锚（模板 ref 绑定；出票成功后焦点移入，§5.4 无障碍口径） */
  ticketCard: Ref<HTMLElement | null>;
  /** 提交预约（幂等守卫：在途重复调用零出网） */
  submit: () => Promise<void>;
  /** 复位出票态（「再约一个」：清出票结果与错误文案） */
  resetTicket: () => void;
}

/**
 * 预约提交状态机组合入口（仅 setup 同步调用）。
 *
 * @param options 校验/载荷/视图动作回调（见各字段注释）
 * @return 提交态/出票态 + 提交与复位函数
 */
export function useAppointment(options: UseAppointmentOptions): UseAppointmentApi {
  const submitting = ref(false);
  const ticket = ref<AppointmentVO | null>(null);
  const ticketError = ref('');
  const ticketCard = ref<HTMLElement | null>(null);

  /**
   * 提交预约：在途守卫 → 校验兜底（失败回调焦点回置）→ 号源未选守卫 → 出网 → 出票卡
   * （倒计时回调先于焦点移入）→ 失败按错误码映射文案驻留；finally 无条件复位在途标志。
   */
  async function submit(): Promise<void> {
    if (submitting.value) {
      return;
    }
    if (!options.validate()) {
      await options.onInvalid();
      return;
    }
    const payload = options.buildPayload();
    if (payload === null) {
      ticketError.value = '请先选择号源';
      return;
    }
    ticketError.value = '';
    submitting.value = true;
    try {
      ticket.value = await bookPortalAppointment(payload);
      options.onBooked?.(ticket.value);
      await nextTick();
      ticketCard.value?.focus();
    } catch (error: unknown) {
      ticketError.value =
        error instanceof PortalApiError ? resolveErrorCopy(error) : '网络异常，请稍后重试';
    } finally {
      submitting.value = false;
    }
  }

  /** 复位出票态（倒计时停表归调用方编排——本组合不持有定时器） */
  function resetTicket(): void {
    ticket.value = null;
    ticketError.value = '';
  }

  return { submitting, ticket, ticketError, ticketCard, submit, resetTicket };
}
