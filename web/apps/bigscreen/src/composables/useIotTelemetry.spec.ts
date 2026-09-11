// 遥测页面级组合单测（BRIEF-PR5-01 §2.3）：真实链路 useIotTelemetry → useIotStomp → 桩 Client
//（vi.mock('@stomp/stompjs')，禁真实建连），覆盖连接落地后收帧覆盖更新与帧计数递增、断开再
// 连接的状态机往返、组件卸载统一退订三组断言。每用例 resetModules 重置模块级单例保证隔离。
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** mock 捕获状态（vi.hoisted：vi.mock 工厂提升后仍可引用；逐用例手动复位） */
const h2 = vi.hoisted(() => ({
  configs: [] as Record<string, unknown>[],
  deactivateCalls: 0,
  /** 捕获的订阅：destination + 帧回调（用例内投递合法帧） */
  subscriptions: [] as { destination: string; callback: (message: { body: string }) => void }[],
  unsubscribeCalls: 0,
}));

vi.mock('@stomp/stompjs', () => {
  /** 捕获构造配置与订阅行为的桩 Client（无任何真实网络行为） */
  class MockClient {
    connected = false;
    constructor(config: Record<string, unknown>) {
      h2.configs.push(config);
    }
    activate(): void {}
    deactivate(): Promise<void> {
      h2.deactivateCalls += 1;
      return Promise.resolve();
    }
    subscribe(
      destination: string,
      callback: (message: { body: string }) => void,
    ): { id: string; destination: string; unsubscribe: () => void } {
      h2.subscriptions.push({ destination, callback });
      return { id: 'sub-0', destination, unsubscribe: () => void (h2.unsubscribeCalls += 1) };
    }
  }
  return { Client: MockClient };
});

// 模块级单例（Client 缓存/订阅在册/状态 ref）经 resetModules 重置：每用例全新模块实例
let telemetry: typeof import('./useIotTelemetry');

beforeEach(async () => {
  vi.clearAllMocks();
  sessionStorage.clear();
  h2.configs = [];
  h2.deactivateCalls = 0;
  h2.subscriptions = [];
  h2.unsubscribeCalls = 0;
  vi.resetModules();
  telemetry = await import('./useIotTelemetry');
});

/** 取最近一次捕获的 Client 构造配置（用例内已保证至少一次建连） */
function lastConfig(): Record<string, unknown> {
  const config = h2.configs.at(-1);
  expect(config).toBeDefined();
  return config as Record<string, unknown>;
}

/** 消费组件实例 API 类型（useIotTelemetry 返回值） */
type TelemetryApi = ReturnType<typeof telemetry.useIotTelemetry>;

/** 挂载消费组件：setup 内调用 useIotTelemetry 并回传实例 API 供断言（onUnmounted 生效前提） */
function mountConsumer(): { unmount: () => void; api: TelemetryApi } {
  let api!: TelemetryApi;
  const wrapper = mount(
    defineComponent({
      setup() {
        api = telemetry.useIotTelemetry();
        return () => h('div');
      },
    }),
  );
  return { unmount: () => wrapper.unmount(), api };
}

/** 构造合法帧体（字段与后端 TelemetrySummary record 逐字对齐） */
function frameBody(count: number, occurredAt: string, deviceId: string): string {
  return JSON.stringify({
    count,
    occurredAtUpperBound: occurredAt,
    items: [{ deviceId, metricCode: 'heartRate' }],
  });
}

describe('遥测页面级组合', () => {
  it('连接落地后收帧：latestSummary 新帧覆盖旧帧且 frameCount 递增', () => {
    const { api } = mountConsumer();
    api.connect('token-a', '1001');
    // 连接落地：触发库 onConnect 回调，登记待订阅转正为库订阅
    (lastConfig()['onConnect'] as () => void)();
    const callback = h2.subscriptions[0]?.callback;
    expect(h2.subscriptions[0]?.destination).toBe('/topic/iot/telemetry/1001');
    expect(callback).toBeDefined();
    // 第一帧：摘要进入展示态、计数起步
    callback?.({ body: frameBody(1, '2026-09-11T01:00:00Z', 'dev-001') });
    expect(api.latestSummary.value?.count).toBe(1);
    expect(api.latestSummary.value?.occurredAtUpperBound).toBe('2026-09-11T01:00:00Z');
    expect(api.latestSummary.value?.items[0]?.deviceId).toBe('dev-001');
    expect(api.frameCount.value).toBe(1);
    // 第二帧覆盖旧帧（滚动最新态，防内存无界增长）：仅保留最近一帧、计数累加
    callback?.({ body: frameBody(2, '2026-09-11T02:00:00Z', 'dev-002') });
    expect(api.latestSummary.value?.count).toBe(2);
    expect(api.latestSummary.value?.occurredAtUpperBound).toBe('2026-09-11T02:00:00Z');
    expect(api.latestSummary.value?.items[0]?.deviceId).toBe('dev-002');
    expect(api.frameCount.value).toBe(2);
  });

  it('断开后再连接：状态机完成 disconnected→connecting→connected 往返且计数归零重订阅', async () => {
    const { api } = mountConsumer();
    expect(api.connectionState.value).toBe('disconnected');
    // 第一轮：建连 → 落地 → 收帧一帧
    api.connect('token-a', '1001');
    expect(api.connectionState.value).toBe('connecting');
    expect(api.topicPath.value).toBe('/topic/iot/telemetry/1001');
    (lastConfig()['onConnect'] as () => void)();
    expect(api.connectionState.value).toBe('connected');
    h2.subscriptions[0]?.callback({ body: frameBody(1, '2026-09-11T01:00:00Z', 'dev-001') });
    expect(api.frameCount.value).toBe(1);
    // 主动断开：deactivate 取消库内建重连，状态回归断开态
    await api.disconnect();
    expect(api.connectionState.value).toBe('disconnected');
    // 第二轮重连（换病区）：状态机再次走 connecting→connected，计数归零、订阅指向新主题
    api.connect('token-a', '1002');
    expect(api.connectionState.value).toBe('connecting');
    expect(api.latestSummary.value).toBeNull();
    expect(api.frameCount.value).toBe(0);
    expect(api.topicPath.value).toBe('/topic/iot/telemetry/1002');
    (lastConfig()['onConnect'] as () => void)();
    expect(api.connectionState.value).toBe('connected');
    expect(h2.subscriptions.at(-1)?.destination).toBe('/topic/iot/telemetry/1002');
  });

  it('组件卸载统一退订订阅句柄并断开连接（web B.3-3 卸载清理条款）', async () => {
    const { unmount, api } = mountConsumer();
    api.connect('token-a', '1001');
    (lastConfig()['onConnect'] as () => void)();
    expect(h2.unsubscribeCalls).toBe(0);
    unmount();
    // onUnmounted 内先退订后 void 断开：等待微任务排空后断言两个动作均已发生
    await flushPromises();
    expect(h2.unsubscribeCalls).toBe(1);
    expect(h2.deactivateCalls).toBe(1);
  });
});
