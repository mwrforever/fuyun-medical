// 呼叫工作台单测（/ward/call-workbench，M16 病区呼叫闭环面前端面 · 暖纸卷宗换脸重排 丁-2）：
// 默认待应答筛选出网携 status=CREATED 与全院口径（G4：WARD_OPTIONS 假常量清零 → listOrgs
// WARD 档真数据三态）、待应答计数胶囊自 rows 过滤 CREATED 派生零出网（蓝图锚点①）、升级行
// 左缘分级橙规线行类与「已升级 N 次」徽标双通道（锚点②）、完成弹窗空摘要零出网拦截与
// .fuy-dialog/.fuy-form 挂类（锚点③）、闭环五动作状态互斥逐行断言（锚点④）、钤印族
// （已应答/已开始处理/已完成/已转接/已取消）与钤印永不承失败（锚点⑤）、门牌页首/级联容器
// （契约 ⑦.4）/空态脸/页脚「手动刷新取数」构图锚点、声音提示缺位注记文案冻结、PR-4F 元素
// 码权限态两态。
// api mock 承载零出网（vi.mock 整模块替身），断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { wardCalls } from '@/api/ward';
import type { WardCallVO } from '@/api/ward';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import CallWorkbenchView from './CallWorkbenchView.vue';

vi.mock('@/api/ward', () => ({
  CALL_STATUS_LABELS: {
    CREATED: '待应答',
    ANSWERED: '已应答',
    IN_PROGRESS: '处理中',
    COMPLETED: '已完成',
    TRANSFERRED: '已转接',
    CANCELLED: '已取消',
  },
  CALL_TYPE_LABELS: {
    NORMAL: '普通呼叫',
    EMERGENCY: '紧急呼叫',
    INFUSION: '输液呼叫',
    SERVICE: '服务请求',
  },
  CALL_SOURCE_LABELS: {
    BEDSIDE: '床头分机',
    BRROOM: '卫生间',
    PATIENT_PAD: '患者手环',
    NURSE_PAD: '护士 PDA',
    IOT: '物联网设备',
  },
  wardCalls: {
    page: vi.fn(),
    create: vi.fn(),
    answer: vi.fn(),
    progress: vi.fn(),
    complete: vi.fn(),
    transfer: vi.fn(),
    cancel: vi.fn(),
  },
}));

// 病区清单读端点替身（G4：listOrgs WARD 档真数据，WARD_OPTIONS 假常量清零零消费）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身 ElMessage（失败批注条断言用），其余导出原样保留供组件解析
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

/** 呼叫行（可覆写状态/类型/升级计数） */
function callMock(partial: Partial<WardCallVO> = {}): WardCallVO {
  return {
    id: '401',
    callNo: 'WC20260926001',
    wardId: '1001',
    bedId: '903',
    patientId: '1932000000000000001',
    deviceId: undefined,
    callType: 'NORMAL',
    source: 'BEDSIDE',
    status: 'CREATED',
    escalationCount: 0,
    processedBy: undefined,
    resultSummary: undefined,
    sourceRef: undefined,
    answeredAt: undefined,
    completedAt: undefined,
    createdAt: '2026-09-26T10:00:00+08:00',
    ...partial,
  };
}

/** 启用病区机构行（listOrgs WARD 档出参演示行，字段全可选与生成物同构） */
function orgMock(): {
  id: string;
  orgCode: string;
  orgName: string;
  orgType: 'WARD';
  sort: number;
} {
  return { id: '9101', orgCode: '1001', orgName: '演示病区', orgType: 'WARD', sort: 1 };
}

/** 空呼叫分页出参 */
function emptyPage() {
  return { content: [], page: '0', size: '20', total: '0' };
}

/** 按文本定位表格行 */
function findRow(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('tr').find((row) => row.text().includes(text));
}

