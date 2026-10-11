// 护理执行工作台页单测（PR-3 Task 14；暖纸卷宗 pages-2 甲-2 锚点重写页）：
// 本次为逐页蓝图钦定的结构锚重写页（工具条大标题+副注 → 门牌页首；380px 抽屉 → 520px
// .fuy-drawer；WARD_OPTIONS 假常量 → listOrgs({type:'WARD'}) 真数据三态；动作成功反馈 →
// 钤印 .fuy-stamp-toast 族），走回归红线出口四边界（逐次批准/严格度不降/原子同 PR/留痕）——
// 被门牌替换直接波及的旧标题断言随新契约重写，既有业务断言全量保留且严格度不降。
// 新增锚点断言：
// ①门牌页首（衬线标题「护理执行工作台」+ EXECUTION DESK 英文小注 + 病区/班次/日期三筛选
//   状态位 + 「执行人 谁 · 班次 · 五环节闭环」批注行 + 页内 h2 清零 + 页脚快照时点批注）；
// ②详情抽屉 520px 挂 .fuy-drawer 与「谁·何时」批注槽（单号行 → 批注槽 → 遥测条 → 时间线
//   → 核对流水 → 扫码核对 → 动作组纵向序）；
// ③钤印族（补签收=已签收/完成执行=已完成；失败不盖印走 message 批注条）与四列计数徽标；
// ④listOrgs 接线三态（请求 type=WARD 且病区未定档看板零出网/失败批注行+重试恢复/会话记忆
//   病区优先回填，键 nursing.exec.wardId 沿用）；
// ⑤看板行卡渲染上限（四列合计 >120 时按列收「已到底 · 共 N 条」签行，列内滚动承载）。
// 业务断言（冻结保留）：路由权限点 meta 登记（路由=权限点清单）、四列看板渲染锚点（列名/
// 行卡床号/医嘱摘要/计划时间/锚行类型标签/升级标记）、LONG 锚行抽屉不进操作流（操作组隐藏）、
// 输液遥测条按 patientId 组合渲染、PR-4F 双态权限（无码五动作全隐藏/含码按状态机出位）。
// api mock 承载零出网（vi.mock 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { executions, infusions } from '@/api/nursing';
import type { OrderExecutionVO } from '@/api/nursing';
import { infusionBoard } from '@/api/ward';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import { router } from '@/router';
import ExecutionWorkbenchView from './ExecutionWorkbenchView.vue';

vi.mock('@/api/nursing', () => ({
  SHIFT_OPTIONS: [
    { code: 'DAY', label: '白班' },
    { code: 'EVENING', label: '小夜班' },
    { code: 'NIGHT', label: '大夜班' },
  ],
  EXECUTION_TYPE_LABELS: { GENERIC: '通用给药', INFUSION: '输液' },
  CHECK_TYPE_OPTIONS: [
    { code: 'WRISTBAND', label: '腕带' },
    { code: 'BAG_LABEL', label: '瓶签' },
    { code: 'DEVICE', label: '执行单' },
  ],
  executions: {
    list: vi.fn(),
    trace: vi.fn(),
    signReceive: vi.fn(),
    check: vi.fn(),
    start: vi.fn(),
    finish: vi.fn(),
    cancel: vi.fn(),
  },
  infusions: { active: vi.fn() },
}));

vi.mock('@/api/ward', () => ({
  infusionBoard: { byWard: vi.fn() },
  INFUSION_ALERT_LABELS: { NONE: '正常', YELLOW: '黄档预警', ORANGE: '橙档告急', RED: '红档危急' },
}));

// 病区假常量清零（pages-2 甲-2 蓝图 5 节）：WARD_OPTIONS → listOrgs({type:'WARD'}) 真端点
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身 ElMessage（失败批注条/复制成功提示断言用），其余导出原样保留
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// jsdom 未实现 ResizeObserver：el-table/抽屉布局测量依赖（存量 spec 同款空壳）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 会话种子（auth store 从 sessionStorage 恢复：执行人=登录用户 u1/李护士；
 * permissions 含 #29 元素码——真实 NURSE 会话经登录契约导出含码，既有用例语义不变） */
