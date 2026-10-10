// 药品字典页单测（FU-M06-01/02 前端面 · 暖纸卷宗重排批次 P10）：业务行为断言全量保留
// （未对照药品渲染「不可医保结算」标记、对照表单三字段提交以行 id 调 mapInsurance（雪花 ID
// string 原样入网禁 number 处理）、对照提交在途防抖 W-22⑥、EX-46/FE-A2-09 变更整单覆盖
// 二次确认、PR-4F 双码权限态），并按逐页蓝图 P10 spec 锚点新增构图断言：门牌页首（衬线标题
// +签认人·时刻批注行）、筛选与建档主钮分主次（主钮右挂 .fuy-filter-actions 墨实底）、列序
// 重排（通用名首列/药码次之）、usePagedList 分页化收口（50/页+翻页 0 基转换+检索回第一页）、
// 两弹窗挂 .fuy-dialog+.fuy-form、显式 popper 挂 fuy-snap-popper、空态 .fuy-empty 脸化。
// api mock 承载，不打真实网络。
// 桩面：覆盖确认走 ElMessageBox.confirm 替身（默认确认放行，用例按需 mockRejectedValueOnce 覆写）。
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper, VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { createDrug, mapInsurance, searchDrugs, updateDrug } from '@/api/pharmacy';
import type { DrugVO } from '@/api/pharmacy';
import { permDirective } from '@/directives/perm';
import DrugDictView from './DrugDictView.vue';

