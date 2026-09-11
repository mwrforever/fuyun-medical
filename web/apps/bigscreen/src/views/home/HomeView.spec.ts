// 最小遥测页单测（BRIEF-PR5-01 §2.3）：mock 页面组合层 useIotTelemetry（禁真实建连），
// 覆盖未连接态渲染（连接设置区与「暂无遥测数据」占位、query.wardId 书签回填）、wardId 缺失/
// 非法与令牌缺失的连接拦截、连接触达组合层并同步路由 query、注入假帧后摘要面板渲染、
// 已连接态断开交互与链路状态区展示。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import type { Ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { useIotTelemetry } from '@/composables/useIotTelemetry';
import type { IotConnectionState } from '@/composables/useIotStomp';
import { router } from '@/router';
import type { TelemetrySummary } from '@/types/iot';
import HomeView from './HomeView.vue';

vi.mock('@/composables/useIotTelemetry', async () => {
  const { ref } = await import('vue');
  // 工厂级单例：refs 与 connect/disconnect spy 各创建一次，被挂组件与断言侧共享同一组实例
  const latestSummary = ref<TelemetrySummary | null>(null);
  const frameCount = ref(0);
  const topicPath = ref<string | null>(null);
  const connectionState = ref<'disconnected' | 'connecting' | 'connected'>('disconnected');
  const connect = vi.fn();
  const disconnect = vi.fn();
  return {
    useIotTelemetry: () => ({
      latestSummary,
      frameCount,
      topicPath,
      connectionState,
      connect,
      disconnect,
    }),
  };
});

/** 组合层 mock 共享句柄的显式类型（连接状态 ref 在真实 API 为只读，测试直接驱动翻转须可写视图） */
interface MockTelemetryApi {
  latestSummary: Ref<TelemetrySummary | null>;
  frameCount: Ref<number>;
  topicPath: Ref<string | null>;
  connectionState: Ref<IotConnectionState>;
  connect: Mock;
  disconnect: Mock;
}

/** 组合层 mock 的共享状态句柄（与被挂组件同源；mock 实现无组件上下文依赖） */
const mockApi = useIotTelemetry() as MockTelemetryApi;

/** 构造合法摘要（字段与后端 TelemetrySummary record 逐字对齐） */
function fakeSummary(): TelemetrySummary {
  return {
    count: 2,
    occurredAtUpperBound: '2026-09-11T02:00:00Z',
    items: [
      { deviceId: 'dev-001', metricCode: 'heartRate' },
      { deviceId: 'dev-002', metricCode: 'spo2' },
    ],
  };
}

/** 挂载首页（真实路由插件承载 useRoute/useRouter；调用前路由已就绪） */
function mountHomeView(): VueWrapper {
  return mount(HomeView, { global: { plugins: [router] } });
}

/** 取文案为指定文本的按钮（「连接」/「断开」），缺失时断言失败定位用例 */
function buttonByText(wrapper: VueWrapper, text: string): DOMWrapper<HTMLButtonElement> {
  const target = wrapper.findAll('button').find((node) => node.text() === text);
  expect(target, `应存在「${text}」按钮`).toBeDefined();
  return target as DOMWrapper<HTMLButtonElement>;
}

beforeEach(async () => {
  sessionStorage.clear();
  vi.clearAllMocks();
  mockApi.latestSummary.value = null;
  mockApi.frameCount.value = 0;
  mockApi.topicPath.value = null;
  mockApi.connectionState.value = 'disconnected';
  await router.push('/');
  await router.isReady();
});

