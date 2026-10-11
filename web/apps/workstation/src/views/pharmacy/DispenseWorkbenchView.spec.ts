// 药房发药工作台单测（FU-M06-04 前端面）：追溯码为空点配药被前置拦截不出网（「无码不结」
// 前端面）、配药→核对→发药签名依次出网且都以回传单号调用（单据锚点由后端回传，页面不自造）；
// 核对在途防抖（W-22⑥）：慢响应窗口内按钮禁用且二次点击零出网，结束后复位可再点。
// 暖纸换脸构图锚点（逐页蓝图 P11）：构图性断言先红后绿——门牌页首（衬线标题/队列计数胶囊/
// 签认人·时刻批注行）、队列空态 .fuy-empty 脸、双签步点条四节点与当前步高亮、动作钮按当前步
// 视觉分级（断样式挂类不断启停逻辑）；保留性锚对重排前实现即绿——队列双态合并出网、选单无单
// 提示、双签辅助启停面（isPicker），防换脸过程丢失既有业务行为。
// api mock 承载，不打真实网络；双签同人拒绝由后端 PH-1011 硬守卫，不在前端断言后端行为。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  issueDispense,
  listDispenses,
  listPrescriptions,
  pickDispense,
  verifyDispense,
} from '@/api/pharmacy';
import type { DispenseVO } from '@/api/pharmacy';
import { permDirective } from '@/directives/perm';
import DispenseWorkbenchView from './DispenseWorkbenchView.vue';