/** 行内按钮文案集（行作用域动作集断言，防跨行误中） */
function rowButtons(row: DOMWrapper<Element> | undefined): string[] {
  if (!row) {
    throw new Error('未找到目标行');
  }
  return row.findAll('button').map((b) => b.text());
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

describe('病区呼叫工作台', () => {
  /** 文件级 Pinia：门牌批注行读 auth 会话显示名 + v-perm 指令读元素码，每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #39）：真实 NURSE 会话经登录契约导出含码，既有用例语义不变
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
          permissions: ['ward:call:btn:handle'],
        },
      }),
    );
    for (const fn of [
      wardCalls.page,
      wardCalls.create,
      wardCalls.answer,
      wardCalls.progress,
      wardCalls.complete,
      wardCalls.transfer,
      wardCalls.cancel,
      listOrgs,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(wardCalls.page).mockResolvedValue(emptyPage());
    vi.mocked(listOrgs).mockResolvedValue([orgMock()]);
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(CallWorkbenchView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('默认待应答筛选出网携 status=CREATED 与全院口径，listOrgs WARD 档真数据装载', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ escalationCount: 2, callType: 'EMERGENCY' }),
        callMock({
          callNo: 'WC20260926002',
          callType: 'INFUSION',
          source: 'IOT',
          status: 'ANSWERED',
        }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    // 病区筛选默认空串=全院口径（真数据源无恒定首项，「全部病区」空值选项保留）
    expect(wardCalls.page).toHaveBeenCalledWith({
      wardId: undefined,
      status: 'CREATED',
      page: 0,
      size: 50,
    });
    // G4：病区清单真数据装载（WARD 档请求参数在位）+「全部病区」空值选项保留
    expect(listOrgs).toHaveBeenCalledWith({ type: 'WARD' });
    const wardSelect = wrapper.find('select[aria-label="病区筛选"]');
    expect(wardSelect.find('option[value=""]').text()).toBe('全部病区');
    expect(wardSelect.text()).toContain('演示病区（1001）');
    const text = wrapper.text();
    expect(text).toContain('WC20260926001');
    expect(text).toContain('紧急呼叫');
    expect(text).toContain('输液呼叫');
    expect(text).toContain('床头分机');
    expect(text).toContain('物联网设备');
  });

  it('病区清单 listOrgs 三态：加载禁用 / 成功可选 / 失败说问题给恢复（G4 真数据三态）', async () => {
    // 加载态：清单在途挂起（手动放行 promise，隔离微任务时序），下拉禁用（防误选空选项）
    let resolveWards!: (value: OrgVO[]) => void;
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          resolveWards = resolve;
        }),
    );
    const loading = mount(CallWorkbenchView);
    await flushPromises();
    const loadingSelect = () =>
      loading.find('select[aria-label="病区筛选"]').element as HTMLSelectElement;
    expect(loadingSelect().disabled).toBe(true);
    // 成功可选：清单放行后解禁
    resolveWards([orgMock()]);
    await flushPromises();
    expect(loadingSelect().disabled).toBe(false);
    loading.unmount();

    // 失败态：说问题给恢复（role=alert 重试行，G6 禁伪装空选项；全院空值仍可筛不阻断读面）
    vi.mocked(listOrgs).mockRejectedValue(new Error('network down'));
    const failed = mount(CallWorkbenchView);
    await flushPromises();
    const failedSelect = () =>
      failed.find('select[aria-label="病区筛选"]').element as HTMLSelectElement;
    const alert = failed.find('.call-ward-error');
    expect(alert.exists()).toBe(true);
    expect(alert.attributes('role')).toBe('alert');
    expect(alert.text()).toContain('病区清单加载失败');
    expect(failedSelect().disabled).toBe(true);
    // 重试恢复：清单重载成功后下拉解禁（挂起 1 次 + 失败 1 次 + 重试 1 次）
    vi.mocked(listOrgs).mockResolvedValue([orgMock()]);
    await clickButton(failed, '重试');
    await flushPromises();
    expect(listOrgs).toHaveBeenCalledTimes(3);
    expect(failedSelect().disabled).toBe(false);
  });

  it('待应答计数胶囊自 rows 过滤 CREATED 派生（锚点①：混合态页内计数零出网）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ callNo: 'WC20260926002', status: 'ANSWERED' }),
        callMock({ callNo: 'WC20260926003', status: 'CREATED' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    // 琥珀胶囊（.fuy-status-pill--amber 契约类）：三行中 CREATED 计 2，非行集总数 3
    const pill = wrapper.find('.fuy-status-pill--amber');
    expect(pill.exists()).toBe(true);
    expect(pill.text()).toBe('待应答 2');
    // 派生计数零出网：列表仅初始一次分页拉取
    expect(wardCalls.page).toHaveBeenCalledTimes(1);
  });

  it('升级行=左缘分级橙规线行类与已升级徽标双通道（锚点②：escalationCount>0 行级可扫）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ escalationCount: 2 }),
        callMock({ callNo: 'WC20260926002', escalationCount: 0 }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    // 行级规线契约类只落在升级行（escalationCount>0），非升级行零污染
    const escalatedRow = wrapper
      .findAll('tr')
      .find((tr) => tr.classes().includes('call-escalated-row'));
    expect(escalatedRow).toBeDefined();
    expect(escalatedRow?.text()).toContain('WC20260926001');
    const plainRow = findRow(wrapper, 'WC20260926002');
    expect(plainRow).toBeDefined();
    expect(plainRow?.classes()).not.toContain('call-escalated-row');
    // 徽标通道与规线双通道同义（已升级 N 次文案机器判据）
    expect(wrapper.find('.fuy-call-escalation').exists()).toBe(true);
    expect(wrapper.text()).toContain('已升级 2 次');
  });

  it('应答出网携 callNo 并刷新列表，成功盖钤印「已应答」（锚点⑤钤印族）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.answer).mockResolvedValue(callMock({ status: 'ANSWERED' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '应答');
    await flushPromises();
    expect(wardCalls.answer).toHaveBeenCalledWith('WC20260926001');
    // 成功反馈=钤印（v-if 挂载制 + is-show 静态形态 + aria-live 批注语义）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已应答');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 列表重载（page 第二次调用）
    expect(wardCalls.page).toHaveBeenCalledTimes(2);
  });

  it('处理出网 progress 并盖钤印「已开始处理」（ANSWERED→IN_PROGRESS 开始到场处置）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock({ status: 'ANSWERED' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.progress).mockResolvedValue(callMock({ status: 'IN_PROGRESS' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '处理');
    await flushPromises();
    expect(wardCalls.progress).toHaveBeenCalledWith('WC20260926001');
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已开始处理');
    expect(wardCalls.page).toHaveBeenCalledTimes(2);
  });

  it('完成弹窗空摘要零出网拦截，携摘要出网 complete 并盖钤印「已完成」（锚点③）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock({ status: 'IN_PROGRESS' })],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.complete).mockResolvedValue(callMock({ status: 'COMPLETED' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '完成');
    await flushPromises();
    // 弹层规格（G2）：完成弹窗挂 .fuy-dialog 全局脸，表单容器挂 .fuy-form 承文书脸
    expect(wrapper.find('.fuy-dialog').exists()).toBe(true);
    expect(wrapper.find('.fuy-dialog .fuy-form').exists()).toBe(true);
    // 空摘要零出网（resultSummary 强制，拦截文案冻结）
    await clickButton(wrapper, '确认完成');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请填写处置结果');
    expect(wardCalls.complete).not.toHaveBeenCalled();
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    // 携摘要出网
    await wrapper.find('textarea[aria-label="处置结果"]').setValue('已到场处理完毕');
    await clickButton(wrapper, '确认完成');
    await flushPromises();
    expect(wardCalls.complete).toHaveBeenCalledWith('WC20260926001', {
      resultSummary: '已到场处理完毕',
    });
    // 成功反馈=钤印「已完成」（摘要强制留痕路径同一钤印族）
    const stamp = wrapper.find('.fuy-stamp-toast.is-show');
    expect(stamp.exists()).toBe(true);
    expect(stamp.text()).toBe('已完成');
    expect(wardCalls.page).toHaveBeenCalledTimes(2);
  });

  it('转接与取消出网携 callNo 并盖钤印「已转接」「已取消」', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ callNo: 'WC20260926002', status: 'ANSWERED' }),
      ],
      page: '0',
      size: '20',
      total: '2',
    });
    vi.mocked(wardCalls.transfer).mockResolvedValue(callMock({ status: 'TRANSFERRED' }));
    vi.mocked(wardCalls.cancel).mockResolvedValue(callMock({ status: 'CANCELLED' }));
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '转接');
    await flushPromises();
    expect(wardCalls.transfer).toHaveBeenCalledWith('WC20260926001');
    expect(wrapper.find('.fuy-stamp-toast.is-show').text()).toBe('已转接');
    await clickRowButton(wrapper, 'WC20260926001', '取消');
    await flushPromises();
    expect(wardCalls.cancel).toHaveBeenCalledWith('WC20260926001');
    expect(wrapper.find('.fuy-stamp-toast.is-show').text()).toBe('已取消');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
  });

  it('失败不盖钤印——业务拒绝走 message 批注条（锚点⑤：钤印永不承失败）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [callMock()],
      page: '0',
      size: '20',
      total: '1',
    });
    vi.mocked(wardCalls.answer).mockRejectedValue(
      Object.assign(new Error('biz reject'), { detail: '呼叫已被应答' }),
    );
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    await clickRowButton(wrapper, 'WC20260926001', '应答');
    await flushPromises();
    // 钤印零出现：失败与业务拒绝归 message 批注条（G2 分工）
    expect(wrapper.find('.fuy-stamp-toast').exists()).toBe(false);
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('呼叫已被应答');
    // 失败不刷新（行集驻留旧值）
    expect(wardCalls.page).toHaveBeenCalledTimes(1);
  });

  it('门牌页首与级联容器构图（契约 ⑦.4/⑧.1）：根禁挂 stagger、CALL DESK 小注、页脚手动刷新取数', async () => {
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    // 页面根节点禁挂 .fuy-stagger（flow 包裹 div 承接 flex+gap——契约 ⑦.4）
    const root = wrapper.find('.fuy-page');
    expect(root.classes()).toContain('call-workbench');
    expect(root.classes()).not.toContain('fuy-stagger');
    expect(wrapper.find('.call-flow.fuy-stagger').exists()).toBe(true);
    // 门牌页首三锚：衬线标题 + 英文小注（装饰层）+ 批注行（口径原文升批注 + 「谁」）
    expect(wrapper.find('.fuy-page-title').text()).toBe('病区呼叫工作台');
    expect(wrapper.find('.fuy-page-en').text()).toBe('CALL DESK');
    const note = wrapper.find('.fuy-page-note').text();
    expect(note).toContain('闭环操作按行状态暴露');
    expect(note).toContain('流转规则归后端把守');
    expect(note).toContain('签认人 李护士');
    // 页脚批注：数据时点 + 手动刷新取数语义（零实时订阅）
    expect(wrapper.find('.fuy-page-foot').text()).toContain('手动刷新取数');
  });

  it('空态脸（G6）：暂无呼叫记录主句 + 下一步说明，待应答胶囊 0 起面', async () => {
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无呼叫记录');
    expect(empty.find('.fuy-empty-hint').text()).toContain('可切换病区或状态筛选');
    // 空列表起面：待应答计数胶囊 0（派生口径零伪数据）
    expect(wrapper.find('.fuy-status-pill--amber').text()).toBe('待应答 0');
  });

  it('声音提示缺位注记文案冻结可见（本批次静默提示面留痕，锚点⑤文案冻结）', async () => {
    const wrapper = mount(CallWorkbenchView);
    await flushPromises();
    expect(wrapper.text()).toContain('声音提示本批次未接入');
  });

  it('闭环五动作按行状态互斥出位（锚点④：三态行各自动作集不串）', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ id: '402', callNo: 'WC20260926002', status: 'ANSWERED' }),
        callMock({ id: '403', callNo: 'WC20260926003', status: 'IN_PROGRESS' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mountView();
    await flushPromises();
    // CREATED 行：应答/转接/取消，零处理与完成（状态机 v-if 互斥冻结）
    const createdButtons = rowButtons(findRow(wrapper, 'WC20260926001'));
    expect(createdButtons).toEqual(expect.arrayContaining(['应答', '转接', '取消']));
    expect(createdButtons).not.toContain('处理');
    expect(createdButtons).not.toContain('完成');
    // ANSWERED 行：处理/转接/取消，零应答与完成
    const answeredButtons = rowButtons(findRow(wrapper, 'WC20260926002'));
    expect(answeredButtons).toEqual(expect.arrayContaining(['处理', '转接', '取消']));
    expect(answeredButtons).not.toContain('应答');
    expect(answeredButtons).not.toContain('完成');
    // IN_PROGRESS 行：完成/转接/取消，零应答与处理
    const progressButtons = rowButtons(findRow(wrapper, 'WC20260926003'));
    expect(progressButtons).toEqual(expect.arrayContaining(['完成', '转接', '取消']));
    expect(progressButtons).not.toContain('应答');
    expect(progressButtons).not.toContain('处理');
  });

  it('PR-4F 权限态：会话无 ward:call:btn:handle 时闭环五动作全隐藏（D-34）', async () => {
    // 三态行齐备（待应答/已应答/处理中）驱动应答/处理/完成/转接/取消五按钮全部出位
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ id: '402', callNo: 'WC20260926002', status: 'ANSWERED' }),
        callMock({ id: '403', callNo: 'WC20260926003', status: 'IN_PROGRESS' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u1' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 呼叫闭环五动作无码全隐藏（D-34 无码 DOM 移除）
    expect(buttonTexts).not.toContain('应答');
    expect(buttonTexts).not.toContain('处理');
    expect(buttonTexts).not.toContain('完成');
    expect(buttonTexts).not.toContain('转接');
    expect(buttonTexts).not.toContain('取消');
    // 呼叫清单读面不受元素码影响
    expect(wrapper.text()).toContain('WC20260926001');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 ward:call:btn:handle 时闭环五动作按状态机出位可见', async () => {
    vi.mocked(wardCalls.page).mockResolvedValue({
      content: [
        callMock({ callNo: 'WC20260926001', status: 'CREATED' }),
        callMock({ id: '402', callNo: 'WC20260926002', status: 'ANSWERED' }),
        callMock({ id: '403', callNo: 'WC20260926003', status: 'IN_PROGRESS' }),
      ],
      page: '0',
      size: '20',
      total: '3',
    });
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('应答');
    expect(buttonTexts).toContain('处理');
    expect(buttonTexts).toContain('完成');
    expect(buttonTexts).toContain('转接');
    expect(buttonTexts).toContain('取消');
    wrapper.unmount();
  });
});
