// 全局 errorHandler 单测（册 0 P-GBL-03）：真实 createApp 挂 setup 抛错探针组件，验证三条
// 行为契约——①错误经 logger.error 全量留痕（含错误源 info 与原始异常对象）；②3s 冷却窗
// 去抖（错误风暴仅首弹 ElMessage，日志逐次全量零去抖）；③AxiosError 防双弹（HTTP 错误
// 已由 api 响应拦截器统一弹错，errorHandler 只留痕不弹）。
// 桩化形态：element-plus 整模块桩化（ElMessage 以 vi.fn 承载，断言弹窗调用面而非渲染面，
// jsdom 不真实渲染 EP 弹层）；logger 以 spyOn 命名空间导出承载（保留真实模块形态，用例后
// mockRestore）。冷却窗时间戳为模块级去抖状态，用例间以逐用例严格递增的确定性时间锚实现
// 隔离（vi.setSystemTime，每用例 +10s——useFakeTimers 每次重锚定，前移出窗法对上一用例
// 残留 lastToastAt 的净推进仅两用例真实耗时，无法出窗）。
import { AxiosError } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockInstance } from 'vitest';
import { createApp, defineComponent } from 'vue';
import { ElMessage } from 'element-plus';
import * as logger from './logger';
import { installGlobalErrorHandler } from './errorHandler';

vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }));

/** 用例时间锚基数（毫秒）：任意远离 epoch 的确定值，逐用例 +10s 严格递增 */
const ANCHOR_BASE_MS = 1_700_000_000_000;

/** 用例序号：驱动时间锚逐用例递增，保证本用例首错时刻恒晚于上一用例残留 lastToastAt+3000 */
let anchorSeq = 0;

/** 挂载一个 setup 即抛指定异常的探针应用（errorHandler 装配后 Vue 将异常交其统一出口） */
function mountSetupThrowingApp(thrown: unknown): void {
  const SetupErrorProbe = defineComponent({
    name: 'SetupErrorProbe',
    setup() {
      throw thrown;
    },
  });
  const host = document.createElement('div');
  document.body.appendChild(host);
  const app = createApp(SetupErrorProbe);
  installGlobalErrorHandler(app);
  app.mount(host);
  app.unmount();
  host.remove();
}

describe('全局 errorHandler 兜底（P-GBL-03）', () => {
  let errorSpy: MockInstance;

  beforeEach(() => {
    // 冷却窗跨用例隔离：lastToastAt 为模块级状态不可注入，以逐用例递增 10s 的确定性时间锚
    // 保证本用例首错时刻严格晚于上一用例残留 lastToastAt + 冷却窗（隔离不依赖用例间真实耗时）
    anchorSeq += 1;
    vi.useFakeTimers();
    vi.setSystemTime(ANCHOR_BASE_MS + anchorSeq * 10_000);
    errorSpy = vi.spyOn(logger, 'error').mockImplementation(() => {});
    vi.mocked(ElMessage.error).mockClear();
  });

  afterEach(() => {
    errorSpy.mockRestore();
    vi.useRealTimers();
  });

  it('全局 errorHandler 安装后：组件渲染抛错经 logger.error 留痕（含错误源 info 与堆栈）', () => {
    const thrown = new Error('探针异常');

    mountSetupThrowingApp(thrown);

    expect(errorSpy).toHaveBeenCalledTimes(1);
    // 错误源 info（Vue 标识，setup 场景即 setup function）进日志消息定位来源，原始异常对象全量附参
    expect(String(errorSpy.mock.calls[0]?.[0])).toContain('setup function');
    expect(errorSpy.mock.calls[0]?.[1]).toBe(thrown);
    // 非风暴首错照常弹用户提示
    expect(ElMessage.error).toHaveBeenCalledTimes(1);
  });

  it('错误风暴去抖：3s 冷却窗内重复异常仅首弹 ElMessage，日志逐次全量留痕', () => {
    mountSetupThrowingApp(new Error('风暴第一击'));
    mountSetupThrowingApp(new Error('风暴第二击'));
    mountSetupThrowingApp(new Error('风暴第三击'));

    // 窗内：弹窗恰好一次（防轰炸），日志三次逐错全量（零去抖）
    expect(ElMessage.error).toHaveBeenCalledTimes(1);
    expect(errorSpy).toHaveBeenCalledTimes(3);

    // 冷却窗走完后下一错误恢复弹出
    vi.advanceTimersByTime(3001);
    mountSetupThrowingApp(new Error('风暴后恢复'));
    expect(ElMessage.error).toHaveBeenCalledTimes(2);
  });

  it('AxiosError 不重复弹提示（api 拦截器已弹，errorHandler 只留痕）', () => {
    mountSetupThrowingApp(new AxiosError('请求失败', 'ERR_BAD_REQUEST'));

    expect(ElMessage.error).not.toHaveBeenCalled();
    expect(errorSpy).toHaveBeenCalledTimes(1);
  });
});
