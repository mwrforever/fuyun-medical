// 门诊叫号 STOMP 单例封装单测（镜像 useIotStomp.spec 范式）：vi.mock('@stomp/stompjs') 捕获
// 构造参数与回调挂接（禁真实建连）。核心断言：未配置大屏令牌时 connect 拒建连【零连接零出网】、
// brokerURL=ws://…/ws/outpatient 形态、订阅路径精确等于 /topic/outpatient/queue/{deptCode}、
// beforeConnect 拼 Bearer 头、毒帧防御、空诊区拒订阅与显式断开先退订后 deactivate。
// 每用例 vi.resetModules + vi.stubEnv 后动态再导入，重置模块级单例并控制构建期令牌值。
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** mock 捕获状态（vi.hoisted：vi.mock 工厂提升后仍可引用；逐用例手动复位） */
const h = vi.hoisted(() => ({
  constructorCalls: 0,
  configs: [] as Record<string, unknown>[],
  /** 捕获的桩 Client 实例（connectHeaders 经实例属性赋值，须从实例侧断言） */
  clients: [] as { connected: boolean; connectHeaders: Record<string, string> }[],
  activateCalls: 0,
  deactivateCalls: 0,
  /** 捕获的订阅：destination + 帧回调（用例内投递毒帧/合法帧） */
  subscriptions: [] as { destination: string; callback: (message: { body: string }) => void }[],
  unsubscribeCalls: 0,
}));

vi.mock('@stomp/stompjs', () => {
  /** 捕获构造参数与订阅行为的桩 Client（无任何真实网络行为） */
  class MockClient {
    connected = false;
    connectHeaders: Record<string, string> = {};
    constructor(config: Record<string, unknown>) {
      h.constructorCalls += 1;
      h.configs.push(config);
      h.clients.push(this);
    }
    activate(): void {
      h.activateCalls += 1;
    }
    deactivate(): Promise<void> {
      h.deactivateCalls += 1;
      return Promise.resolve();
    }
    subscribe(
      destination: string,
      callback: (message: { body: string }) => void,
    ): { id: string; destination: string; unsubscribe: () => void } {
      h.subscriptions.push({ destination, callback });
      return { id: 'sub-0', destination, unsubscribe: () => void (h.unsubscribeCalls += 1) };
    }
  }
  return { Client: MockClient };
});

// 模块级单例经 resetModules 重置：每用例取得全新模块实例（令牌常量随 stubEnv 重新求值）
let queueStomp: typeof import('./useQueueStomp');

/** 动态导入被测模块：tokenStub 传入构建期令牌值（undefined=未配置，即默认空） */
async function importModule(tokenStub?: string): Promise<void> {
  vi.resetModules();
  vi.unstubAllEnvs();
  if (tokenStub !== undefined) {
    vi.stubEnv('VITE_BIGSCREEN_TOKEN', tokenStub);
  }
  queueStomp = await import('./useQueueStomp');
}

beforeEach(async () => {
  vi.clearAllMocks();
  h.constructorCalls = 0;
  h.configs = [];
  h.clients = [];
  h.activateCalls = 0;
  h.deactivateCalls = 0;
  h.subscriptions = [];
  h.unsubscribeCalls = 0;
  await importModule();
});

/** 取最近一次捕获的 Client 构造配置（用例内已保证至少一次建连） */
function lastConfig(): Record<string, unknown> {
  const config = h.configs.at(-1);
  expect(config).toBeDefined();
  return config as Record<string, unknown>;
}

/** 取最近一次捕获的桩 Client 实例（connectHeaders 经实例属性赋值，从实例侧断言） */
function lastClient(): { connected: boolean; connectHeaders: Record<string, string> } {
  const instance = h.clients.at(-1);
  expect(instance).toBeDefined();
  return instance as { connected: boolean; connectHeaders: Record<string, string> };
}