vi.mock('@/api/pharmacy', () => ({
  searchDrugs: vi.fn(),
  createDrug: vi.fn(),
  updateDrug: vi.fn(),
  mapInsurance: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（提示与覆盖确认断言用），其余导出原样保留供组件解析
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

/** 构造药品行（insuredSettleable 由用例指定驱动标记断言） */
function drugRow(overrides: Partial<DrugVO>): DrugVO {
  return {
    id: '7',
    drugCode: 'D001',
    genericName: '阿莫西林胶囊',
    tradeName: undefined,
    dosageForm: undefined,
    specification: '0.25g*24粒',
    manufacturer: '华北制药',
    routeCodes: undefined,
    unit: '盒',
    splitRatio: undefined,
    nhsaCode: undefined,
    nhsaCatalogVersion: undefined,
    nhsaPayType: undefined,
    essentialFlag: false,
    antibioClass: 'NONE',
    hazardLevel: 'NONE',
    skinTestFlag: false,
    narcoticClass: 'NONE',
    itemCode: 'C901',
    traceCodeType: undefined,
    indication: undefined,
    maxDose: undefined,
    contraindication: undefined,
    storageCondition: undefined,
    insuredSettleable: false,
    status: 'ACTIVE',
    ...overrides,
  };
}

describe('药品字典页', () => {
  /** 文件级 Pinia：v-perm 指令读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 会话种子（PR-4F #22/#23）：真实 PHARMACIST 会话经登录契约导出含建档/变更码；
    // #23 医保对照为 ADMIN 专属码（V1121 零绑定行），PHARMACIST 会话实况不含——但本页
    // 既有用例覆盖「医保对照」链路（对照弹窗提交/在途），按既有形态播种含码保语义不变
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
          permissions: ['pharmacy:drug:btn:maintain', 'pharmacy:drug:btn:insurance-mapping'],
        },
      }),
    );
    vi.mocked(searchDrugs).mockReset();
    vi.mocked(createDrug).mockReset();
    vi.mocked(updateDrug).mockReset();
    vi.mocked(mapInsurance).mockReset();
    vi.mocked(ElMessage.warning).mockClear();
    // 确认框替身调用记录清零（实现默认确认放行，单用例按需 mockRejectedValueOnce 覆写）
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 挂载即检索：默认空页兜底，防未 stub 的 resolve 断链
    vi.mocked(searchDrugs).mockResolvedValue({ content: [], page: 0, size: 20, total: '0' });
    vi.mocked(mapInsurance).mockResolvedValue();
  });

  it('未对照药品渲染不可医保结算标记', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);

    await vi.waitFor(() => {
      // 业务断言：insuredSettleable=false 行渲染警示标记（对照态可视面）
      expect(wrapper.text()).toContain('不可医保结算');
    });
    wrapper.unmount();
  });

  it('对照表单提交以行 id 调 mapInsurance', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 打开对照弹窗（el-dialog 缺省不 teleport，弹窗 DOM 留在 wrapper 内）
    await clickButton(wrapper, '医保对照');
    await flushPromises();

    await wrapper.find('input[placeholder="国家医保药品编码"]').setValue('XN01AX0900014305116');
    await wrapper.find('input[placeholder="如 2024A"]').setValue('2024A');
    await wrapper.find('input[placeholder="如 甲类/乙类"]').setValue('甲类');
    await clickButton(wrapper, '确认对照');
    await flushPromises();

    // 出网载荷：行 id string 原样入路径 + 三字段原样（对照后 insuredSettleable 翻转由后端承载）
    expect(vi.mocked(mapInsurance)).toHaveBeenCalledWith('7', {
      nhsaCode: 'XN01AX0900014305116',
      catalogVersion: '2024A',
      payType: '甲类',
    });
    wrapper.unmount();
  });

  it('医保对照提交在途：按钮禁用且二次点击零出网，结束后复位可再点（W-22⑥ 防抖）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    // 慢响应：对照挂起至用例放行，稳定复现「请求在途」窗口（双击的第二个事件必落在窗口内）
    let releaseMapping: () => void = () => {};
    vi.mocked(mapInsurance).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          releaseMapping = resolve;
        }),
    );
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 打开对照弹窗并录入三字段 → 首击提交（在途窗开启）
    await clickButton(wrapper, '医保对照');
    await flushPromises();
    await wrapper.find('input[placeholder="国家医保药品编码"]').setValue('XN01AX0900014305116');
    await wrapper.find('input[placeholder="如 2024A"]').setValue('2024A');
    await wrapper.find('input[placeholder="如 甲类/乙类"]').setValue('甲类');
    await clickButton(wrapper, '确认对照');
    await flushPromises();

    // 在途态：按钮原生 disabled 置位（:loading 同挂 mappingSubmitting）
    expect(findButton(wrapper, '确认对照').attributes('disabled')).toBeDefined();
    expect(vi.mocked(mapInsurance)).toHaveBeenCalledTimes(1);

    // 在途窗口内二次点击：handler 入口守卫 + 组件 loading 双保险，零第二次出网
    await clickButton(wrapper, '确认对照');
    await flushPromises();
    expect(vi.mocked(mapInsurance)).toHaveBeenCalledTimes(1);

    // 在途结束后恢复可点（防抖标记须经 finally 复位，禁把按钮永久锁死）
    releaseMapping();
    await flushPromises();
    expect(findButton(wrapper, '确认对照').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  it('药品变更提交前整单覆盖二次确认：取消零出网，确认后出网（EX-46/FE-A2-09）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    vi.mocked(updateDrug).mockResolvedValue(drugRow({}));
    const wrapper = mount(DrugDictView);
    await flushPromises();
    // 打开变更弹窗（管控属性等 11 字段已按行预填，必填校验可直接通过）
    await clickButton(wrapper, '变更');
    await flushPromises();
    // 先取消覆盖确认 → 零出网，弹窗与录入驻留
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');
    await clickButton(wrapper, '确认变更');
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('覆盖'),
      '变更覆盖确认',
      expect.anything(),
    );
    expect(vi.mocked(updateDrug)).not.toHaveBeenCalled();
    expect(wrapper.find('input[placeholder="院内药品编码"]').isVisible()).toBe(true);
    // 再确认 → 整单变更出网（管控属性随表单回写由后端承载）
    await clickButton(wrapper, '确认变更');
    await flushPromises();
    expect(vi.mocked(updateDrug)).toHaveBeenCalledWith(
      '7',
      expect.objectContaining({ drugCode: 'D001', genericName: '阿莫西林胶囊' }),
    );
    wrapper.unmount();
  });

  it('药品建档不经覆盖确认直接出网（EX-46/FE-A2-09 范围界定：仅变更路径确认）', async () => {
    vi.mocked(createDrug).mockResolvedValue(drugRow({ drugCode: 'D002' }));
    const wrapper = mount(DrugDictView);
    await flushPromises();
    await clickButton(wrapper, '药品建档');
    await flushPromises();
    // 建档必填五项（药码/通用名/抗菌药分级/危险级/麻精分级）
    await wrapper.find('input[placeholder="院内药品编码"]').setValue('D002');
    await wrapper.find('input[placeholder="通用名"]').setValue('布洛芬片');
    await wrapper.find('input[placeholder="非抗菌填 NONE"]').setValue('NONE');
    await wrapper.find('input[placeholder="高危药分级，普通填 NONE"]').setValue('NONE');
    await wrapper.find('input[placeholder="非麻精填 NONE"]').setValue('NONE');
    await clickButton(wrapper, '确认建档');
    await flushPromises();
    // 新建无覆盖面：零确认直接出网
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(vi.mocked(createDrug)).toHaveBeenCalledWith(
      expect.objectContaining({ drugCode: 'D002', genericName: '布洛芬片' }),
    );
    wrapper.unmount();
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(DrugDictView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：PHARMACIST 会话（无 #23）医保对照隐藏而建档/变更可见（ADMIN 专属码）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    // 业务角色会话：仅含 #22 建档/变更码，无 #23 医保对照码（V1121 该码 ADMIN 专属零绑定行）
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
          permissions: ['pharmacy:drug:btn:maintain'],
        },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    // 医保对照（#23）隐藏；药品建档/变更（#22）可见——双码互不干扰
    expect(buttonTexts).not.toContain('医保对照');
    expect(buttonTexts).toContain('药品建档');
    expect(buttonTexts).toContain('变更');
    wrapper.unmount();
  });

  it('PR-4F 权限态：ADMIN 会话经全表导出含 #23 时医保对照可见', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    // ADMIN 会话经权限全表导出含码（运行期全放自动可见）：beforeEach 种子已含 #23
    const wrapper = mountView();
    await flushPromises();
    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts).toContain('医保对照');
    expect(buttonTexts).toContain('药品建档');
    expect(buttonTexts).toContain('变更');
    wrapper.unmount();
  });

  /* ==================== 逐页蓝图 P10 新增锚点（门牌/主次钮/列序/分页/挂类/空态） ==================== */

  it('门牌页首承载衬线标题与签认人·时刻批注行（会话真值非伪数据）', () => {
    const wrapper = mount(DrugDictView);

    // 门牌页首锚（蓝图 P10.7/契约 ⑧.1）：原 el-card #header「药品字典」升格衬线主标题 +
    // 「谁·何时」批注行取会话真值（空值 — 占位），2px 墨规收底归全局 .fuy-page-head 族
    expect(wrapper.find('.fuy-page').exists()).toBe(true);
    expect(wrapper.find('header.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('h1.fuy-page-title').text()).toBe('药品字典');
    const note = wrapper.find('.fuy-page-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('签认人');
    expect(note.text()).toContain('王药师');
    expect(note.find('time').exists()).toBe(true);
    wrapper.unmount();
  });

  it('筛选与建档主钮分主次：筛选区 .fuy-filter + 建档墨实底主钮右挂 .fuy-filter-actions', () => {
    const wrapper = mount(DrugDictView);

    // 构图锚（蓝图 P10.2）：筛选卡独立语义位（.fuy-filter）+ 主表卡 .fuy-card.fuy-dense；
    // 检索钮留在筛选控件序列内（次钮），药品建档主钮（EP primary=墨实底自动承继）右挂
    // .fuy-filter-actions（margin-left:auto 主次分区），且 v-perm 码面不变（既有口径）
    expect(wrapper.find('.fuy-filter').exists()).toBe(true);
    expect(wrapper.find('.fuy-filter').text()).toContain('关键词');
    const actions = wrapper.find('.fuy-filter .fuy-filter-actions');
    expect(actions.exists()).toBe(true);
    const createBtn = actions.findAll('button').find((b) => b.text() === '药品建档');
    expect(createBtn).toBeDefined();
    expect(createBtn?.classes()).toContain('el-button--primary');
    // 检索次钮不在主钮挂载区（分主次的版式表达）
    const searchInActions = actions.findAll('button').find((b) => b.text() === '检索');
    expect(searchInActions).toBeUndefined();
    expect(wrapper.findAll('button').map((b) => b.text())).toContain('检索');
    // 主表卡：fuy-dense 必须挂卡容器（密度规则为后代选择器 .fuy-dense .el-table）
    expect(wrapper.find('section.fuy-card.fuy-dense .el-table').exists()).toBe(true);
    wrapper.unmount();
  });

  it('列序按扫读频率重排：通用名首列、药码次之（蓝图 P10.2）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 列序锚：表头前两列=通用名 → 药码（重排前为药码 → 通用名），空表头格（固定列垫）不计
    const headerCells = wrapper
      .findAll('.el-table__header-wrapper th .cell')
      .map((c) => c.text().trim())
      .filter((t) => t !== '');
    expect(headerCells[0]).toBe('通用名');
    expect(headerCells[1]).toBe('药码');
    expect(headerCells.indexOf('通用名')).toBeLessThan(headerCells.indexOf('药码'));
    // 数据行同序：首格=通用名值、次格=药码值（药码主号列挂 .fuy-num 等宽，契约 ⑧.4/域级一致性）
    const firstRowCells = wrapper.findAll('.el-table__body-wrapper .el-table__row td');
    expect(firstRowCells[0].text()).toBe('阿莫西林胶囊');
    expect(firstRowCells[1].text()).toBe('D001');
    expect(firstRowCells[1].classes()).toContain('fuy-num');
    wrapper.unmount();
  });

  it('usePagedList 分页化收口：50/页出网、翻页 0 基转换、检索回第一页（蓝图 P10.6）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 挂载即检索（既有口径）：usePagedList 收口后出网带 0 基 page 与 size 50（契约可选参既有，
    // 替换重排前「不传 page/size 后端兜底」的全量渲染隐患）
    expect(vi.mocked(searchDrugs)).toHaveBeenCalledWith({
      keyword: undefined,
      essential: undefined,
      insuranceMapped: undefined,
      page: 0,
      size: 50,
    });
    // 有结果才出分页（total>0 门控），翻页回传 1 基页码 → 契约 0 基 page=1 出网
    // （按组件名取包装器：翻页经 current-change 事件驱动，断言视图侧接线而非 EP 内部点击换算）
    const pagination = wrapper.getComponent({ name: 'ElPagination' });
    pagination.vm.$emit('current-change', 2);
    await flushPromises();
    expect(vi.mocked(searchDrugs)).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 50 }),
    );
    // 换条件点检索：回第一页（usePagedList search 出口）再出网
    await clickButton(wrapper, '检索');
    await flushPromises();
    expect(vi.mocked(searchDrugs)).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, size: 50 }),
    );
    wrapper.unmount();
  });

  it('筛选三参出网：关键词经检索/回车同出口原样合并分页参数（三参契约保留）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    await wrapper.find('input[placeholder="名称/拼音/医保码"]').setValue('阿莫');
    await clickButton(wrapper, '检索');
    await flushPromises();
    // 三参（keyword/essential/insuranceMapped）原样出网 + 分页参数合并（trim 归一既有口径）
    expect(vi.mocked(searchDrugs)).toHaveBeenLastCalledWith({
      keyword: '阿莫',
      essential: undefined,
      insuranceMapped: undefined,
      page: 0,
      size: 50,
    });
    // 回车与检索钮同走 handleSearch 出口（既有联动口径保留）
    await wrapper.find('input[placeholder="名称/拼音/医保码"]').setValue('025g');
    await wrapper.find('input[placeholder="名称/拼音/医保码"]').trigger('keyup.enter');
    await flushPromises();
    expect(vi.mocked(searchDrugs)).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '025g', page: 0, size: 50 }),
    );
    wrapper.unmount();
  });

  it('建档/变更与医保对照两弹窗挂 .fuy-dialog+.fuy-form 弹层脸（契约 ⑤#9/⑫.4-③）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({})],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 先开建档弹窗再开对照弹窗（两弹层各自独立挂类，同时在场便于一次断言）
    await clickButton(wrapper, '药品建档');
    await flushPromises();
    await clickButton(wrapper, '医保对照');
    await flushPromises();
    const dialogs = wrapper.findAll('.fuy-dialog');
    expect(dialogs.length).toBe(2);
    // 表单整脸挂 .fuy-form（label 疏排/聚焦墨环/错误显影经全局挂类承载）
    expect(wrapper.findAll('.fuy-dialog .fuy-form').length).toBe(2);
    // 两弹窗标题位（建档/变更随 editId 切换的既有语义保留）
    expect(wrapper.text()).toContain('药品建档');
    expect(wrapper.text()).toContain('医保编码对照');
    wrapper.unmount();
  });

  it('显式 popper 统一挂 fuy-snap-popper（基药/医保对照两下拉硬 snap 门禁）', () => {
    const wrapper = mount(DrugDictView);

    // 契约总则 4/⑦.5：一切显式 popper 挂 fuy-snap-popper——本页仅筛选区两下拉
    const selectPopperClasses = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .map((node) => node.props('popperClass'));
    expect(selectPopperClasses).toHaveLength(2);
    for (const popperClass of selectPopperClasses) {
      expect(popperClass).toBe('fuy-snap-popper');
    }
    wrapper.unmount();
  });

  it('药码族号码列等宽全覆盖：医保编码列与药码列同挂 fuy-num（域级「药品主号一律等宽」）', async () => {
    vi.mocked(searchDrugs).mockResolvedValue({
      content: [drugRow({ nhsaCode: 'XN01AX0900014305116', insuredSettleable: true })],
      page: 0,
      size: 20,
      total: '1',
    });
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 域级一致性（蓝图药房域附加节「药品/单据主号一律 .fuy-num」）：医保编码同为药品编码族
    // 号码列，与药码列同挂等宽通道（tabular-nums），防编码列宽度跳动
    const cells = wrapper.findAll('.el-table__body-wrapper .el-table__row td');
    const nhsaCell = cells.find((c) => c.text() === 'XN01AX0900014305116');
    expect(nhsaCell).toBeDefined();
    expect(nhsaCell?.classes()).toContain('fuy-num');
    wrapper.unmount();
  });

  it('查无结果空态走 .fuy-empty 脸（禁纸箱插画）且零结果不出分页', async () => {
    const wrapper = mount(DrugDictView);
    await flushPromises();

    // 空态脸锚（契约 ⑥/蓝图 P10.2）：el-empty 默认纸箱插画不渲染；主句合「暂无〈业务客体〉」
    // 语法 + 说明给下一步（挂载即检索，初始与查无结果同语义，无需 searched 门控——既有口径）
    expect(wrapper.find('.el-empty').exists()).toBe(false);
    const empty = wrapper.find('.fuy-empty');
    expect(empty.exists()).toBe(true);
    expect(empty.attributes('role')).toBe('status');
    expect(empty.find('.fuy-empty-title').text()).toBe('暂无匹配药品');
    expect(empty.find('.fuy-empty-hint').text()).toContain('重新检索');
    // total=0 不出分页条（usePagedList total 门控既有）
    expect(wrapper.find('.el-pagination').exists()).toBe(false);
    wrapper.unmount();
  });
});
