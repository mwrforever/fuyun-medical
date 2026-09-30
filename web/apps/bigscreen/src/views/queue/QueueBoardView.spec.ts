// 候诊叫号大屏页单测（FU-M03-05 大屏端前端面）：核心断言——令牌运行期获取失败（tokenFailed）
// 时整页横幅【链路禁用】；正常链路：快照首屏榜单前 8 条渲染 + 订阅挂接；CALLED 帧驱动当前
// 叫号卡与该票离队；断线横幅呈现。api/STOMP 模块 mock 承载，禁真实网络（拒建连零出网语义
// 归 useQueueStomp.spec 承载，本文件只断言页面横幅与渲染）。
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { getQueueSnapshot } from '@/api/outpatientQueue';
import type { QueueTicketVO } from '@/api/outpatientQueue';
import QueueBoardView from './QueueBoardView.vue';

/** mock 捕获状态（hoisted：捕获的订阅帧回调，逐用例手动复位；令牌态经导出 ref 翻转） */
const h = vi.hoisted(() => ({
  onFrame: null as null | ((notice: unknown) => void),
  unsubscribeCalls: 0,
}));

vi.mock('@/api/outpatientQueue', () => ({
  getQueueSnapshot: vi.fn(),
}));

vi.mock('@/composables/useQueueStomp', () => {
  // connectionState/tokenFailed 以真实 ref 承载（组件 computed 消费其 .value 响应性）
  const connectionState = ref<string>('connecting');
  const tokenFailed = ref<boolean>(false);
  return {
    connectionState,
    tokenFailed,
    connect: vi.fn(),
    subscribeQueue: vi.fn(
      (_deptCode: string, onFrame: (notice: unknown) => void): { unsubscribe: () => void } => {
        h.onFrame = onFrame;
        return { unsubscribe: () => void (h.unsubscribeCalls += 1) };
      },
    ),
    disconnect: vi.fn().mockResolvedValue(undefined),
    queueTopicPath: (deptCode: string) => `/topic/outpatient/queue/${deptCode}`,
  };
});

// 连接/令牌状态 ref 引用（用例内直接翻转驱动断线与失败横幅断言）
// eslint 提示：与 mock 工厂共享模块作用域，非未使用导入
import { connectionState, tokenFailed } from '@/composables/useQueueStomp';

function ticketMock(
  no: string,
  status: QueueTicketVO['status'] = 'WAITING',
  triageLevel?: number,
): QueueTicketVO {
  return {
    id: no,
    visitId: `O20260921${no}`,
    queueId: 'DEPT-INT',
    ticketNo: no,
    ticketType: 'FIRST',
    priorityScore: 300,
    queueSeq: 1,
    status,
    patientName: '张*',
    // 票面分诊级别（W-29 契约消费）：undefined=可空态（非分级流程票据）
    triageLevel,
  };
}

/** 挂载组件（内存 history 路由，query 可书签化形态与生产一致） */
async function mountBoard(
  query = '',
): Promise<{ wrapper: ReturnType<typeof mount>; router: ReturnType<typeof createRouter> }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/queue', component: QueueBoardView }],
  });
  await router.push(`/queue${query}`);
  await router.isReady();
  const wrapper = mount(QueueBoardView, { global: { plugins: [router] } });
  await flushPromises();
  return { wrapper, router };
}