describe('门诊叫号 STOMP 单例封装（web B.3-3）', () => {
  it('未配置大屏令牌（VITE_BIGSCREEN_TOKEN 空）：connect 拒建连，零 Client 创建零激活', async () => {
    await importModule(undefined);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    queueStomp.connect('DEPT-INT');
    // 核心断言（§8.5 零出网）：未配置令牌不创建 Client、不发起激活——整条 WS 链路禁用
    expect(h.constructorCalls).toBe(0);
    expect(h.activateCalls).toBe(0);
    expect(queueStomp.connectionState.value).toBe('disconnected');
    expect(queueStomp.isQueueTokenConfigured()).toBe(false);
    warnSpy.mockRestore();
  });

  it('已配置令牌建连：brokerURL 形态 ws://…/ws/outpatient，库内建重连 10s、双向心跳 10s', async () => {
    await importModule('tok-screen');
    queueStomp.connect('DEPT-INT');
    const config = lastConfig();
    // 计划 Interfaces 冻结形态：/ws/outpatient 路径（jsdom 页面协议 http → ws 推导）
    expect(config['brokerURL']).toBe('ws://localhost:3000/ws/outpatient');
    expect(config['reconnectDelay']).toBe(10000);
    expect(config['heartbeatIncoming']).toBe(10000);
    expect(config['heartbeatOutgoing']).toBe(10000);
    expect(queueStomp.isQueueTokenConfigured()).toBe(true);
  });

  it('beforeConnect 拼接 Bearer 头（订阅级鉴权凭证），连接发起即进入 connecting 态', async () => {
    await importModule('tok-screen');
    queueStomp.connect('DEPT-INT');
    const beforeConnect = lastConfig()['beforeConnect'] as () => void;
    beforeConnect();
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-screen');
    expect(queueStomp.connectionState.value).toBe('connecting');
  });

  it('订阅路径精确等于 /topic/outpatient/queue/{deptCode}（连接落地前登记待订阅，onConnect 转正）', async () => {
    await importModule('tok-screen');
    const onStateSeen: string[] = [];
    queueStomp.connect('DEPT-INT', (state) => void onStateSeen.push(state));
    queueStomp.subscribeQueue('DEPT-INT', () => {});
    expect(h.subscriptions).toHaveLength(0);
    (lastConfig()['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(1);
    expect(h.subscriptions[0]?.destination).toBe('/topic/outpatient/queue/DEPT-INT');
    // 连接状态机（页面消费）：connecting → connected，onStateChange 随翻转触发
    expect(onStateSeen).toEqual(['connecting', 'connected']);
  });

  it('毒帧（非法 JSON 与 type 缺失载荷）仅 warn 留痕不中断订阅、不触达帧回调', async () => {
    await importModule('tok-screen');
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onFrame = vi.fn();
    queueStomp.connect('DEPT-INT');
    queueStomp.subscribeQueue('DEPT-INT', onFrame);
    (lastConfig()['onConnect'] as () => void)();
    const callback = h.subscriptions[0]?.callback;
    expect(callback).toBeDefined();
    // 非法 JSON 毒帧：不抛异常、帧回调不触达
    expect(() => callback?.({ body: 'not-json{{' })).not.toThrow();
    // type 缺失载荷毒帧（后端 QueueCalledNotice.type 恒有值，缺失即不合法）
    expect(() => callback?.({ body: JSON.stringify({ ticketNo: 'A007' }) })).not.toThrow();
    expect(onFrame).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('合法 CALLED 帧逐字段收窄后触达帧回调（room 解析失败容忍 null）', async () => {
    await importModule('tok-screen');
    const onFrame = vi.fn();
    queueStomp.connect('DEPT-INT');
    queueStomp.subscribeQueue('DEPT-INT', onFrame);
    (lastConfig()['onConnect'] as () => void)();
    h.subscriptions[0]?.callback({
      body: JSON.stringify({
        type: 'CALLED',
        ticketNo: 'A007',
        patientName: '张*',
        doctorId: 'u1',
        room: null,
      }),
    });
    expect(onFrame).toHaveBeenCalledWith({
      type: 'CALLED',
      ticketNo: 'A007',
      patientName: '张*',
      doctorId: 'u1',
      room: null,
    });
  });

  it('空诊区编码拒绝连接与订阅（异常上抛且不产生库订阅）', async () => {
    await importModule('tok-screen');
    expect(() => queueStomp.connect('')).not.toThrow();
    expect(h.constructorCalls).toBe(0);
    expect(() => queueStomp.subscribeQueue('   ', () => {})).toThrow();
    expect(h.subscriptions).toHaveLength(0);
  });

  it('显式 disconnect 先退订在册订阅再 deactivate，状态回归断开态', async () => {
    await importModule('tok-screen');
    queueStomp.connect('DEPT-INT');
    queueStomp.subscribeQueue('DEPT-INT', () => {});
    (lastConfig()['onConnect'] as () => void)();
    await queueStomp.disconnect();
    // B.3-3 卸载清理条款：显式断开先退订（1 次）再 deactivate（1 次）
    expect(h.unsubscribeCalls).toBe(1);
    expect(h.deactivateCalls).toBe(1);
    expect(queueStomp.connectionState.value).toBe('disconnected');
  });

  it('断线 close 后重连 onConnect 重新落地订阅（stompjs 7.3.0 无自动重订阅，禁假连接）', async () => {
    await importModule('tok-screen');
    queueStomp.connect('DEPT-INT');
    queueStomp.subscribeQueue('DEPT-INT', () => {});
    const config = lastConfig();
    (config['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(1);
    // 模拟断线：stompjs 7.3.0 整体作废 _stompHandler（旧订阅句柄随连接失效）
    (config['onWebSocketClose'] as () => void)();
    expect(queueStomp.connectionState.value).toBe('disconnected');
    // 模拟库内建自动重连成功：onConnect 二次触发必须重新 subscribe
    (config['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(2);
    expect(h.subscriptions.at(-1)?.destination).toBe('/topic/outpatient/queue/DEPT-INT');
  });

  it('令牌值禁入任何日志（web A.6 红线）：全部 warn/error 输出拼接后不得出现令牌值', async () => {
    await importModule('tok-screen-secret');
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    // 覆盖拒建连分支（空诊区）与建连失败分支（close/error 回调）
    queueStomp.connect('');
    queueStomp.connect('DEPT-INT');
    (lastConfig()['onWebSocketClose'] as () => void)();
    (lastConfig()['onStompError'] as (frame: { headers: Record<string, string> }) => void)({
      headers: { message: 'broker error' },
    });
    const allLogs = [...warnSpy.mock.calls, ...errorSpy.mock.calls].flat().join('\n');
    expect(allLogs).not.toContain('tok-screen-secret');
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });
});