vi.mock('@/api/pharmacy', () => ({
  listPrescriptions: vi.fn(),
  listDispenses: vi.fn(),
  pickDispense: vi.fn(),
  verifyDispense: vi.fn(),
  issueDispense: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（拦截提示与发药确认断言用），其余导出原样保留供组件解析
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: { ...mod.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') },
  };
});

// jsdom 未实现 ResizeObserver：el-table 布局测量依赖，补空壳避免挂载即抛（测试环境无真实 resize）
if (!('ResizeObserver' in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

/** 按按钮文案点击 el-button（避免 DOM 结构序号耦合，patient 三页 spec 同款） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

/** 按按钮文案定位 el-button 包装（在途断言用；非点击入口） */
function findButton(wrapper: VueWrapper, text: string): DOMWrapper<Element> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

/**
 * 构造发药单（status/picker 由用例指定：status 驱动按钮启停，picker 驱动双签辅助启停面——
 * 后端留痕操作者标识为 userId，配药后 picker=调配人 userId，与测试态未登录（undefined）
 * 不等即可核对/发药）。
 */
function dispenseMock(status: string): DispenseVO {
  return {
    id: '100',
    dispenseNo: 'D1',
    dispenseType: 'OUTPATIENT',
    rxNo: 'RX-20260919-001',
    patientId: '1932000000000000002',
    visitId: 'V001',
    storehouse: 'MAIN',
    picker: status === 'CREATED' ? undefined : 'pharmacist-a',
    verifier: undefined,
    issuer: undefined,
    status,
    items: [
      {
        id: '10',
        prescriptionItemId: '5',
        itemCode: 'C001',
        requestedQuantity: '2',
        issuedQuantity: '0',
        returnedQuantity: '0',
        batchNo: 'B20260101',
        traceCodes: [],
        itemStatus: 'PENDING',
      },
    ],
  };
}

describe('发药工作台', () => {
  /** 文件级 Pinia：组件读取 auth 会话 store（双签辅助启停面），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #19）：真实 PHARMACIST 会话经登录契约导出含码，既有用例语义不变
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'test-token',
        refreshToken: 'test-refresh',
        user: {
          userId: 'u9',
          loginName: 'pharmacistdemo',
          displayName: '王药师',
          orgId: null,
          roles: ['pharmacist'],
          permissions: ['pharmacy:dispense:btn:issue'],
        },
      }),
    );
    vi.mocked(listPrescriptions).mockReset();
    vi.mocked(listDispenses).mockReset();
    vi.mocked(pickDispense).mockReset();
    vi.mocked(verifyDispense).mockReset();
    vi.mocked(issueDispense).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    // 队列兜底：待发/调剂中两段并查（loadQueue 契约），默认全空防未 stub 的 resolve 断链
    vi.mocked(listPrescriptions).mockImplementation(() =>
      Promise.resolve({ content: [], page: 0, size: 20, total: '0' }),
    );
  });

  /** 队列分段 stub：仅待发段返回一张处方（DISPENSING 段空），与 loadQueue 两段并查对齐 */
  function mockQueueWithPendingRx(): void {
    vi.mocked(listPrescriptions).mockImplementation((params) =>
      Promise.resolve(
        params.status === 'PENDING_DISPENSE'
          ? {
              content: [
                {
                  id: '1',
                  rxNo: 'RX-20260919-001',
                  patientId: '1932000000000000002',
                  visitId: 'V001',
                  status: 'PENDING_DISPENSE',
                },
              ],
              page: 0,
              size: 20,
              total: '1',
            }
          : { content: [], page: 0, size: 20, total: '0' },
      ),
    );
  }

  it('追溯码为空点配药被前置拦截不出网', async () => {
    // 队列一张处方 + CREATED 发药单：配药按钮启用，但逐码未录即须拦截
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('CREATED')]);
    const wrapper = mount(DispenseWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();

    // 选队列行 → 回显发药单
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('D1');

    // 逐码录入留空（无码不结）直接点配药
    await clickButton(wrapper, '配药');
    await flushPromises();

    // 断言业务结果：拦截不出网 + 前置提示
    expect(vi.mocked(pickDispense)).not.toHaveBeenCalled();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('配药→核对→发药依次出网且都以回传单号调用', async () => {
    mockQueueWithPendingRx();
    // 三段状态机：选单 CREATED → 配药后回显 PICKING → 核对后回显 PICKED
    vi.mocked(listDispenses)
      .mockResolvedValueOnce([dispenseMock('CREATED')])
      .mockResolvedValueOnce([dispenseMock('PICKING')])
      .mockResolvedValueOnce([dispenseMock('PICKED')]);
    vi.mocked(pickDispense).mockResolvedValue();
    vi.mocked(verifyDispense).mockResolvedValue();
    vi.mocked(issueDispense).mockResolvedValue();
    const wrapper = mount(DispenseWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();

    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    // 逐盒录入（逗号分隔两码）→ 配药锁定
    await wrapper.find('input[placeholder="逐盒扫码，逗号分隔"]').setValue('T1,T2');
    await clickButton(wrapper, '配药');
    await flushPromises();
    expect(vi.mocked(pickDispense)).toHaveBeenCalledWith('D1', {
      items: [{ prescriptionItemId: '5', traceCodes: ['T1', 'T2'] }],
    });

    // 核对（第二药师入口）→ 发药签名（确认弹框已替身放行）
    await clickButton(wrapper, '核对');
    await flushPromises();
    expect(vi.mocked(verifyDispense)).toHaveBeenCalledWith('D1');

    await clickButton(wrapper, '发药签名');
    await flushPromises();
    // 发药不可逆：终笔签名前必须经确认弹框（防误触面）。
    // D-21 断言现代化（2026-09-22 用户批复选项①破例）：由两参精确匹配升为三参全量精确匹配——
    // 钉死单号前置文案与中文按钮 options（R-3 缺口第三处闭合）；严格度上升，非回归弱化
    expect(vi.mocked(ElMessageBox.confirm)).toHaveBeenCalledWith(
      '发药单 D1 签名后药品出库且不可逆，确认发药？',
      '发药签名',
      { confirmButtonText: '确认发药', cancelButtonText: '取消' },
    );
    expect(vi.mocked(issueDispense)).toHaveBeenCalledWith('D1');

    // 出网顺序：配药 → 核对 → 发药签名（三段不可跳跃，调用序即证）
    expect(vi.mocked(pickDispense).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(verifyDispense).mock.invocationCallOrder[0],
    );
    expect(vi.mocked(verifyDispense).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(issueDispense).mock.invocationCallOrder[0],
    );
    wrapper.unmount();
  });

  it('核对请求在途：按钮禁用且二次点击零出网，结束后复位可再点（W-22⑥ 防抖）', async () => {
    mockQueueWithPendingRx();
    // 回显固定 PICKING 单（核对后重刷仍回同态），复位断言不被业务态翻转干扰
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('PICKING')]);
    // 慢响应：核对挂起至用例放行，稳定复现「请求在途」窗口（双击的第二个事件必落在窗口内）
    let releaseVerify: () => void = () => {};
    vi.mocked(verifyDispense).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releaseVerify = resolve;
        }),
    );
    const wrapper = mount(DispenseWorkbenchView, { global: { plugins: [pinia] } });
    await flushPromises();

    // 选队列行 → 回显发药单
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('D1');

    await clickButton(wrapper, '核对');
    await flushPromises();

    // 在途态：按钮原生 disabled 置位（:disabled 组合业务态与在途标志）
    expect(findButton(wrapper, '核对').attributes('disabled')).toBeDefined();
    expect(vi.mocked(verifyDispense)).toHaveBeenCalledTimes(1);

    // 在途窗口内二次点击：handler 入口守卫 + 组件 loading 双保险，零第二次出网
    await clickButton(wrapper, '核对');
    await flushPromises();
    expect(vi.mocked(verifyDispense)).toHaveBeenCalledTimes(1);

    // 在途结束后恢复可点（防抖标记须经 finally 复位，禁把按钮永久锁死）
    releaseVerify();
    await flushPromises();
    expect(findButton(wrapper, '核对').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DispenseWorkbenchView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 pharmacy:dispense:btn:issue 时配药/核对/发药签名全隐藏（D-34）', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('CREATED')]);
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 'u9' } }),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();
    // 发药单读面回显不受元素码影响，三写入口经 v-perm DOM 移除（队列「选择」为
    // 纯 UI 选单通道不挂码，仍可见）
    expect(wrapper.text()).toContain('D1');
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).not.toContain('配药');
    expect(buttonTexts).not.toContain('核对');
    expect(buttonTexts).not.toContain('发药签名');
    expect(buttonTexts).toContain('选择');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 pharmacy:dispense:btn:issue 时配药/核对/发药签名可见', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('CREATED')]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('配药');
    // 核对/发药签名按 CREATED 单数据态禁用但仍在 DOM（权限与数据态两层正交）
    expect(buttonTexts).toContain('核对');
    expect(buttonTexts).toContain('发药签名');
    wrapper.unmount();
  });

  it('门牌页首锚点：衬线标题「发药工作台」/ 队列计数胶囊（待发+调剂中合计）/ 签认人·时刻批注行（蓝图 P11.2）', async () => {
    // 队列分段 stub：待发 2 + 调剂中 1 → 门牌胶囊合计 3（两段并查基数即胶囊基数）
    vi.mocked(listPrescriptions).mockImplementation((params) =>
      Promise.resolve(
        params.status === 'PENDING_DISPENSE'
          ? {
              content: [
                {
                  id: '1',
                  rxNo: 'RX-20260919-001',
                  patientId: '1932000000000000002',
                  visitId: 'V001',
                  status: 'PENDING_DISPENSE',
                },
                {
                  id: '2',
                  rxNo: 'RX-20260919-002',
                  patientId: '1932000000000000003',
                  visitId: 'V002',
                  status: 'PENDING_DISPENSE',
                },
              ],
              page: 0,
              size: 20,
              total: '2',
            }
          : {
              content: [
                {
                  id: '3',
                  rxNo: 'RX-20260919-003',
                  patientId: '1932000000000000004',
                  visitId: 'V003',
                  status: 'DISPENSING',
                },
              ],
              page: 0,
              size: 20,
              total: '1',
            },
      ),
    );
    const wrapper = mountView();
    await flushPromises();

    // 门牌页首锚（契约 ⑧.1/蓝图 P11.2）：衬线标题承接页面名 + 批注行取会话真值
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('发药工作台');
    const note = wrapper.find('.fuy-page-note');
    expect(note.text()).toContain('签认人');
    expect(note.text()).toContain('王药师');
    expect(note.text()).toMatch(/20\d{2}-\d{2}-\d{2} 周[日一二三四五六]/);

    // 队列计数胶囊：待发+调剂中合计（2+1=3），数字走 .fuy-num 等宽通道（蓝图 P11.2）
    const pill = wrapper.find('.fuy-page-status .fuy-status-pill');
    expect(pill.exists()).toBe(true);
    expect(pill.text()).toContain('队列');
    expect(pill.find('.fuy-num').text()).toBe('3');
    wrapper.unmount();
  });

  it('队列双态合并出网：待发与调剂中两段并查（双签闭环数据源，蓝图 P11.5）', async () => {
    mockQueueWithPendingRx();
    const wrapper = mountView();
    await flushPromises();
    // loadQueue 契约：Promise.all 两段并查缺一不可——单查待发段会使第二药师
    // 无法从队列拉起在途单完成核对/发药签名
    expect(vi.mocked(listPrescriptions)).toHaveBeenCalledWith({ status: 'PENDING_DISPENSE' });
    expect(vi.mocked(listPrescriptions)).toHaveBeenCalledWith({ status: 'DISPENSING' });
    wrapper.unmount();
  });

  it('队列空态走 .fuy-empty 脸：暂无待发/调剂中处方并给下一步指引（契约 ⑥/蓝图总则 8）', async () => {
    // beforeEach 默认队列为空：空态脸直出
    const wrapper = mountView();
    await flushPromises();
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    // 主句合「暂无〈业务客体〉」语法，说明句给下一步（禁裸「暂无数据」）
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无待发/调剂中处方');
    expect(empty.find('.fuy-empty-hint').text()).not.toBe('');
    wrapper.unmount();
  });

  it('选处方无放行发药单：提示「该处方尚未放行生成发药单」且右栏不出发药单卡（蓝图 P11.7）', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('该处方尚未放行生成发药单');
    // 无单驻留：右栏不发药单卡（步点条随之不存在）
    expect(wrapper.find('.dispense-workbench-sheet').exists()).toBe(false);
    wrapper.unmount();
  });

  it('双签步点条四节点静态展示：PICKING 单高亮「核对」、已过步/未到步分级（蓝图 P11.2）', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('PICKING')]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    const steps = wrapper.findAll('.dispense-workbench-step');
    // 四节点固定序=状态机镜像（CREATED→PICKING→PICKED→ISSUED 对应拾取/核对/发药/完成），
    // 纯展示零启停逻辑
    expect(steps.map((s) => s.text())).toEqual(['拾取', '核对', '发药', '完成']);
    // PICKING 单：核对为当前步（高亮），拾取已过、发药/完成未到
    expect(steps[0].classes()).toContain('is-passed');
    expect(steps[1].classes()).toContain('is-current');
    expect(steps[2].classes()).toContain('is-upcoming');
    expect(steps[3].classes()).toContain('is-upcoming');
    wrapper.unmount();
  });

  it('步点条与动作钮按当前步视觉分级：CREATED 单高亮「拾取」且配药为主钮墨实底（蓝图 P11.2/7）', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('CREATED')]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    const steps = wrapper.findAll('.dispense-workbench-step');
    expect(steps[0].classes()).toContain('is-current');
    expect(steps[1].classes()).toContain('is-upcoming');

    // 动作钮视觉分级（蓝图 P11.7 断样式挂类不断启停逻辑）：当前步主钮挂 primary 墨实底脸，
    // 非当前步无主钮脸；启停（disabled）语义由既有用例承载，此处只断视觉挂类
    expect(findButton(wrapper, '配药').classes()).toContain('el-button--primary');
    expect(findButton(wrapper, '核对').classes()).not.toContain('el-button--primary');
    expect(findButton(wrapper, '发药签名').classes()).not.toContain('el-button--primary');
    wrapper.unmount();
  });

  it('视觉分级随当前步流转：PICKED 单高亮「发药」且发药签名为唯一主钮（蓝图 P11.2/7）', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('PICKED')]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    const steps = wrapper.findAll('.dispense-workbench-step');
    expect(steps[0].classes()).toContain('is-passed');
    expect(steps[1].classes()).toContain('is-passed');
    expect(steps[2].classes()).toContain('is-current');
    expect(steps[3].classes()).toContain('is-upcoming');

    expect(findButton(wrapper, '发药签名').classes()).toContain('el-button--primary');
    expect(findButton(wrapper, '配药').classes()).not.toContain('el-button--primary');
    expect(findButton(wrapper, '核对').classes()).not.toContain('el-button--primary');
    wrapper.unmount();
  });

  it('单据号码等宽全覆盖：卡头单号挂 .fuy-num，明细表项目/批次列挂 fuy-num（域级一致性）', async () => {
    mockQueueWithPendingRx();
    vi.mocked(listDispenses).mockResolvedValue([dispenseMock('CREATED')]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    // 卡头标题内的发药单号走等宽通道（fuy-num 只承 tabular-nums，不改衬线题族——
    // 字体三分不破：号码是数据面通道，衬线只承题面文字）
    const titleNum = wrapper.find('.fuy-card-title .fuy-num');
    expect(titleNum.exists()).toBe(true);
    expect(titleNum.text()).toBe('D1');
    // 明细表：项目（药品编码）与批次列同属号码族，单元格挂 fuy-num（td 由 class-name 落类）
    const rowCells = wrapper.findAll('.dispense-workbench-items .el-table__row td');
    const itemCell = rowCells.find((c) => c.text() === 'C001');
    const batchCell = rowCells.find((c) => c.text() === 'B20260101');
    expect(itemCell?.classes()).toContain('fuy-num');
    expect(batchCell?.classes()).toContain('fuy-num');
    wrapper.unmount();
  });

  it('双签辅助启停面：调配人=当前会话用户时核对/发药签名禁用并挂分权提示（PH-1011 前端面，蓝图 P11.7）', async () => {
    mockQueueWithPendingRx();
    // 配药人=会话 userId（u9）：PICKING 单业务态允许核对，但双签分权禁用（后端 PH-1011 硬拒兜底）
    const selfPicked: DispenseVO = { ...dispenseMock('PICKING'), picker: 'u9' };
    vi.mocked(listDispenses).mockResolvedValue([selfPicked]);
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('.el-table__row').trigger('click');
    await flushPromises();

    const verifyButton = findButton(wrapper, '核对');
    expect(verifyButton.attributes('disabled')).toBeDefined();
    expect(verifyButton.attributes('title')).toBe('调配人不可自行核对/发药');
    const issueButton = findButton(wrapper, '发药签名');
    expect(issueButton.attributes('disabled')).toBeDefined();
    expect(issueButton.attributes('title')).toBe('调配人不可自行核对/发药');
    wrapper.unmount();
  });
});
