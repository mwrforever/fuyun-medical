// 质量看板页单测（/iot/quality，M16 数据质量治理面前端面 · 暖纸卷宗换脸重排 丙-7）：既有业务
// 断言全量保留（质量统计渲染与 deviceId 筛选出网携参、设备利用率 TopN 按 usageRate 降序截取、
// 消费积压水位渲染、消费错误默认待处置筛选出网携 status=PENDING 与环节/状态徽标机器判据、重放
// 出网携 errorId 并刷新（含重放在途守卫双击仅一次出网——防重复重投消费消息）、放弃原因强制
// 空原因零出网、PR-4F 权限两态），新增换脸锚点（丙-7 蓝图第 7 节）：①PENDING 计数胶囊派生
// 断言（门牌「待处置死信 N」琥珀胶囊 + 消费错误卡头徽标，前端自 errorRows 过滤 PENDING 派生；
// 首拉落地前静态骨架占位禁伪 0 值——丙-4/丙-6 先例同构）；
// ②backlogEstimate>0 琥珀双通道断言（琥珀文字+「积压」字样，阈值口径=零值判断）；③重放/放弃
// 原因强制断言保留；④TopN 截取与降序断言保留；⑤钤印族（「已重放/已放弃」v-if 挂载制）+四空
// 态脸化。
// 断言现代化留痕（回归红线出口）：原重放/放弃成功反馈断言 ElMessage.success 按钤印契约（母
// 契约 G2：成功反馈=钤印，message 不再承载成功）重写为 .fuy-stamp-toast 断言——该两处断言
// 冻结的恰为被本次换脸替换的成功反馈通道（旧实现细节），非业务行为；重放/放弃出网携参、原因
// 强制、刷新链等业务行为断言零变动。
// api mock 承载零出网（vi.mock('@/api/iot') 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { consumeErrors, monitor, quality } from '@/api/iot';
import type { ConsumeErrorVO, DataQualityStatVO } from '@/api/iot';
import { permDirective } from '@/directives/perm';
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
  /** 文件级 Pinia：门牌批注行签认人读 auth 会话 store + v-perm 指令元素权限判定，每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #38）：真实 IOT_ADMIN 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u5',
          loginName: 'iotadmindemo',
          displayName: '陆物联',
          orgId: null,
          roles: ['iot_admin'],
          permissions: ['iot:quality:btn:replay'],
        },
      }),
    );
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

  /** 统一挂载：pinia（门牌签认人）+ v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(QualityBoardView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('门牌页首：根不挂 .fuy-stagger（flow 层承接级联）、衬线标题「质量看板」+ DATA QUALITY + 批注行 + 全量刷新', async () => {
    const wrapper = mountView();
    await flushPromises();
    // 页级骨架（契约 ⑧.1/G1）：根挂 .fuy-page 且不挂 .fuy-stagger（契约 ⑦.4 防双重进场节奏），
    // 级联由 flow 层承接（flex+gap 同构承接根级纵排）
    const root = wrapper.find('.fuy-page');
    expect(root.exists()).toBe(true);
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.quality-flow.fuy-stagger').exists()).toBe(true);
    // 门牌三锚：衬线标题 + 装饰英文小注 + 批注行（四段语义 · 谁 · 时刻齐备）
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('质量看板');
    expect(wrapper.find('.fuy-page-en').text()).toBe('DATA QUALITY');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('缺测率/异常数/质量分');
    expect(note.text()).toContain('利用率 TopN');
    expect(note.text()).toContain('消费积压水位');
    expect(note.text()).toContain('死信治理（重放/放弃）');
    expect(note.text()).toContain('签认人 陆物联');
    // 状态位=全量刷新钮（四区并行重取）
    expect(wrapper.findAll('button').some((b) => b.text() === '刷新')).toBe(true);
    // 页脚批注（契约 ⑧.8）：刷新语义
    expect(wrapper.find('.fuy-page-foot').exists()).toBe(true);
    wrapper.unmount();
  });

  it('PENDING 计数胶囊派生：门牌「待处置死信 N」琥珀胶囊 + 消费错误卡头徽标同源自 errorRows 过滤', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [
        errorMock(),
        errorMock({ errorId: '502', status: 'REPLAYED' }),
        errorMock({ errorId: '503', status: 'ABANDONED' }),
        errorMock({ errorId: '504', queueName: 'fy.queue.iot.alarm' }),
      ],
      page: '0',
      size: '20',
      total: '4',
    });
    const wrapper = mountView();
    await flushPromises();
    // 门牌胶囊（蓝图 丙-7.2 锚点①）：四行混态自 errorRows 过滤 PENDING 计 2，琥珀变体双通道
    const pagePill = wrapper.find('[data-test="page-pending-pill"]');
    expect(pagePill.exists()).toBe(true);
    expect(pagePill.classes()).toContain('fuy-status-pill--amber');
    expect(pagePill.text()).toBe('待处置死信 2');
    // 消费错误卡头徽标同源派生（死信治理主战场开场面）
    const cardPill = wrapper.find('[data-test="card-pending-pill"]');
    expect(cardPill.exists()).toBe(true);
    expect(cardPill.classes()).toContain('fuy-status-pill--amber');
    expect(cardPill.text()).toBe('待处置 2');
    wrapper.unmount();
  });

  it('PENDING 计数胶囊归零：无待处置行时门牌胶囊=「待处置死信 0」（零值派生不隐藏）', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [
        errorMock({ status: 'REPLAYED' }),
        errorMock({ errorId: '502', status: 'ABANDONED' }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mountView();
    await flushPromises();
    const pagePill = wrapper.find('[data-test="page-pending-pill"]');
    expect(pagePill.text()).toBe('待处置死信 0');
    expect(wrapper.find('[data-test="card-pending-pill"]').text()).toBe('待处置 0');
    wrapper.unmount();
  });

  it('PENDING 计数胶囊首拉骨架：挂载即静态占位禁伪 0 值，落地后门牌与卡头双胶囊诚实计数', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    // 挂载即骨架态（首拉未落地，禁伪 0 值——丙-4/丙-6 先例同构）：门牌与卡头双静态占位 +
    // 零内容胶囊
    expect(wrapper.find('.fuy-page-status .quality-pending-sk').exists()).toBe(true);
    expect(wrapper.find('.quality-card-tools .quality-pending-sk').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-status .fuy-status-pill').exists()).toBe(false);
    expect(wrapper.find('[data-test="card-pending-pill"]').exists()).toBe(false);
    await flushPromises();
    // 内容态：琥珀变体 + PENDING 过滤派生计数（错误出网仍只 mount 时一次，计数零出网派生）
    const pagePill = wrapper.find('[data-test="page-pending-pill"]');
    expect(pagePill.exists()).toBe(true);
    expect(pagePill.classes()).toContain('fuy-status-pill--amber');
    expect(pagePill.text()).toBe('待处置死信 1');
    expect(wrapper.find('[data-test="card-pending-pill"]').text()).toBe('待处置 1');
    expect(consumeErrors.page).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('质量统计表渲染缺测率/异常数/质量分列，deviceId 筛选出网携参', async () => {
    vi.mocked(quality.stats).mockResolvedValue({
      content: [statMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
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
    const wrapper = mountView();
    await flushPromises();
    const usageTable = wrapper.find('[data-test="usage-table"]');
    expect(usageTable.exists()).toBe(true);
    // 降序机器判据：首行为最高利用率设备
    const deviceIds = usageTable.findAll('tbody tr').map((row) => row.text());
    expect(deviceIds[0]).toContain('dev-high');
    expect(deviceIds[1]).toContain('dev-mid');
    expect(deviceIds[2]).toContain('dev-low');
  });

  it('消费积压水位渲染 + backlogEstimate>0 琥珀双通道（琥珀文字+「积压」字样），零值行不挂积压', async () => {
    vi.mocked(monitor.consumerLag).mockResolvedValue([
      {
        consumerGroup: 'iot-telemetry-consumer',
        sampledAt: '2026-09-26T10:00:00+08:00',
        oldestMsgAgeSecs: '42',
        consumeRate: 120.5,
        arriveRate: 118.2,
        backlogEstimate: 36,
      },
      {
        consumerGroup: 'iot-alarm-consumer',
        sampledAt: '2026-09-26T10:00:00+08:00',
        oldestMsgAgeSecs: '0',
        consumeRate: 30.0,
        arriveRate: 30.0,
        backlogEstimate: 0,
      },
    ]);
    const wrapper = mountView();
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('iot-telemetry-consumer');
    expect(text).toContain('36');
    expect(text).toContain('42');
    // 蓝图 丙-7.2 锚点②（PM 重排：水位异常双通道，阈值口径=零值判断非业务阈值）：
    // 36>0 行=琥珀文字+「积压」字样（双通道铁律：色+文字）；零值行不挂
    const hotCells = wrapper.findAll('.quality-backlog-hot');
    expect(hotCells).toHaveLength(1);
    expect(hotCells[0].text()).toContain('36');
    expect(hotCells[0].text()).toContain('积压');
    // 零值消费组行仅数字直出（无「积压」字样通道）
    const coldRow = findRow(wrapper, 'iot-alarm-consumer');
    expect(coldRow?.find('.quality-backlog-hot').exists()).toBe(false);
    expect(coldRow?.text()).toContain('iot-alarm-consumer');
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
    const wrapper = mountView();
    await flushPromises();
    expect(consumeErrors.page).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING', page: 0, size: 50 }),
    );
    expect(wrapper.find('.fuy-cerr-tag--pending').exists()).toBe(true);
    expect(wrapper.find('.fuy-cerr-tag--replayed').exists()).toBe(true);
    expect(wrapper.find('.fuy-cerr-tag--abandoned').exists()).toBe(true);
    expect(wrapper.text()).toContain('解析');
  });

  it('消费错误重放出网携 errorId 并刷新列表，成功盖钤印「已重放」（丙-7 锚点⑤钤印族）', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(consumeErrors.replay).mockResolvedValue(errorMock({ status: 'REPLAYED' }));
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, '501', '重放');
    await flushPromises();
    expect(consumeErrors.replay).toHaveBeenCalledWith('501');
    // 成功反馈=钤印（契约 ⑧.5/G2：动词结果文案，v-if 挂载制 + is-show + aria-live；
    // 留痕：原 ElMessage.success 断言按钤印契约重写，成功不再走 message 通道）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已重放');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（page 第二次调用）
    expect(consumeErrors.page).toHaveBeenCalledTimes(2);
  });

  it('放弃弹窗（挂 .fuy-dialog）空原因零出网拦截，携原因出网 abandon 并盖钤印「已放弃」', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(consumeErrors.abandon).mockResolvedValue(errorMock({ status: 'ABANDONED' }));
    const wrapper = mountView();
    await flushPromises();
    await clickRowButton(wrapper, '501', '放弃');
    await flushPromises();
    // 弹层脸锚（契约 G2/⑤#9）：放弃弹窗挂 .fuy-dialog
    expect(wrapper.find('.fuy-dialog').exists()).toBe(true);
    // 原因强制：空原因零出网
    await clickButton(wrapper, '确认放弃');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写放弃原因');
    expect(consumeErrors.abandon).not.toHaveBeenCalled();
    // 携原因出网
    await wrapper.find('textarea[aria-label="放弃原因"]').setValue('脏数据确认不重放');
    await clickButton(wrapper, '确认放弃');
    await flushPromises();
    expect(consumeErrors.abandon).toHaveBeenCalledWith('501', { reason: '脏数据确认不重放' });
    // 成功反馈=钤印「已放弃」（留痕：原 ElMessage.success 断言按钤印契约重写）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已放弃');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（page 第二次调用）
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
    const wrapper = mountView();
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

  it('四空态脸化：全空挂载渲染四张诚实空态脸（暂无语法主句+给下一步提示，零默认纸箱插画）', async () => {
    const wrapper = mountView();
    await flushPromises();
    const empties = wrapper.findAll('.fuy-empty');
    expect(empties).toHaveLength(4);
    // 四空态主句=暂无〈业务客体〉（蓝图 丙-7.4：「数据暂无质量统计数据」等四空态脸化）
    expect(empties[0].find('.fuy-empty-title').text()).toBe('暂无质量统计数据');
    expect(empties[1].find('.fuy-empty-title').text()).toBe('暂无设备利用率数据');
    expect(empties[2].find('.fuy-empty-title').text()).toBe('暂无消费积压数据');
    expect(empties[3].find('.fuy-empty-title').text()).toBe('暂无消费错误');
    // 说明句给下一步（刷新/筛选），零默认 el-empty 纸箱插画（契约 ⑥）
    for (const empty of empties) {
      expect(empty.find('.fuy-empty-hint').text()).toContain('刷新');
      expect(empty.find('.fuy-empty-mark').exists()).toBe(true);
    }
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    wrapper.unmount();
  });

  it('四区卷宗卡脸：2×2 四区各挂 .fuy-card（衬线卡头），数据表沿 .fuy-dense 密排', async () => {
    vi.mocked(quality.stats).mockResolvedValue({
      content: [statMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock()],
      page: '0',
      size: '20',
      total: '1',
    });
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
    const wrapper = mountView();
    await flushPromises();
    // 卷宗卡族（契约 ⑧.2/G1）：el-card 罗列脸退场，四区各一张卷宗卡
    expect(wrapper.findAll('.fuy-card')).toHaveLength(4);
    expect(wrapper.find('.el-card').exists()).toBe(false);
    // 四张卡头衬线标题
    const titles = wrapper.findAll('.fuy-card-title').map((t) => t.text());
    expect(titles).toContain('数据质量统计');
    expect(titles).toContain('设备利用率 Top10');
    expect(titles).toContain('消费积压水位');
    expect(titles).toContain('消费错误');
    // 密排沿 .fuy-dense（四区卡容器挂 dense——丙-2 同构后代关系；fuy-dense 唯一，
    // 禁与 size="small" 双轨混用，本次归一为仅 .fuy-dense）
    expect(wrapper.findAll('.fuy-card.fuy-dense')).toHaveLength(4);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 iot:quality:btn:replay 时重放/放弃全隐藏（D-34）', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock({ status: 'PENDING' })],
      page: '0',
      size: '20',
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u5' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 待处置行重放/放弃两口无码全隐藏
    expect(buttonTexts).not.toContain('重放');
    expect(buttonTexts).not.toContain('放弃');
    // 消费错误清单读面不受元素码影响
    expect(wrapper.text()).toContain('501');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 iot:quality:btn:replay 时待处置行重放/放弃可见', async () => {
    vi.mocked(consumeErrors.page).mockResolvedValue({
      content: [errorMock({ status: 'PENDING' })],
      page: '0',
      size: '20',
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('重放');
    expect(buttonTexts).toContain('放弃');
    wrapper.unmount();
  });
});
