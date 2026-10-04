// 门诊叫号 STOMP 单例封装单测（镜像 useIotStomp.spec 范式）：vi.mock('@stomp/stompjs') 捕获
// 构造参数与回调挂接（禁真实建连）；令牌缓存单源经 vi.mock('@/api/bigscreenToken') 承载
// （W-68 收敛：ensure 把关 + getCached 拼头，缓存命中/到期重签本体归 bigscreenToken.spec
// 覆盖）。核心断言：令牌获取失败时 connect 拒建连【零连接零出网】+ tokenFailed 横幅态、
// brokerURL=ws://…/ws/outpatient 形态、订阅路径精确等于 /topic/outpatient/queue/{deptCode}、
// beforeConnect 取令牌拼 Bearer 头的时序（泛哨兵无病区面）、毒帧防御、空诊区拒订阅与显式
// 断开先退订后 deactivate。每用例 vi.resetModules 后动态再导入，重置模块级单例。
import { flushPromises } from '@vue/test-utils';
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
  /** 令牌缓存单源桩状态：tokenReady=ensure 结论（false=签发失败）；tokenValue=getCached
   *  缓存值（拼 Bearer 头用）；ensureCalls/ensureWardIds=ensure 调用计数与透传病区序列 */
  tokenValue: 'tok-screen',
  tokenReady: true,
  ensureCalls: 0,
  ensureWardIds: [] as Array<string | undefined>,
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

vi.mock('@/api/bigscreenToken', () => ({
  // 令牌缓存单源桩（W-68 收敛改造）：ensure 承载「是否持有有效令牌」把关结论并捕获透传
  // 病区序列（叫号大屏恒泛哨兵 undefined），getCached 承载拼 Bearer 头的缓存值；缓存命中/
  // 到期/畸形载荷重签本体归 bigscreenToken.spec 覆盖（本层只验消费契约）
  ensureBigscreenToken: (wardId?: string): Promise<boolean> => {
    h.ensureCalls += 1;
    h.ensureWardIds.push(wardId);
    return Promise.resolve(h.tokenReady);
  },
  getCachedBigscreenToken: (): string => (h.tokenReady ? h.tokenValue : ''),
}));

// 模块级单例经 resetModules 重置：每用例取得全新模块实例（令牌缓存与失败态随重置归零）
let queueStomp: typeof import('./useQueueStomp');

