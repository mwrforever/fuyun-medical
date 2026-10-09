// 工作站首页单测（批次 2 册 2 报表式真数据首页）：门牌页首沿用 auth store（空会话防御
// 兜底）；两聚合域（overview/events）真数据渲染逐项断言可指源；三态（骨架/错误/空态）
// 与错误重试；STOMP 多端点订阅登记（候诊表真实 deptCode 填充叫号主题模板）、叫号帧入流
// （患者姓名不渲染）与卸载清理（订阅退订 + 连接断开）。会话以直接注入 state 方式承载
// （假令牌资产）；出网面与 STOMP 面均经 vi.mock 隔离（纯单元，不真实建连不出网）。
// 注：每用例新 Pinia 实例保证会话态互不串扰；vi.resetModules 隔离 composable 模块态。
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import HomeView from './HomeView.vue';
import type { WorkbenchEventsVO, WorkbenchOverviewVO } from '@/api/dashboard';

/** STOMP 模块桩捕获面（vi.hoisted：工厂提升后仍可引用；逐用例复位） */
const stomp = vi.hoisted(() => ({
  /** 已捕获订阅：endpoint + destination + 帧回调 + 退订 spy */
  handles: [] as {
    endpoint: string;
    destination: string;
    onFrame: (payload: unknown) => void;
    unsubscribe: ReturnType<typeof vi.fn>;
  }[],
  connectCalls: vi.fn(),
  disconnectCalls: vi.fn(),
  /** 桩内连接态（默认已连接，供 live 指示断言；用例可改写） */
  state: 'connected',
}));

vi.mock('@/composables/useIotStomp', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/composables/useIotStomp')>();
  const { ref } = await import('vue');
  return {
    ...actual,
    connect: stomp.connectCalls,
    disconnect: stomp.disconnectCalls,
    subscribeTopic: (
      destination: string,
      onFrame: (payload: unknown) => void,
      endpoint: string,
    ) => {
      const handle = { unsubscribe: vi.fn() };
      stomp.handles.push({ endpoint, destination, onFrame, unsubscribe: handle.unsubscribe });
      return { id: `sub-${stomp.handles.length}`, unsubscribe: handle.unsubscribe };
    },
    connectionStateOf: () => ref(stomp.state),
  };
});

/** dashboard 出网桩捕获面（逐用例改写 resolve/reject 行为） */
const api = vi.hoisted(() => ({
  overview: vi.fn(),
  events: vi.fn(),
}));

vi.mock('@/api/dashboard', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/dashboard')>();
  return { ...actual, dashboard: { overview: api.overview, events: api.events } };
});

/** 总览快照真值夹具：趋势 14 点（前 7 日和 70 / 近 7 日和 148 → 周同比 +111.4%） */
function buildOverview(): WorkbenchOverviewVO {
  const zeros = (base: number, count: number): { statDate: string; visitCount: string }[] =>
    Array.from({ length: count }, (_, index) => ({
      statDate: `2026-10-${String(index + 1).padStart(2, '0')}`,
      visitCount: String(base),
    }));
  return {
    metrics: {
      todayVisits: '1284',
      waitingCount: '37',
      todayIncomeFen: '260000',
      inHospitalCount: '812',
      pendingDispenseCount: '34',
      pendingSettleCount: '21',
    },
    trend: [...zeros(10, 7), ...zeros(20, 6), { statDate: '2026-10-09', visitCount: '28' }],
    waitingTable: [
      { deptCode: 'D01', waitingCount: '11', longestWaitingMinutes: '18' },
      { deptCode: 'D02', waitingCount: '9', longestWaitingMinutes: '14' },
    ],
    generatedAt: '2026-10-09T10:42:00+08:00',
  };
}

/** 事件流真值夹具：三主题指引 + 两轮询源行 + 危急值降级标志 */
function buildEvents(): WorkbenchEventsVO {
  return {
    topics: [
      {
        endpoint: '/ws/outpatient',
        topic: '/topic/outpatient/queue/{deptCode}',
        description: '诊区候诊叫号推送',
      },
    ],
    events: [
      {
        id: 'F1',
        type: 'FEE_PENDING',
        source: 'billing',
        title: '腹部彩超',
        amountFen: '12500',
        occurredAt: '2026-10-09T10:37:00+08:00',
      },
      {
        id: 'P1',
        type: 'DISPENSE_PENDING',
        source: 'pharmacy',
        title: 'FITRX0002',
        amountFen: undefined,
        occurredAt: '2026-10-09T10:30:00+08:00',
      },
    ],
    criticalValues: [],
    criticalValueDegraded: true,
    generatedAt: '2026-10-09T10:42:30+08:00',
  };
}

