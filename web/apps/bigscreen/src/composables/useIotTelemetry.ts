/**
 * IoT 遥测页面级组合（页面私有状态走 composable 不建 store，web B.2-7）：聚合 useIotStomp
 * 单例连接与遥测摘要订阅，暴露最近一帧摘要（新帧覆盖旧帧，防内存无界增长）、帧计数、当前
 * 订阅主题与连接状态。副作用清理统一在 onUnmounted（web B.2-6 / B.3-3 卸载条款）：先退订
 * 订阅句柄再断开连接。禁 import views/components（web B.2-3）。
 */
import { onUnmounted, ref } from 'vue';
import type { Ref } from 'vue';
import type { StompSubscription } from '@stomp/stompjs';
import type { TelemetrySummary } from '@/types/iot';
import {
  connect as stompConnect,
  connectionState,
  disconnect as stompDisconnect,
  subscribeTelemetrySummary,
  telemetryTopicPath,
} from './useIotStomp';
import type { IotConnectionState } from './useIotStomp';
import { warn } from '@/utils/logger';

/** 病区 ID 合法形态：纯数字字符串（与 useIotStomp 订阅侧防御同口径，此处先行拦截承载拒绝） */
const WARD_ID_PATTERN = /^\d+$/;

/**
 * 遥测页面组合入口：每组件实例独立帧状态（latestSummary/frameCount/topicPath），连接层复用
 * useIotStomp 模块级单例（connectionState 直接透传该唯一状态源，不复制状态）。
 *
 * @return latestSummary 最近一帧摘要（null=尚未收帧）；frameCount 已接收帧数（connect 归零）；
 *   topicPath 当前订阅主题路径（null=未订阅）；connectionState 连接状态（只读透传）；
 *   connect 建连订阅；disconnect 主动断开
 */
export function useIotTelemetry(): {
  latestSummary: Ref<TelemetrySummary | null>;
  frameCount: Ref<number>;
  topicPath: Ref<string | null>;
  connectionState: Readonly<Ref<IotConnectionState>>;
  connect: (token: string, wardId: string) => void;
  disconnect: () => Promise<void>;
} {
  /** 最近一帧遥测摘要（仅保留一帧，新帧覆盖；null=尚未收到任何帧） */
  const latestSummary = ref<TelemetrySummary | null>(null);
  /** 已接收帧计数（每次 connect 归零，按连接会话重新计数） */
  const frameCount = ref(0);
  /** 当前订阅主题路径（null=未订阅；链路状态区展示） */
  const topicPath = ref<string | null>(null);
  /** 在册订阅句柄（换病区重订阅/显式断开/组件卸载统一退订的执行凭据） */
  let subscription: StompSubscription | null = null;

  /**
   * 建连并订阅遥测摘要主题：校验病区 ID → 单例连接 → 订阅摘要主题（连接落地前由
   * useIotStomp 登记待订阅，onConnect 自动转正）。新会话从零计数并清空上一帧，
   * 换病区重连时旧病区帧不得残留展示。
   *
   * @param token 访问令牌，非空；来源：连接设置区输入（登录接口获取后粘贴）
   * @param wardId 病区 ID，纯数字字符串；来源：路由 query 或连接设置区输入
   */
  function connect(token: string, wardId: string): void {
    if (!WARD_ID_PATTERN.test(wardId)) {
      warn('连接被拒绝：病区 ID 必须为纯数字');
      return;
    }
    if (token === '') {
      warn('连接被拒绝：缺少访问令牌');
      return;
    }
    // 换病区重连先退订旧主题（防旧帧流入），随后新会话计数归零、最近一帧清空
    unsubscribeCurrent();
    latestSummary.value = null;
    frameCount.value = 0;
    stompConnect({ token, wardId });
    // 未连上时返回代理句柄（onConnect 落地自动转正），已连接时立即落地
    subscription = subscribeTelemetrySummary(wardId, (summary) => {
      latestSummary.value = summary; // 滚动最新态：新帧覆盖旧帧
      frameCount.value += 1;
    });
    topicPath.value = telemetryTopicPath(wardId);
  }

  /**
   * 主动断开：先退订再断连（web B.3-3；deactivate 同时取消库内建重连计划）。
   * 最近一帧保留展示供用户查看，重连时归零。
   */
  async function disconnect(): Promise<void> {
    unsubscribeCurrent();
    topicPath.value = null;
    await stompDisconnect();
  }

  /** 退订当前在册订阅（幂等：无订阅时跳过） */
  function unsubscribeCurrent(): void {
    subscription?.unsubscribe();
    subscription = null;
  }

  // web B.3-3 卸载清理条款：组件卸载先退订句柄再断开连接（void 不阻塞卸载流程）
  onUnmounted(() => {
    unsubscribeCurrent();
    void stompDisconnect();
  });

  return { latestSummary, frameCount, topicPath, connectionState, connect, disconnect };
}