function seedAuthSession(): void {
  sessionStorage.setItem(
    'fy:workstation:auth',
    JSON.stringify({
      token: 'test-token',
      refreshToken: 'test-refresh',
      user: {
        userId: 'u1',
        loginName: 'nursedemo',
        displayName: '李护士',
        orgId: null,
        roles: ['nurse'],
        permissions: ['nursing:execution:btn:perform'],
      },
    }),
  );
}

/** 病区清单 mock（V1123 种子双病区同源：W01 首项 + 1001 会话记忆回填锚） */
function wardMocks(): OrgVO[] {
  return [
    { id: '1', orgCode: 'W01', orgName: 'W01 演示病区', orgType: 'WARD' },
    { id: '2', orgCode: '1001', orgName: '1001 示范病区', orgType: 'WARD' },
  ];
}

/** 执行单行（状态/锚行/升级标记可覆写） */
function rowMock(partial: Partial<OrderExecutionVO> = {}): OrderExecutionVO {
  return {
    executionNo: 'EX2026100100001',
    m04OrderNo: 'M04202610010001',
    m04PlanNo: 'M04P2026100100001',
    visitId: 'I2026092300001',
    patientId: '1932000000000000001',
    wardId: 'W01',
    bedNo: '01',
    executionType: 'GENERIC',
    execItemName: '0.9% 氯化钠注射液',
    dosageText: '250ml qd',
    planTime: '2026-10-01T08:00:00',
    status: 'CREATED',
    ...partial,
  };
}