describe('候诊叫号大屏', () => {
  beforeEach(() => {
    vi.mocked(getQueueSnapshot).mockReset();
    h.onFrame = null;
    h.unsubscribeCalls = 0;
    (connectionState as { value: string }).value = 'connecting';
    (tokenFailed as { value: boolean }).value = false;
  });

  it('令牌运行期获取失败（tokenFailed）：整页横幅随状态响应式翻转，看板主体整段替换', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([ticketMock('A001')]);
    const { wrapper } = await mountBoard();
    await flushPromises();
    // 正常态渲染看板主体（横幅不出现——失败态由 composable 异步置位，页面只读消费）
    expect(wrapper.text()).not.toContain('数据链路已禁用');

    // 模拟初始建连取令牌失败（useQueueStomp 置 tokenFailed=true）：横幅出现且替换看板主体
    (tokenFailed as { value: boolean }).value = true;
    await flushPromises();
    expect(wrapper.text()).toContain('大屏令牌获取失败，数据链路已禁用');
    expect(wrapper.text()).toContain('运行期自动获取');
    expect(wrapper.find('.queue-board-waiting').exists()).toBe(false);
    wrapper.unmount();
  });

  it('正常链路：REST 快照首屏 + WS 订阅挂接，候诊榜渲染前 8 条与状态角标', async () => {
    // 11 行快照：榜单只渲染前 8 条（§7.2 slice 承载）
    vi.mocked(getQueueSnapshot).mockResolvedValue(
      Array.from({ length: 11 }, (_, i) => ticketMock(`A${String(i + 1).padStart(3, '0')}`)),
    );
    const { wrapper } = await mountBoard('?dept=DEPT-INT');
    await flushPromises();

    expect(vi.mocked(getQueueSnapshot)).toHaveBeenCalledWith('DEPT-INT');
    const { connect, subscribeQueue } = await import('@/composables/useQueueStomp');
    expect(vi.mocked(connect)).toHaveBeenCalledWith('DEPT-INT');
    expect(vi.mocked(subscribeQueue)).toHaveBeenCalledWith('DEPT-INT', expect.any(Function));
    // 榜单容量断言：11 行快照渲染 8 行（两列 grid 4×2）
    expect(wrapper.findAll('.queue-board-row')).toHaveLength(8);
    expect(wrapper.text()).toContain('A001');
    expect(wrapper.text()).toContain('候诊');
    wrapper.unmount();
  });

  it('分诊级别角标：快照行有分级渲染对应级别角标，可空行不渲染占位（W-29 契约消费）', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock('A001', 'WAITING', 1),
      ticketMock('A002', 'WAITING'),
    ]);
    const { wrapper } = await mountBoard('?dept=DEPT-INT');
    await flushPromises();

    // 有分级行：角标携级别词表文案与四级色 modifier；可空行整段隐藏（榜单不出现第二枚角标）
    const badges = wrapper.findAll('.queue-board-row-triage');
    expect(badges).toHaveLength(1);
    expect(badges[0].text()).toBe('Ⅰ级');
    expect(badges[0].classes()).toContain('is-l1');
    wrapper.unmount();
  });

  it('CALLED 帧：当前叫号卡 :key 票号重挂（引导语/票号/脱敏姓名）且该票从榜单离队', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([
      ticketMock('A008'),
      ticketMock('A009', 'CALLED'),
    ]);
    const { wrapper } = await mountBoard();
    await flushPromises();
    expect(h.onFrame).not.toBeNull();

    // 投递 CALLED 帧（票号 A009）：当前叫号卡更新 + 榜单该票移除
    h.onFrame?.({
      type: 'CALLED',
      ticketNo: 'A009',
      patientName: '张*',
      doctorId: 'u1',
      room: '3',
    });
    await flushPromises();
    expect(wrapper.text()).toContain('请 A009 号到');
    expect(wrapper.text()).toContain('3');
    expect(wrapper.text()).toContain('诊室');
    expect(wrapper.text()).toContain('张*');
    const tickets = wrapper.findAll('.queue-board-row-ticket').map((node) => node.text());
    expect(tickets).not.toContain('A009');
    expect(tickets).toContain('A008');
    wrapper.unmount();
  });

  it('断线横幅：connectionState 回归断开态呈现「连接中断，自动重连中」', async () => {
    vi.mocked(getQueueSnapshot).mockResolvedValue([]);
    const { wrapper } = await mountBoard();
    await flushPromises();
    expect(wrapper.text()).not.toContain('连接中断，自动重连中');

    (connectionState as { value: string }).value = 'disconnected';
    await flushPromises();
    expect(wrapper.text()).toContain('连接中断，自动重连中');
    wrapper.unmount();
  });
});
