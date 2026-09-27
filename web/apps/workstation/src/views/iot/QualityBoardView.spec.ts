// 质量看板页单测（/iot/quality，M16 数据质量治理面前端面）：质量统计表渲染与 deviceId 筛选
// 出网携参、设备利用率 TopN 按 usageRate 降序截取渲染、消费积压水位渲染、消费错误列表默认
// 待处置筛选出网携 status=PENDING 与环节/状态徽标（fuy-cerr-tag--{status} 机器判据）、重放
// 出网携 errorId 并刷新（含重放在途守卫双击仅一次出网——防重复重投消费消息）、放弃弹窗空
// 原因零出网与携原因出网 abandon。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { consumeErrors, monitor, quality } from '@/api/iot';
import type { ConsumeErrorVO, DataQualityStatVO } from '@/api/iot';
import QualityBoardView from './QualityBoardView.vue';

vi.mock('@/api/iot', () => ({
  CONSUME_ERROR_STAGE_LABELS: { PARSE: '解析', VALIDATE: '校验', PERSIST: '落库' },
  CONSUME_ERROR_STATUS_LABELS: { PENDING: '待处置', REPLAYED: '已重放', ABANDONED: '已放弃' },
  quality: { stats: vi.fn(), deviceUsage: vi.fn() },
  monitor: { consumerLag: vi.fn() },
  consumeErrors: { page: vi.fn(), replay: vi.fn(), abandon: vi.fn() },
}));

// 仅替身 ElMessage（提示断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 质量统计行（质量/利用率共用出参形态，可覆写） */
function statMock(partial: Partial<DataQualityStatVO> = {}): DataQualityStatVO {
  return {
    deviceId: 'dev-001',
    statDate: '2026-09-26',
    expectedCount: '1000',
    receivedCount: '980',
    missingRate: 0.02,
    anomalyCount: '3',
    qualityScore: 98.5,
    usageRate: 0.85,
    ...partial,
  };
}