describe('护理执行工作台页', () => {
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    seedAuthSession();
    vi.mocked(listOrgs).mockReset().mockResolvedValue(wardMocks());
    vi.mocked(executions.list).mockReset().mockResolvedValue({ content: [], total: '0' });
    vi.mocked(executions.trace).mockReset().mockResolvedValue({
      executionNo: 'EX2026100100001',
      planTime: '2026-10-01T08:00:00',
      checkLogs: [],
    });
    vi.mocked(executions.signReceive)
      .mockReset()
      .mockResolvedValue(rowMock({ status: 'SIGNED' }));
    vi.mocked(executions.start)
      .mockReset()
      .mockResolvedValue(rowMock({ status: 'EXECUTING' }));
    vi.mocked(executions.finish)
      .mockReset()
      .mockResolvedValue(rowMock({ status: 'COMPLETED' }));
    vi.mocked(infusions.active).mockReset().mockResolvedValue([]);
    vi.mocked(infusionBoard.byWard).mockReset().mockResolvedValue({ devices: [] });
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
  });

  it('路由登记权限点 nursing:execution:perform（路由=权限点清单审计形态）', () => {
    expect(router.resolve('/nursing/execution').meta.permission).toBe('nursing:execution:perform');
  });

  it('门牌页首锚点：衬线标题+英文小注+三筛选状态位+执行人批注行，页内 h2 清零', async () => {
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 门牌页首三件套（契约 ⑧.1）：衬线标题 / 英文小注（装饰层）/ 2px 墨规收底走全局脸
    expect(wrapper.find('.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-title').text()).toBe('护理执行工作台');
    expect(wrapper.find('.fuy-page-en').text()).toBe('EXECUTION DESK');
    // 状态位=病区/班次/日期三筛选+查询钮（旧工具条首屏解散，筛选右挂门牌）
    expect(wrapper.find('.exec-ward').exists()).toBe(true);
    expect(wrapper.find('.exec-shift').exists()).toBe(true);
    expect(wrapper.find('.exec-date').exists()).toBe(true);
    const statusButtons = wrapper.findAll('.fuy-page-status button').map((b) => b.text());
    expect(statusButtons).toContain('查询');
    // 批注行=「执行人 谁 · 班次 · 五环节闭环」（旧 h2 大标题+副注降位，闭环口径语义不丢）
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('执行人 李护士');
    expect(note).toContain('五环节闭环');
    // 页内大标题清零（pages-2 G1：h2 由门牌衬线标题承担）
    expect(wrapper.findAll('h2')).toHaveLength(0);
    // 页脚批注：看板快照时点（禁放操作钮）
    expect(wrapper.find('.fuy-page-foot').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-foot').text()).toContain('看板快照');
    wrapper.unmount();
  });

  it('listOrgs 接线·加载态：请求 type=WARD、骨架占位、病区未定档看板零出网', async () => {
    // 病区清单悬而不决：看板必须等病区定档后才出网（空清单/失败态零执行单查询）
    vi.mocked(listOrgs).mockImplementation(() => new Promise<OrgVO[]>(() => {}));
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledWith({ type: 'WARD' });
    // 加载中骨架（G4 三态之一）：看板区被骨架替代，不渲染空列也不出网
    expect(wrapper.find('.exec-board-skeleton').exists()).toBe(true);
    expect(wrapper.find('.exec-board').exists()).toBe(false);
    // 病区下拉加载中禁用防误选（EP 2.14 禁用态类挂 .el-select__wrapper 内围合层）
    expect(wrapper.find('.exec-ward .el-select__wrapper').classes()).toContain('is-disabled');
    expect(executions.list).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('listOrgs 接线·失败态：批注行说问题给恢复，重试成功后看板照常加载', async () => {
    vi.mocked(listOrgs)
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(wardMocks());
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 失败态=「说问题+给恢复」批注行（错误态不伪装空态），重试入口可达
    const state = wrapper.find('.exec-ward-state');
    expect(state.exists()).toBe(true);
    expect(state.text()).toContain('病区清单加载失败');
    expect(state.find('button').text()).toBe('重试');
    await state.find('button').trigger('click');
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledTimes(2);
    // 恢复后病区定档取首项，看板四列照常渲染
    expect(executions.list).toHaveBeenCalled();
    expect(wrapper.findAll('.exec-column')).toHaveLength(4);
    wrapper.unmount();
  });

  it('listOrgs 接线·就绪态：会话记忆病区优先于首项回填（nursing.exec.wardId 沿用）', async () => {
    sessionStorage.setItem('nursing.exec.wardId', '1001');
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 清单就绪回填：记忆病区 1001 优先于首项 W01，看板按记忆病区出网
    expect(executions.list).toHaveBeenCalledWith(expect.objectContaining({ wardId: '1001' }));
    expect(wrapper.findAll('.exec-column')).toHaveLength(4);
    wrapper.unmount();
  });

  it('四列看板渲染锚点：列名/行卡床号/医嘱摘要/计划时间/锚行标签/升级标记', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [
        rowMock({ executionNo: 'E1', bedNo: '01', status: 'CREATED' }),
        rowMock({
          executionNo: 'E2',
          bedNo: '02',
          status: 'CREATED',
          m04PlanNo: undefined,
          executionType: 'INFUSION',
          overrideFlag: true,
          latestAlarmNo: 'AL2026100100001',
        }),
        rowMock({ executionNo: 'E3', bedNo: '03', status: 'EXECUTING' }),
      ],
      total: '3',
    });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 四列列名（spec 冻结渲染序）
    const headers = wrapper.findAll('.exec-column-title').map((node) => node.text());
    expect(headers).toEqual(['待签收', '待核对', '待执行', '执行中']);
    // 列头计数徽标（墨底纸字取色走样式层，spec 锚定计数数值与徽标类在位）
    const counts = wrapper.findAll('.exec-column-count').map((node) => node.text());
    expect(counts).toEqual(['2', '0', '0', '1']);
    // 行卡锚点：床号 + 医嘱摘要 + 计划时间
    const cards = wrapper.findAll('.exec-card');
    expect(cards).toHaveLength(3);
    expect(wrapper.find('.exec-card-bed').text()).toBe('01');
    expect(wrapper.find('.exec-card-item').text()).toContain('0.9% 氯化钠注射液');
    expect(wrapper.find('.exec-card-plan').text()).toContain('08:00');
    // 锚行类型标签区分两种快照语义 + 升级标记（越权/告警）
    expect(wrapper.text()).toContain('类型锚行');
    expect(wrapper.text()).toContain('执行快照');
    expect(wrapper.text()).toContain('越权');
    expect(wrapper.text()).toContain('告警');
    wrapper.unmount();
  });

  it('空看板渲染 .fuy-empty 空态脸（暂无执行单+给下一步，零默认插画）', async () => {
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    const empty = wrapper.find('.exec-board-empty');
    expect(empty.classes()).toContain('fuy-empty');
    expect(wrapper.find('.fuy-empty-title').text()).toBe('暂无执行单');
    // 说明句给下一步（切病区/班次/查询刷新），禁「暂无数据」裸词
    expect(wrapper.find('.fuy-empty-hint').text()).not.toBe('');
    // el-empty 默认纸箱插画零渲染（契约 ⑥ 空态脸收编）
    expect(wrapper.findComponent({ name: 'ElEmpty' }).exists()).toBe(false);
    wrapper.unmount();
  });

  it('点击行卡开抽屉：执行单号+复制按钮+闭环时间线渲染；锚行不进操作流', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [
        // 01 床 SIGNED（快照行，落待核对列）+ 02 床 CREATED 锚行（落待签收列=首列）
        rowMock({ executionNo: 'EX2026100100001', bedNo: '01', status: 'SIGNED' }),
        rowMock({
          executionNo: 'EX2026100100002',
          bedNo: '02',
          status: 'CREATED',
          m04PlanNo: undefined,
        }),
      ],
      total: '2',
    });
    vi.mocked(executions.trace).mockResolvedValue({
      executionNo: 'EX2026100100001',
      planTime: '2026-10-01T08:00:00',
      signedAt: '2026-10-01T08:05:00',
      checkLogs: [
        { checkType: 'WRISTBAND', checkResult: 'PASS', occurredAt: '2026-10-01T08:06:00' },
      ],
    });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    const cards = wrapper.findAll('.exec-card');
    expect(cards).toHaveLength(2);
    // 快照行（01 床，第二列）：抽屉开 + 单号 + 复制按钮 + 时间线 + 操作按钮组（SIGNED=核对/撤销）
    await cards[1].trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('modelValue')).toBe(true);
    // 抽屉升档锚点（380→520 承契约 drawer 档）：挂 .fuy-drawer 弹层脸 + 520px size
    expect(wrapper.find('.fuy-drawer').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('size')).toBe('520px');
    // 抽屉内部纵向序锚点：单号行 → 批注槽（谁·何时）在遥测条/时间线之前
    expect(wrapper.find('.exec-drawer-no').text()).toContain('EX2026100100001');
    const drawerNote = wrapper.find('.exec-drawer-note');
    expect(drawerNote.exists()).toBe(true);
    expect(drawerNote.text()).toContain('01 床');
    expect(drawerNote.text()).toContain('执行人 李护士');
    expect(wrapper.text()).toContain('复制单号');
    expect(wrapper.text()).toContain('计划');
    expect(wrapper.text()).toContain('签收');
    expect(wrapper.text()).toContain('核对通过');
    const buttons = wrapper.findAll('.exec-drawer-actions button').map((b) => b.text());
    expect(buttons).toContain('核对');
    expect(buttons).toContain('撤销');
    // 锚行（02 床，首列）：换选后操作组隐藏（不进操作流，时间线仍可追溯）
    await cards[0].trigger('click');
    await flushPromises();
    expect(wrapper.find('.exec-drawer-actions').exists()).toBe(false);
    expect(wrapper.text()).toContain('类型锚行不进操作流');
    wrapper.unmount();
  });

  it('输液遥测条按 patientId 组合渲染（护理侧在途行 × ward 侧设备遥测）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'E1', status: 'EXECUTING', executionType: 'INFUSION' })],
      total: '1',
    });
    vi.mocked(infusions.active).mockResolvedValue([
      {
        executionNo: 'EX2026100100001',
        patientId: '1932000000000000001',
        visitId: 'I2026092300001',
        wardId: 'W01',
        bedNo: '01',
        execItemName: '0.9% 氯化钠注射液',
        startedAt: '2026-10-01T08:10:00',
        iotDeviceId: 'DEV-001',
        escalationCount: 2,
      },
    ]);
    vi.mocked(infusionBoard.byWard).mockResolvedValue({
      devices: [
        { deviceId: 'DEV-001', remainLatest: 120, dropRateLatest: 45, alertLevel: 'YELLOW' },
      ],
    });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    // 遥测条组合值：余量 120ml + 滴速 45 + 黄档预警（组合降级 UI 内不体现，仅数据缺位不渲染）
    expect(wrapper.find('.exec-infusion-strip').exists()).toBe(true);
    expect(wrapper.find('.exec-infusion-strip').text()).toContain('120');
    expect(wrapper.find('.exec-infusion-strip').text()).toContain('45');
    expect(wrapper.find('.exec-infusion-strip').text()).toContain('黄档预警');
    wrapper.unmount();
  });

  it('看板行卡渲染上限：四列合计>120 时按列收「已到底 · 共 N 条」签行', async () => {
    // 40+40+40+5=125 行（>120 阈值）：前三列各 40 行超单列渲染上限出签行，执行中列 5 行不截断
    const rows: OrderExecutionVO[] = [];
    const pushRows = (status: string, count: number): void => {
      for (let i = 0; i < count; i += 1) {
        rows.push(rowMock({ executionNo: `E${status}${i}`, bedNo: String(i + 1), status }));
      }
    };
    pushRows('CREATED', 40);
    pushRows('SIGNED', 40);
    pushRows('CHECKED', 40);
    pushRows('EXECUTING', 5);
    vi.mocked(executions.list).mockResolvedValue({ content: rows, total: '125' });
    const wrapper = mount(ExecutionWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();
    // 截断列收签行（签行锚点：已到底 + 该列完整行数），未截断列零签行
    const foots = wrapper.findAll('.exec-column-foot');
    expect(foots).toHaveLength(3);
    expect(foots[0].text()).toContain('已到底');
    expect(foots[0].text()).toContain('共 40 条');
    // 渲染预算锁：截断列行卡数=单列上限（30=120/4），签行后零渲染
    const firstColumn = wrapper.findAll('.exec-column')[0];
    expect(firstColumn.findAll('.exec-card')).toHaveLength(30);
    // 四列合计 ≤120 时不截断（对照：执行中列 5 行全渲染且无签行）
    const lastColumn = wrapper.findAll('.exec-column')[3];
    expect(lastColumn.findAll('.exec-card')).toHaveLength(5);
    expect(lastColumn.find('.exec-column-foot').exists()).toBe(false);
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(ExecutionWorkbenchView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('动作成功盖钤印：补签收成功盖「已签收」朱印（.fuy-stamp-toast 落章）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'CREATED' })],
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    const signButton = wrapper
      .findAll('.exec-drawer-actions button')
      .find((b) => b.text() === '补签收');
    expect(signButton).toBeDefined();
    await signButton!.trigger('click');
    await flushPromises();
    // 成功=钤印（pages-2 G2：文案=动词结果，≤1200ms 收场）；jsdom 实时钟内 is-show 在位
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toContain('已签收');
    wrapper.unmount();
  });

  it('动作成功盖钤印：完成执行成功盖「已完成」朱印', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'EXECUTING' })],
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    const finishButton = wrapper
      .findAll('.exec-drawer-actions button')
      .find((b) => b.text() === '完成执行');
    expect(finishButton).toBeDefined();
    await finishButton!.trigger('click');
    await flushPromises();
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toContain('已完成');
    wrapper.unmount();
  });

  it('动作失败不盖钤印（失败=message 批注条，钤印永不承失败）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'CREATED' })],
      total: '1',
    });
    // 业务拒绝对象（api 层直抛 ProblemDetail 形态）：surfaceBizError 弹 detail 批注条
    vi.mocked(executions.signReceive).mockRejectedValue({ detail: '执行单已签收，请勿重复操作' });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    const signButton = wrapper
      .findAll('.exec-drawer-actions button')
      .find((b) => b.text() === '补签收');
    await signButton!.trigger('click');
    await flushPromises();
    // 失败走 message 批注条（ElMessage.error），零钤印
    expect(ElMessage.error).toHaveBeenCalledWith('执行单已签收，请勿重复操作');
    expect(wrapper.find('.fuy-stamp-toast.is-show').exists()).toBe(false);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话无 nursing:execution:btn:perform 时抽屉五动作全隐藏（D-34 无码全移除）', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'SIGNED' })],
      total: '1',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    // 抽屉可开（读面不受限）；动作容器由 v-if（状态机）渲染，内部按钮经 v-perm 逐个
    // DOM 移除——无码即五动作全隐藏（D-34），容器残留空壳不构成可达入口
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('modelValue')).toBe(true);
    expect(wrapper.find('.exec-drawer-actions').findAll('button')).toHaveLength(0);
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 nursing:execution:btn:perform 时抽屉五动作按状态机出位', async () => {
    vi.mocked(executions.list).mockResolvedValue({
      content: [rowMock({ executionNo: 'EX2026100100001', status: 'SIGNED' })],
      total: '1',
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.exec-card').trigger('click');
    await flushPromises();
    const buttons = wrapper.findAll('.exec-drawer-actions button').map((b) => b.text());
    expect(buttons).toContain('核对');
    expect(buttons).toContain('撤销');
    wrapper.unmount();
  });
});