/** 动态导入被测模块（令牌状态经 mock 桩 h 预置，替代旧构建期 stubEnv 机制） */
async function importModule(): Promise<void> {
  vi.resetModules();
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
  h.tokenValue = 'tok-screen';
  h.tokenReady = true;
  h.ensureCalls = 0;
  h.ensureWardIds = [];
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
  it('令牌运行期获取失败：connect 拒建连，零 Client 创建零激活，tokenFailed 置位供页面横幅', async () => {
    h.tokenReady = false;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    queueStomp.connect('DEPT-INT');
    await flushPromises();
    // 核心断言（拒建连语义保持）：取不到令牌不创建 Client、不发起激活——整条 WS 链路禁用
    expect(h.ensureCalls).toBe(1);
    expect(h.constructorCalls).toBe(0);
    expect(h.activateCalls).toBe(0);
    expect(queueStomp.connectionState.value).toBe('disconnected');
    expect(queueStomp.tokenFailed.value).toBe(true);
    warnSpy.mockRestore();
  });

  it('令牌获取成功建连：brokerURL 形态 ws://…/ws/outpatient，库内建重连 10s、双向心跳 10s', async () => {
    queueStomp.connect('DEPT-INT');
    // 连接发起（含取令牌阶段）先行进入 connecting 态（同步断言）
    expect(queueStomp.connectionState.value).toBe('connecting');
    await flushPromises();
    const config = lastConfig();
    // 计划 Interfaces 冻结形态：/ws/outpatient 路径（jsdom 页面协议 http → ws 推导）
    expect(config['brokerURL']).toBe('ws://localhost:3000/ws/outpatient');
    expect(config['reconnectDelay']).toBe(10000);
    expect(config['heartbeatIncoming']).toBe(10000);
    expect(config['heartbeatOutgoing']).toBe(10000);
    expect(h.activateCalls).toBe(1);
    expect(queueStomp.tokenFailed.value).toBe(false);
  });

  it('beforeConnect 携带运行期签发令牌拼 Bearer 头（先取令牌后建连时序，订阅级鉴权凭证）', async () => {
    queueStomp.connect('DEPT-INT');
    await flushPromises();
    const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
    await beforeConnect();
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-screen');
  });

  it('beforeConnect 每次连接尝试经 ensure 把关（缓存/重签收敛 api 层单源，泛哨兵无病区面）', async () => {
    queueStomp.connect('DEPT-INT');
    await flushPromises();
    const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
    await beforeConnect();
    // connect 建连把关一次 + beforeConnect 重连把关一次：每次连接尝试都经 ensure（到期重签
    // 裁决在 api 层命中条件承载，W-68 收敛）；叫号大屏无病区筛选面，恒泛哨兵 undefined
    expect(h.ensureCalls).toBe(2);
    expect(h.ensureWardIds.every((wardId) => wardId === undefined)).toBe(true);
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-screen');
    // api 层重签后缓存值更新：重连尝试拼头携带新值（getCached 实时读取不固化）
    h.tokenValue = 'tok-screen-2';
    await beforeConnect();
    expect(h.ensureCalls).toBe(3);
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-screen-2');
  });

  it('重连路径令牌重签失败：beforeConnect 不抛异常且本次尝试无凭证头，交服务端拒绝+库内建重连', async () => {
    queueStomp.connect('DEPT-INT');
    await flushPromises();
    const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
    h.tokenReady = false;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    // 重连路径语义（useIotStomp 同款）：不中断库重连节奏，本次尝试被服务端 CONNECT 帧鉴权拒绝
    await expect(beforeConnect()).resolves.toBeUndefined();
    expect(h.ensureCalls).toBe(2);
    expect(lastClient().connectHeaders['Authorization']).toBeUndefined();
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('订阅路径精确等于 /topic/outpatient/queue/{deptCode}（连接落地前登记待订阅，onConnect 转正）', async () => {
    const onStateSeen: string[] = [];
    queueStomp.connect('DEPT-INT', (state) => void onStateSeen.push(state));
    await flushPromises();
    queueStomp.subscribeQueue('DEPT-INT', () => {});
    expect(h.subscriptions).toHaveLength(0);
    (lastConfig()['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(1);
    expect(h.subscriptions[0]?.destination).toBe('/topic/outpatient/queue/DEPT-INT');
    // 连接状态机（页面消费）：connecting → connected，onStateChange 随翻转触发
    expect(onStateSeen).toEqual(['connecting', 'connected']);
  });

  it('毒帧（非法 JSON 与 type 缺失载荷）仅 warn 留痕不中断订阅、不触达帧回调', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onFrame = vi.fn();
    queueStomp.connect('DEPT-INT');
    await flushPromises();
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
    const onFrame = vi.fn();
    queueStomp.connect('DEPT-INT');
    await flushPromises();
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

  it('空诊区编码拒绝连接与订阅（异常上抛且不产生库订阅、不触发令牌签发）', () => {
    expect(() => queueStomp.connect('')).not.toThrow();
    expect(h.ensureCalls).toBe(0);
    expect(h.constructorCalls).toBe(0);
    expect(() => queueStomp.subscribeQueue('   ', () => {})).toThrow();
    expect(h.subscriptions).toHaveLength(0);
  });

  it('显式 disconnect 先退订在册订阅再 deactivate，状态回归断开态', async () => {
    queueStomp.connect('DEPT-INT');
    await flushPromises();
    queueStomp.subscribeQueue('DEPT-INT', () => {});
    (lastConfig()['onConnect'] as () => void)();
    await queueStomp.disconnect();
    // B.3-3 卸载清理条款：显式断开先退订（1 次）再 deactivate（1 次）
    expect(h.unsubscribeCalls).toBe(1);
    expect(h.deactivateCalls).toBe(1);
    expect(queueStomp.connectionState.value).toBe('disconnected');
  });

  it('建连在飞时卸载（disconnect 递增代际）：令牌链落定后不建 Client 不激活 WS', async () => {
    queueStomp.connect('DEPT-INT');
    // 竞态窗口模拟：令牌签发在飞（未 flush）即卸载断开——真实组件 onUnmounted 调用时序，
    // 此刻 client 尚为 null、deactivate 无从作用，只能靠代际比对作废在飞建连链
    await queueStomp.disconnect();
    await flushPromises();
    // 令牌签发已真实发起（窗口存在），但代际失配：零 Client 创建零激活——WS 不得在卸载后被激活
    expect(h.ensureCalls).toBe(1);
    expect(h.constructorCalls).toBe(0);
    expect(h.activateCalls).toBe(0);
    // 状态保持断开态且不置 tokenFailed（页面已卸载，失败横幅态无消费方）
    expect(queueStomp.connectionState.value).toBe('disconnected');
    expect(queueStomp.tokenFailed.value).toBe(false);
  });

  it('断线 close 后重连 onConnect 重新落地订阅（stompjs 7.3.0 无自动重订阅，禁假连接）', async () => {
    queueStomp.connect('DEPT-INT');
    await flushPromises();
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

  it('令牌值禁入任何日志（web A.6 红线）：全部 info/warn/error 输出拼接后不得出现令牌值', async () => {
    h.tokenValue = 'tok-screen-secret';
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    // 覆盖拒建连分支（空诊区）、连接成功留痕、重签失败与连接失败分支
    queueStomp.connect('');
    queueStomp.connect('DEPT-INT');
    await flushPromises();
    h.tokenReady = false;
    const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
    await beforeConnect();
    (lastConfig()['onWebSocketClose'] as () => void)();
    (lastConfig()['onStompError'] as (frame: { headers: Record<string, string> }) => void)({
      headers: { message: 'broker error' },
    });
    const allLogs = [...infoSpy.mock.calls, ...warnSpy.mock.calls, ...errorSpy.mock.calls]
      .flat()
      .join('\n');
    expect(allLogs).not.toContain('tok-screen-secret');
    infoSpy.mockRestore();
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });
});