describe('bigscreen 最小遥测页', () => {
  it('未连接态渲染连接设置区与「暂无遥测数据」占位，query.wardId 书签回填输入框', async () => {
    // 可书签化：携带 wardId query 直达页面应回填输入框
    await router.push('/?wardId=1001');
    const wrapper = mountHomeView();
    const wardIdInput = wrapper.find('input[type="text"]');
    expect((wardIdInput.element as HTMLInputElement).value).toBe('1001');
    // 令牌输入为 password 型（防旁窥）；空 sessionStorage 下无预填
    const tokenInput = wrapper.find('input[type="password"]');
    expect(tokenInput.exists()).toBe(true);
    expect((tokenInput.element as HTMLInputElement).value).toBe('');
    // 连接按钮在位、断开按钮仅连接态可见（未连接态不渲染）
    expect(buttonByText(wrapper, '连接').exists()).toBe(true);
    expect(wrapper.findAll('button').some((node) => node.text() === '断开')).toBe(false);
    // 站点名锚点（App.spec 冒烟断言共用）与无帧占位渲染
    expect(wrapper.text()).toContain('富云数据大屏');
    expect(wrapper.text()).toContain('暂无遥测数据');
    wrapper.unmount();
  });

  it('wardId 缺失或非法时连接被拦截并提示，不触达组合层', async () => {
    const wrapper = mountHomeView();
    const connectButton = buttonByText(wrapper, '连接');
    // 缺失：直接点击连接
    await connectButton.trigger('click');
    expect(wrapper.text()).toContain('请输入纯数字病区 ID');
    expect(mockApi.connect).not.toHaveBeenCalled();
    // 非法（含非数字字符）：同样拦截
    await wrapper.find('input[type="text"]').setValue('abc12');
    await connectButton.trigger('click');
    expect(wrapper.text()).toContain('请输入纯数字病区 ID');
    expect(mockApi.connect).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('令牌缺失时连接被拦截并提示注入令牌', async () => {
    const wrapper = mountHomeView();
    await wrapper.find('input[type="text"]').setValue('1001');
    await buttonByText(wrapper, '连接').trigger('click');
    expect(wrapper.text()).toContain('请先注入访问令牌');
    expect(mockApi.connect).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('wardId 与令牌齐备时连接触达组合层，wardId 同步路由 query（可书签化）', async () => {
    const wrapper = mountHomeView();
    await wrapper.find('input[type="text"]').setValue('1001');
    await wrapper.find('input[type="password"]').setValue('token-x');
    await buttonByText(wrapper, '连接').trigger('click');
    expect(mockApi.connect).toHaveBeenCalledWith('token-x', '1001');
    // router.replace 为异步导航（组件内 void 不阻塞点击链路）：排空微任务后再断言 query 同步
    await flushPromises();
    expect(router.currentRoute.value.query['wardId']).toBe('1001');
    wrapper.unmount();
  });

  it('注入假帧后面板渲染本批条数与 items 明细，占位文案消失', async () => {
    mockApi.latestSummary.value = fakeSummary();
    mockApi.frameCount.value = 5;
    const wrapper = mountHomeView();
    await flushPromises();
    expect(wrapper.text()).toContain('本批条数：2');
    expect(wrapper.text()).toContain('2026-09-11T02:00:00Z');
    expect(wrapper.text()).toContain('dev-001');
    expect(wrapper.text()).toContain('heartRate');
    expect(wrapper.text()).toContain('dev-002');
    expect(wrapper.text()).toContain('spo2');
    expect(wrapper.text()).not.toContain('暂无遥测数据');
    wrapper.unmount();
  });

  it('已连接态展示链路状态与「断开」按钮，点击触达组合层断开', async () => {
    mockApi.connectionState.value = 'connected';
    mockApi.topicPath.value = '/topic/iot/telemetry/1001';
    mockApi.frameCount.value = 3;
    const wrapper = mountHomeView();
    // 链路状态区：已连接徽标 + 当前订阅主题路径 + 已接收帧计数
    expect(wrapper.text()).toContain('已连接');
    expect(wrapper.text()).toContain('/topic/iot/telemetry/1001');
    expect(wrapper.text()).toContain('已接收帧数：3');
    await buttonByText(wrapper, '断开').trigger('click');
    expect(mockApi.disconnect).toHaveBeenCalled();
    wrapper.unmount();
  });
});