/** 消费错误行（可覆写状态/环节） */
function errorMock(partial: Partial<ConsumeErrorVO> = {}): ConsumeErrorVO {
  return {
    errorId: '501',
    queueName: 'fy.queue.iot.telemetry',
    rawDigest: 'a1b2c3',
    rawPayload: '{"deviceId":"dev-1"}',
    errorStage: 'PARSE',
    errorMsg: '非法载荷结构',
    status: 'PENDING',
    replayCount: 0,
    createdAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 空分页出参 */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行 */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 在指定行内按按钮文案点击（行作用域操作，防跨行误中） */
async function clickRowButton(
  wrapper: VueWrapper,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = findRow(wrapper, rowText);
  const button = row?.findAll('button').find((b) => b.text() === buttonText);
  if (!button) {
    throw new Error(`未找到行内按钮：${buttonText}`);
  }
  await button.trigger('click');
}

/** 按按钮文案点击 */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('质量看板页', () => {
  beforeEach(() => {
    for (const fn of [
      quality.stats,
      quality.deviceUsage,
      monitor.consumerLag,
      consumeErrors.page,
      consumeErrors.replay,
      consumeErrors.abandon,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(quality.stats).mockResolvedValue(emptyPage());
    vi.mocked(quality.deviceUsage).mockResolvedValue(emptyPage());
    vi.mocked(monitor.consumerLag).mockResolvedValue([]);
    vi.mocked(consumeErrors.page).mockResolvedValue(emptyPage());
  });

  it('质量统计表渲染缺测率/异常数/质量分列，deviceId 筛选出网携参', async () => {
    vi.mocked(quality.stats).mockResolvedValue({
      content: [statMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('dev-001');
    expect(text).toContain('2026-09-26');
    expect(text).toContain('2.00%');
    expect(text).toContain('98.5');
    await wrapper.find('input[aria-label="设备 ID 筛选"]').setValue('dev-009');
    await clickButton(wrapper, '查询');
    await flushPromises();
    expect(quality.stats).toHaveBeenLastCalledWith(
      expect.objectContaining({ deviceId: 'dev-009', page: 0, size: 50 }),
    );
  });

  it('设备利用率 TopN 按 usageRate 降序排序渲染', async () => {
    vi.mocked(quality.deviceUsage).mockResolvedValue({
      content: [
        statMock({ deviceId: 'dev-low', usageRate: 0.42 }),
        statMock({ deviceId: 'dev-high', usageRate: 0.97 }),
        statMock({ deviceId: 'dev-mid', usageRate: 0.71 }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    const usageTable = wrapper.find('[data-test="usage-table"]');
    expect(usageTable.exists()).toBe(true);
    // 降序机器判据：首行为最高利用率设备
    const deviceIds = usageTable.findAll('tbody tr').map((row) => row.text());
    expect(deviceIds[0]).toContain('dev-high');
    expect(deviceIds[1]).toContain('dev-mid');
    expect(deviceIds[2]).toContain('dev-low');
  });

  it('消费积压水位渲染消费组/积压估计/最老消息年龄', async () => {
    vi.mocked(monitor.consumerLag).mockResolvedValue([
      {
        consumerGroup: 'iot-telemetry-consumer',
        sampledAt: '2026-09-26T10:00:00+08:00',
        oldestMsgAgeSecs: '42',
        consumeRate: 120.5,
        arriveRate: 118.2,
        backlogEstimate: 36,
      },
    ]);
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('iot-telemetry-consumer');
    expect(text).toContain('36');
    expect(text).toContain('42');
  });

  it('消费错误列表默认待处置筛选出网携 status=PENDING，环节/状态徽标机器判据', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [
        errorMock({ status: 'PENDING' }),
        errorMock({ errorId: '502', errorStage: 'VALIDATE', status: 'REPLAYED' }),
        errorMock({ errorId: '503', errorStage: 'PERSIST', status: 'ABANDONED' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    expect(consumeErrors.page).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING', page: 0, size: 50 }),
    );
    expect(wrapper.find('.fuy-cerr-tag--pending').exists()).toBe(true);
    expect(wrapper.find('.fuy-cerr-tag--replayed').exists()).toBe(true);
    expect(wrapper.find('.fuy-cerr-tag--abandoned').exists()).toBe(true);
    expect(wrapper.text()).toContain('解析');
  });

  it('消费错误重放出网携 errorId 并刷新列表', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(consumeErrors.replay).mockResolvedValue(errorMock({ status: 'REPLAYED' }));
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    await clickRowButton(wrapper, '501', '重放');
    await flushPromises();
    expect(consumeErrors.replay).toHaveBeenCalledWith('501');
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 列表重载（page 第二次调用）
    expect(consumeErrors.page).toHaveBeenCalledTimes(2);
  });

  it('放弃弹窗空原因零出网拦截，携原因出网 abandon 并刷新列表', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(consumeErrors.abandon).mockResolvedValue(errorMock({ status: 'ABANDONED' }));
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    await clickRowButton(wrapper, '501', '放弃');
    await flushPromises();
    // 空原因零出网
    await clickButton(wrapper, '确认放弃');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写放弃原因');
    expect(consumeErrors.abandon).not.toHaveBeenCalled();
    // 携原因出网
    await wrapper.find('textarea[aria-label="放弃原因"]').setValue('脏数据确认不重放');
    await clickButton(wrapper, '确认放弃');
    await flushPromises();
    expect(consumeErrors.abandon).toHaveBeenCalledWith('501', { reason: '脏数据确认不重放' });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(consumeErrors.page).toHaveBeenCalledTimes(2);
  });

  it('重放在途守卫：双击窗口内重复点击仅一次出网（防重复重投消费消息）', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(consumeErrors.replay).mockResolvedValue(errorMock({ status: 'REPLAYED' }));
    const wrapper = mount(QualityBoardView);
    await flushPromises();
    const replayButton = findRow(wrapper, '501')
      ?.findAll('button')
      .find((b) => b.text() === '重放');
    expect(replayButton).toBeDefined();
    // 双击（第二次点击落在首次重投在途窗口内，未 flush）
    await replayButton?.trigger('click');
    await replayButton?.trigger('click');
    await flushPromises();
    expect(consumeErrors.replay).toHaveBeenCalledTimes(1);
    expect(consumeErrors.replay).toHaveBeenCalledWith('501');
  });
});