/** 批注行日期格式锚点（YYYY-MM-DD 周X，中文星期单字） */
const DATE_PATTERN = /\d{4}-\d{2}-\d{2} 周[日一二三四五六]/;

describe('workstation 工作站首页（报表式真数据）', () => {
  /** 文件级 Pinia：每用例新实例保证会话态互不串扰 */
  let pinia: Pinia;

  /**
   * 构造带权限点集的测试会话。
   *
   * @param permissions 权限点编码集；空数组 = 无任何业务权限的授权会话（PR-4D 全拒口径）
   */
  function injectSession(permissions: string[]): void {
    const auth = useAuthStore();
    auth.token = 'home-access-token'; // 测试假令牌资产，非真实凭证
    auth.user = {
      userId: '1',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: undefined,
      roles: ['ADMIN'],
      permissions,
    };
  }

  /** 挂载已就绪的首页（会话已注入 + 两域桩默认返回真值夹具 + 微任务冲刷） */
  async function mountReady(): Promise<ReturnType<typeof mount>> {
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    await flushPromises();
    return wrapper;
  }

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：首页消费路由表反查权限点，与当前停留路由解耦，只求路由器就绪
    await router.push('/login');
    stomp.handles = [];
    stomp.connectCalls.mockClear();
    stomp.disconnectCalls.mockClear();
    // 断开语义镜像真实模块契约（useIotStomp.disconnect：先遍历在册句柄逐一退订再断开），
    // 供卸载清理用例对「每只句柄真实退订」做逐项调用断言
    stomp.disconnectCalls.mockImplementation(() => {
      for (const handle of stomp.handles) {
        handle.unsubscribe();
      }
      return Promise.resolve();
    });
    stomp.state = 'connected';
    api.overview.mockReset();
    api.events.mockReset();
    api.overview.mockResolvedValue(buildOverview());
    api.events.mockResolvedValue(buildEvents());
  });

  it('门牌页首渲染问候语与「登录名 · 日期」批注行（删岗后无岗位段）', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    expect(wrapper.text()).toContain('，系统管理员');
    expect(wrapper.text()).toContain('登录名 admin ·');
    expect(wrapper.text()).toMatch(DATE_PATTERN);
    expect(wrapper.text()).not.toContain('当前岗位');
    wrapper.unmount();
  });

  it('指标带六格真数据渲染：值可逐项指源 metrics，金额经分→元集中换算', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    const values = wrapper.findAll('.stat-value').map((node) => node.text());
    expect(values).toEqual(['1,284', '37', '¥2600.00', '812', '34', '21']);
    const labels = wrapper.findAll('.stat-label').map((node) => node.text());
    expect(labels).toEqual([
      '今日挂号',
      '当前候诊',
      '今日收入',
      '在院患者',
      '待发药处方',
      '待结算费用',
    ]);
    wrapper.unmount();
  });

  it('趋势图双序列与周同比由同一真实序列推导：近 7 日和 148 vs 前 7 日和 70 → +111.4%', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    // 双序列 polyline（当期墨实线 + 上周同期灰虚线）+ 周同比文案
    expect(wrapper.findAll('polyline.chart-line-main')).toHaveLength(1);
    expect(wrapper.findAll('polyline.chart-line-comp')).toHaveLength(1);
    expect(wrapper.find('.trend-note').text()).toBe('周同比 +111.4%');
    // 末点值标签=近 7 日末日真实计数 28
    expect(wrapper.find('text.chart-val').text()).toBe('28');
    wrapper.unmount();
  });

  it('全零趋势诚实缺示：周同比渲染 —（分母为零不造数），零线仍真实绘制', async () => {
    injectSession([]);
    const zero = buildOverview();
    zero.trend = (zero.trend ?? []).map((point) => ({ ...point, visitCount: '0' }));
    api.overview.mockResolvedValue(zero);
    const wrapper = await mountReady();
    expect(wrapper.find('.trend-note').text()).toBe('周同比 —');
    expect(wrapper.find('polyline.chart-line-main').exists()).toBe(true);
    wrapper.unmount();
  });

  it('候诊表渲染真实 deptCode 行（不造科室中文名），最长等待取自 waitingTable', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    const rows = wrapper.findAll('.waiting-table tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.text()).toContain('D01');
    expect(rows[0]?.text()).toContain('11');
    expect(rows[0]?.text()).toContain('18 分');
    // 指标带「当前候诊」格 delta=最长等待（同源推导；首格 delta 为今日挂号的周同比）
    const deltas = wrapper.findAll('.stat-delta').map((node) => node.text());
    expect(deltas).toContain('最长等待 18 分');
    wrapper.unmount();
  });

  it('事件流渲染 REST 轮询源行：来源/类型词表中文 + 金额分→元，危急值降级文案按标志渲染', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    const items = wrapper.findAll('.stream-item');
    expect(items).toHaveLength(2);
    expect(items[0]?.text()).toContain('收费管理 · 待支付费用');
    expect(items[0]?.text()).toContain('腹部彩超 · ¥125.00');
    expect(items[1]?.text()).toContain('药房管理 · 待配药处方');
    // 危急值恒空 + degraded=true → 降级文案（不渲染危急条目）
    expect(wrapper.find('.critical-note').text()).toContain('危急值通道未接入');
    wrapper.unmount();
  });

  it('STOMP 订阅按候诊表真实 deptCode 填充指引模板（多端点登记 /ws/outpatient）', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    const destinations = stomp.handles.map((handle) => handle.destination).sort();
    expect(destinations).toEqual(['/topic/outpatient/queue/D01', '/topic/outpatient/queue/D02']);
    expect(stomp.handles.every((handle) => handle.endpoint === '/ws/outpatient')).toBe(true);
    // live 指示反映真实订阅面与连接态（桩态 connected）
    expect(wrapper.find('.live').text()).toContain('实时推送已连接');
    wrapper.unmount();
  });

  it('模板填充被拒：非法 deptCode 诊区 warn 留痕跳过订阅，合法诊区照常登记（fillTopicTemplate 拒绝分支）', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    injectSession([]);
    // 「D 01」含空白字符 → fillTopicTemplate 非法字符拒绝；D02 合法照常订阅（跳过不中断其余）
    const rejected = buildOverview();
    rejected.waitingTable = [
      { deptCode: 'D 01', waitingCount: '11', longestWaitingMinutes: '18' },
      { deptCode: 'D02', waitingCount: '9', longestWaitingMinutes: '14' },
    ];
    api.overview.mockResolvedValue(rejected);
    const wrapper = await mountReady();
    expect(stomp.handles.map((handle) => handle.destination)).toEqual([
      '/topic/outpatient/queue/D02',
    ]);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('叫号主题模板填充被拒，跳过该诊区订阅'),
      expect.anything(),
    );
    warnSpy.mockRestore();
    wrapper.unmount();
  });

  it('events 迟于 overview 到达仍完成叫号订阅（首拉时序竞态回归）', async () => {
    injectSession([]);
    // events 悬置：overview 先落、topics 后到——订阅 watch 必须随 topics 到达重触发
    let resolveEvents!: (value: WorkbenchEventsVO) => void;
    api.events.mockReturnValue(
      new Promise<WorkbenchEventsVO>((resolve) => (resolveEvents = resolve)),
    );
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    await flushPromises();
    expect(stomp.handles).toHaveLength(0); // topics 未就绪：不猜参订阅（零伪数据边界）
    resolveEvents(buildEvents());
    await flushPromises();
    const destinations = stomp.handles.map((handle) => handle.destination).sort();
    expect(destinations).toEqual(['/topic/outpatient/queue/D01', '/topic/outpatient/queue/D02']);
    wrapper.unmount();
  });

  it('叫号帧入流：真实帧载荷渲染票号+诊室，患者姓名字段不渲染（全院面脱敏从严）', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    const handle = stomp.handles[0];
    handle?.onFrame({ type: 'CALLED', ticketNo: 'T090', patientName: '张三', room: '3' });
    await flushPromises();
    // 帧行按 occurredAt 降序归位（夹具 REST 行时点可能晚于收帧钟面，按内容定位不绑排序位）
    const wsRow = wrapper.findAll('.stream-item').find((node) => node.text().includes('T090'));
    expect(wsRow).toBeDefined();
    expect(wsRow?.text()).toContain('门诊叫号 · D01');
    expect(wsRow?.text()).toContain('T090 已叫号（3 诊室）');
    expect(wsRow?.text()).not.toContain('张三');
    wrapper.unmount();
  });

  it('卸载清理：STOMP 订阅全部退订 + 连接断开（web B.3-3 卸载条款）', async () => {
    injectSession([]);
    const wrapper = await mountReady();
    expect(stomp.handles.length).toBeGreaterThan(0);
    wrapper.unmount();
    // 退订经 disconnect 单出口完成（组件不逐句柄自退）：桩按真实模块契约遍历退订后，
    // 逐只句柄断言退订 spy 真实被调用（禁 every(函数对象) 恒真空断言）
    stomp.handles.forEach((handle) => expect(handle.unsubscribe).toHaveBeenCalled());
    expect(stomp.disconnectCalls).toHaveBeenCalled();
  });

  it('三态·加载骨架：首拉在途渲染骨架占位且不渲染内容（min-height 锁 CLS）', async () => {
    injectSession([]);
    api.overview.mockReturnValue(new Promise(() => {}));
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    await flushPromises();
    expect(wrapper.findAll('.sk').length).toBeGreaterThan(0);
    expect(wrapper.find('.stat-value').exists()).toBe(false);
    wrapper.unmount();
  });

  it('三态·错误与重试：总览域失败渲染错误态，重试成功后内容归位', async () => {
    injectSession([]);
    api.overview.mockRejectedValue(new Error('network down'));
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    await flushPromises();
    expect(wrapper.text()).toContain('总览数据加载失败');
    expect(wrapper.find('button.home-retry').exists()).toBe(true);
    // 重试：桩改为成功 → 内容归位
    api.overview.mockResolvedValue(buildOverview());
    await wrapper.find('button.home-retry').trigger('click');
    await flushPromises();
    expect(wrapper.find('.stat-value').exists()).toBe(true);
    wrapper.unmount();
  });

  it('两域错误独立：事件域失败仅侧栏错误态，主栏总览内容不受牵连', async () => {
    injectSession([]);
    api.events.mockRejectedValue(new Error('events down'));
    const wrapper = await mountReady();
    expect(wrapper.text()).toContain('事件流加载失败');
    expect(wrapper.find('.stat-value').exists()).toBe(true);
    wrapper.unmount();
  });

  it('三态·诚实空态：候诊表空与事件空各出真实语义空态（非占位 lorem）', async () => {
    injectSession([]);
    const empty = buildOverview();
    empty.waitingTable = [];
    api.overview.mockResolvedValue(empty);
    const emptyEvents = buildEvents();
    emptyEvents.events = [];
    api.events.mockResolvedValue(emptyEvents);
    const wrapper = await mountReady();
    expect(wrapper.text()).toContain('各诊区当前无候诊患者');
    expect(wrapper.text()).toContain('暂无待办工作事件');
    wrapper.unmount();
  });

  it('常用入口仅权限单道过滤（与侧栏同口径），空权限会话渲染诚实权限空态', async () => {
    injectSession(['nursing:ward:view']);
    const wrapper = await mountReady();
    const labels = wrapper
      .findAll('a.home-quick-link .home-quick-link-label')
      .map((node) => node.text());
    expect(labels).toEqual(['护士站']);
    wrapper.unmount();

    // 空权限会话（重置注入）→ 诚实权限空态
    injectSession([]);
    const denied = mount(HomeView, { global: { plugins: [pinia, router] } });
    await flushPromises();
    expect(denied.text()).toContain('当前会话未被授予任何业务功能的访问权限');
    expect(denied.find('a.home-quick-link').exists()).toBe(false);
    denied.unmount();
  });

  it('空会话兜底渲染未登录防御文案（直挂场景不渲染裸 undefined）', async () => {
    const wrapper = await mountReady();
    expect(wrapper.text()).toContain('，未登录用户');
    expect(wrapper.text()).toContain('登录名 —');
    expect(wrapper.text()).not.toContain('undefined');
    wrapper.unmount();
  });
});
