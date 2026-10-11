// 入院登记台单测（/inpatient/admission，M04 FU-M04-01 前端面 · 暖纸卷宗换脸重排 乙-1）：
// 门牌页首（衬线标题/英文小注/排序批注行）、登记确认卡升右栏首位（锚点① DOM 区序）、
// 状态签 .fuy-tabs 四档（锚点②，radio-button 卷宗签化）、钤印「已登记 · visit ××××」/
// 「已预约」（锚点③，成功反馈换约——ElMessage 让位钤印）、预约弹窗病区下拉 listOrgs 真数据
// 三态（锚点④，WARD_OPTIONS 假常量清零）、脱敏口径（锚点⑤患者编号展示无姓名字段）、
// FROZEN 患者 IP-1003 ProblemDetail detail 原文透出（4xx 口径）、未选医保零出网显式校验、
// 创建住院证出网、作废中档确认文案冻结、PR-4F 权限三件。
// api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身 + @/api/patient + @/api/system
// 替身），不打真实网络；断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { admissions, beds } from '@/api/inpatient';
import type { AdmissionVO, BedMapVO } from '@/api/inpatient';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
import { listOrgs } from '@/api/system';
import type { OrgVO } from '@/api/system';
import { permDirective } from '@/directives/perm';
import AdmissionView from './AdmissionView.vue';

vi.mock('@/api/inpatient', () => ({
  ADMISSION_STATUS_OPTIONS: [
    { code: 'WAITING', label: '候床中' },
    { code: 'SCHEDULED', label: '已预约' },
  ],
  ADMISSION_TYPE_OPTIONS: [
    { code: 'NORMAL', label: '普通入院' },
    { code: 'EMERGENCY', label: '急诊入院' },
    { code: 'PRE_HOSPITAL', label: '预住院' },
  ],
  SOURCE_TYPE_OPTIONS: [
    { code: 'OUTPATIENT', label: '门诊转诊' },
    { code: 'EMERGENCY', label: '急诊' },
    { code: 'PEIS', label: '体检' },
    { code: 'OTHER', label: '其他' },
  ],
  INSURANCE_TYPE_OPTIONS: [
    { code: '职工医保', label: '职工医保' },
    { code: '居民医保', label: '居民医保' },
    { code: '自费', label: '自费' },
    { code: '商业保险', label: '商业保险' },
  ],
  admissions: {
    create: vi.fn(),
    list: vi.fn(),
    schedule: vi.fn(),
    cancel: vi.fn(),
    register: vi.fn(),
  },
  beds: {
    map: vi.fn(),
    reserve: vi.fn(),
    assign: vi.fn(),
    release: vi.fn(),
    disinfectDone: vi.fn(),
    maintain: vi.fn(),
    maintainDone: vi.fn(),
  },
}));

vi.mock('@/api/patient', () => ({
  matchCheck: vi.fn(),
  createPatient: vi.fn(),
  searchPatients: vi.fn(),
  getPatient: vi.fn(),
  changeFreeze: vi.fn(),
}));

// 病区清单真数据源替身（G4：WARD_OPTIONS 假常量清零，预约弹窗目标病区走 listOrgs WARD 档）
vi.mock('@/api/system', () => ({
  listOrgs: vi.fn(),
}));

// 仅替身 ElMessage/ElMessageBox（提示与确认断言用），其余导出原样保留供组件解析
// （本组件不使用 prompt，不替身 prompt——不留死种子）
vi.mock('element-plus', async (importOriginal) => {
  const mod = await importOriginal<typeof import('element-plus')>();
  return {
    ...mod,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
    ElMessageBox: {
      ...mod.ElMessageBox,
      confirm: vi.fn().mockResolvedValue('confirm'),
    },
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

/** 病区清单种子（listOrgs WARD 档出参；label=「名称（编码）」渲染口径的载体） */
const WARD_SEED: OrgVO[] = [
  { id: '1', orgCode: 'W01', orgName: '演示病区', orgType: 'WARD', sort: 1 },
];

/** 候床队列行（住院证；状态/类型可覆写） */
function admissionMock(partial: Partial<AdmissionVO> = {}): AdmissionVO {
  return {
    admissionNo: 'AD20260925001',
    patientId: '1932000000000000001',
    sourceType: 'OUTPATIENT',
    sourceVisitId: 'O20260925000000001',
    targetDeptId: undefined,
    targetWardId: undefined,
    targetBedId: undefined,
    admissionType: 'NORMAL',
    expectDate: '2026-09-26',
    diagnosisSummary: '急性阑尾炎，拟手术治疗',
    issuedDoctorId: 'D001',
    status: 'WAITING',
    createdAt: '2026-09-25T08:00:00+08:00',
    ...partial,
  };
}

/** 床位行（预约弹窗空床选择数据源；状态可覆写） */
function bedMock(partial: Partial<BedMapVO> = {}): BedMapVO {
  return {
    bedId: '901',
    wardId: 'W01',
    bedNo: '01',
    bedAttr: 'NORMAL',
    bedStatus: 'FREE',
    allowGender: 'ALL',
    occupiedVisit: undefined,
    ...partial,
  };
}

/** 患者检索行（脱敏档案，患者搜索选中载体） */
function patientMock(partial: Partial<PatientVO> = {}): PatientVO {
  return {
    patientId: '1932000000000000001',
    name: '张*',
    gender: '男',
    age: 35,
    idCardNo: '110101********0011',
    mobile: '138********01',
    ...partial,
  } as PatientVO;
}

/** 空候床分页出参（page/size/total 由后端 long→string 全局口径，web A.3-6 同源） */
function emptyQueue() {
  return { content: [], page: '0', size: '50', total: '0' };
}

/** 单行候床分页出参 */
function oneRowQueue(row: AdmissionVO) {
  return { content: [row], page: '0', size: '50', total: '1' };
}

/** 登记确认成功签发的 visit 出参（I 型就诊号，钤印回显载体） */
function visitMock() {
  return {
    visitId: 'I20260925000000001',
    admissionId: '9001',
    patientId: '1932000000000000001',
    currentDeptId: undefined,
    currentWardId: undefined,
    currentBedId: undefined,
    attendingDoctorId: undefined,
    nursingLevel: undefined,
    insuranceType: '职工医保',
    admissionDiagnosis: undefined,
    registeredAt: '2026-09-25T09:00:00+08:00',
    admittedAt: undefined,
    dischargeRequestedAt: undefined,
    dischargedAt: undefined,
    dischargeWay: undefined,
    arrearsFlag: false,
    status: 'REGISTERED',
  };
}

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('入院登记台', () => {
  /** 文件级 Pinia：v-perm/hasPerm 读取 auth 会话 store（元素权限判定），每用例新实例防串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    for (const fn of [
      admissions.create,
      admissions.list,
      admissions.schedule,
      admissions.cancel,
      admissions.register,
      beds.map,
      searchPatients,
      listOrgs,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链；病区清单真数据源默认落种子两态可复现
    vi.mocked(admissions.list).mockResolvedValue(emptyQueue());
    vi.mocked(beds.map).mockResolvedValue([]);
    vi.mocked(searchPatients).mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total: 0,
    });
    vi.mocked(listOrgs).mockResolvedValue(WARD_SEED);
    // PR-4F #13/#14 后建单/入区按钮挂元素权限：激活 pinia 并播种含码会话，
    // 既有用例语义不变（预约/作废行内与运算按权限正常渲染，断言零改动）
    pinia = createPinia();
    setActivePinia(pinia);
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: {
          userId: 1,
          permissions: ['inpatient:admission:btn:create', 'inpatient:admission:btn:register'],
        },
      }),
    );
  });

  it('门牌页首与候床队列加载渲染并透出急诊优先标识（后端冻结排序，前端只读标识）', async () => {
    vi.mocked(admissions.list).mockResolvedValue({
      content: [
        admissionMock({ admissionNo: 'AD20260925002', admissionType: 'EMERGENCY' }),
        admissionMock({ admissionNo: 'AD20260925001' }),
      ],
      page: '0',
      size: '50',
      total: '2',
    });
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // 门牌页首三锚：衬线标题 / 英文小注（装饰层）/ 排序口径升批注行（原 hint 文案逐字保留）
    expect(wrapper.find('.fuy-page-title').text()).toBe('入院登记台');
    expect(wrapper.find('.fuy-page-en').text()).toBe('ADMISSION DESK');
    const text = wrapper.text();
    expect(text).toContain('急诊优先 ＞ 预约时段 ＞ 候床时长');
    expect(text).toContain('AD20260925002');
    expect(text).toContain('AD20260925001');
    // 急诊入院行透出「急诊优先」排序标识（普通入院行无；类契约机器判据，印泥朱实底小徽标）
    expect(wrapper.findAll('.admission-emergency-flag').length).toBe(1);
    // 候床队列计数可见
    expect(text).toContain('共 2 条');
    expect(admissions.list).toHaveBeenCalledWith({ status: undefined, page: 0, size: 50 });
  });

  it('锚点①登记确认卡区序断言：确认卡 DOM 序先于住院证建证卡（PM 重排：动线=点登记→右侧确认顶置）', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock()));
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // G1 页级骨架：.fuy-page 根 + 门牌页首 + 页脚批注齐备
    expect(wrapper.find('.fuy-page.admission-view').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-head').exists()).toBe(true);
    expect(wrapper.find('.fuy-page-foot').exists()).toBe(true);
    // 区序机器证据：确认卡在建证卡之前（compareDocumentPosition FOLLOWING 位）
    const confirmCard = wrapper.find('.admission-confirm-card');
    const formCard = wrapper.find('.admission-form-card');
    expect(confirmCard.exists()).toBe(true);
    expect(formCard.exists()).toBe(true);
    expect(
      confirmCard.element.compareDocumentPosition(formCard.element) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('锚点②状态签 .fuy-tabs 挂类与四签文案：切「候床中」签按 WAITING 出网重载', async () => {
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // 卷宗签语法（⑤#8）：挂 .fuy-tabs，四档文案=全部/候床中/已预约/已入院
    const tabs = wrapper.findAll('.admission-status-tabs .el-tabs__item');
    expect(tabs.map((tab) => tab.text())).toEqual(['全部', '候床中', '已预约', '已入院']);
    const waitingTab = tabs.find((tab) => tab.text() === '候床中');
    if (!waitingTab) {
      throw new Error('未找到候床中状态签');
    }
    await waitingTab.trigger('click');
    await flushPromises();
    expect(admissions.list).toHaveBeenLastCalledWith({ status: 'WAITING', page: 0, size: 50 });
  });

  it('空队列给诚实空态脸（G6：暂无候床住院证+下一步指引，非「暂无数据」裸词）', async () => {
    const wrapper = mount(AdmissionView);
    await flushPromises();
    expect(wrapper.find('.fuy-empty').exists()).toBe(true);
    expect(wrapper.find('.fuy-empty-title').text()).toBe('暂无候床住院证');
    expect(wrapper.find('.fuy-empty-hint').text()).not.toBe('');
  });

  it('锚点⑤脱敏口径：队列仅展示患者编号（脱敏列头），零姓名字段渲染', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock()));
    const wrapper = mount(AdmissionView);
    await flushPromises();
    const text = wrapper.text();
    // 脱敏列头与患者编号展示（后端 VO 契约即不含姓名，前端零补齐）
    expect(text).toContain('患者编号（脱敏）');
    expect(text).toContain('1932000000000000001');
    // 全页零姓名列头（placeholder 为属性不进 textContent，不影响判定）
    const headers = wrapper.findAll('th').map((th) => th.text());
    expect(headers).not.toContain('姓名');
  });

  it('预约动作出网携预约时段与目标病区并刷新候床队列，成功盖钤印「已预约」（成功反馈换约：钤印代 message）', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock({ status: 'WAITING' })));
    vi.mocked(beds.map).mockResolvedValue([bedMock({ bedId: '902', bedNo: '02' })]);
    vi.mocked(admissions.schedule).mockResolvedValue(
      admissionMock({ status: 'SCHEDULED', targetWardId: 'W01', targetBedId: '902' }),
    );
    const wrapper = mount(AdmissionView);
    await flushPromises();
    await clickButton(wrapper, '预约');
    await flushPromises();
    // 预约弹窗打开后填写预约时段（native date input，yyyy-MM-dd 与 LocalDate 契约一致；
    // aria-label 精确定位弹窗字段，避开创建表单同型日期框）
    const dateInput = wrapper.find('input[aria-label="预约入院日期"]');
    await dateInput.setValue('2026-09-28');
    await clickButton(wrapper, '确认预约');
    await flushPromises();
    expect(admissions.schedule).toHaveBeenCalledWith(
      'AD20260925001',
      expect.objectContaining({ targetWardId: 'W01', expectDate: '2026-09-28' }),
    );
    // 成功反馈=钤印（G2/附一#6）：文案=动词结果，不再走 ElMessage
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toBe('已预约');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 预约成功后队列重载（list 第二次调用）
    expect(admissions.list).toHaveBeenCalledTimes(2);
  });

  it('锚点④预约弹窗病区真数据三态（装载）：listOrgs({type:"WARD"}) 在途禁用，到达默认落首项并联动空床', async () => {
    let releaseWards: () => void = () => {};
    vi.mocked(listOrgs).mockImplementation(
      () =>
        new Promise<OrgVO[]>((resolve) => {
          releaseWards = () => resolve(WARD_SEED);
        }),
    );
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock({ status: 'WAITING' })));
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // 页面装配即拉真数据病区清单（假常量清零后唯一真数据源，册级总则 G4）
    expect(vi.mocked(listOrgs)).toHaveBeenCalledWith({ type: 'WARD' });
    await clickButton(wrapper, '预约');
    await flushPromises();
    // 在途：目标病区下拉禁用（出网值必须锚定真实病区编码），床位联动零出网
    const wardSelect = wrapper.find('select[aria-label="目标病区"]');
    expect(wardSelect.attributes('disabled')).toBeDefined();
    expect(beds.map).not.toHaveBeenCalled();
    // 清单到达：下拉解禁、选项=「名称（编码）」、默认落首项并联动空床清单
    releaseWards();
    await flushPromises();
    expect(wardSelect.attributes('disabled')).toBeUndefined();
    const options = wardSelect.findAll('option');
    expect(options.map((option) => option.text())).toEqual(['演示病区（W01）']);
    expect(beds.map).toHaveBeenCalledWith('W01');
    wrapper.unmount();
  });

  it('锚点④预约弹窗病区真数据三态（失败恢复）：清单失败给「说问题+给恢复」，重试后默认落首项并联动空床', async () => {
    vi.mocked(listOrgs).mockRejectedValueOnce(new Error('网络不可用'));
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock({ status: 'WAITING' })));
    const wrapper = mount(AdmissionView);
    await flushPromises();
    await clickButton(wrapper, '预约');
    await flushPromises();
    // 失败态=说问题给恢复（G4/G6：不伪装空选项），病区下拉禁用、床位联动零出网
    expect(wrapper.text()).toContain('病区清单加载失败');
    const wardSelect = wrapper.find('select[aria-label="目标病区"]');
    expect(wardSelect.attributes('disabled')).toBeDefined();
    expect(beds.map).not.toHaveBeenCalled();
    // 重试恢复：清单到达后默认落首项并联动空床
    await clickButton(wrapper, '重试');
    await flushPromises();
    expect(wrapper.text()).not.toContain('病区清单加载失败');
    expect(beds.map).toHaveBeenCalledWith('W01');
    wrapper.unmount();
  });

  it('锚点③登记确认成功盖钤印「已登记 · visit ××××」（visit 号回显）并刷新候床队列', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock()));
    vi.mocked(admissions.register).mockResolvedValue(visitMock());
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // 行内「登记」选中候床证 → 右栏登记确认（确认卡显影后医保类型可选）
    await clickButton(wrapper, '登记');
    await wrapper.find('select[aria-label="医保类型"]').setValue('职工医保');
    await clickButton(wrapper, '登记确认');
    await flushPromises();
    expect(admissions.register).toHaveBeenCalledWith('AD20260925001', {
      insuranceType: '职工医保',
    });
    // 成功反馈=钤印（G2/附一#6）：含 visit 号回显，不再走 ElMessage
    const stamp = wrapper.find('.fuy-stamp-toast');
    expect(stamp.exists()).toBe(true);
    expect(stamp.classes()).toContain('is-show');
    expect(stamp.text()).toBe('已登记 · visit I20260925000000001');
    expect(stamp.attributes('aria-live')).toBe('polite');
    expect(vi.mocked(ElMessage.success)).not.toHaveBeenCalled();
    // 登记确认成功后队列刷新（list 第二次调用）
    expect(admissions.list).toHaveBeenCalledTimes(2);
  });

  it('FROZEN 患者登记确认透出 IP-1003 ProblemDetail detail 原文（4xx 口径）', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock()));
    vi.mocked(admissions.register).mockRejectedValue({
      errorCode: 'IP-1003',
      detail: '患者档案冻结或合并中，禁止办理住院',
    });
    const wrapper = mount(AdmissionView);
    await flushPromises();
    await clickButton(wrapper, '登记');
    await wrapper.find('select[aria-label="医保类型"]').setValue('职工医保');
    await clickButton(wrapper, '登记确认');
    await flushPromises();
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalledWith('患者档案冻结或合并中，禁止办理住院');
    // 失败不触发队列刷新（list 仍只 1 次）
    expect(admissions.list).toHaveBeenCalledTimes(1);
  });

  it('登记确认未选医保类型零出网显式校验拦截', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock()));
    const wrapper = mount(AdmissionView);
    await flushPromises();
    await clickButton(wrapper, '登记');
    await clickButton(wrapper, '登记确认');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择医保类型');
    expect(admissions.register).not.toHaveBeenCalled();
  });

  it('创建住院证成功出网并刷新候床队列（建证成功反馈维持 message 批注条——蓝图钤印族仅登记/预约）', async () => {
    vi.mocked(searchPatients).mockResolvedValue({
      content: [patientMock()],
      page: 0,
      size: 20,
      total: 1,
    });
    vi.mocked(admissions.create).mockResolvedValue(admissionMock());
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // 患者搜索 → 选中检索行（select 载体）→ 补齐开单医生 → 创建
    const searchInput = wrapper.find('input[placeholder="姓名/证件号/手机号"]');
    await searchInput.setValue('张');
    await clickButton(wrapper, '搜索');
    await flushPromises();
    const patientSelect = wrapper.find('select[aria-label="患者检索结果"]');
    await patientSelect.setValue('1932000000000000001');
    const doctorInput = wrapper.find('input[placeholder="开单医生工号"]');
    await doctorInput.setValue('D001');
    await clickButton(wrapper, '创建住院证');
    await flushPromises();
    expect(admissions.create).toHaveBeenCalledWith(
      expect.objectContaining({
        patientId: '1932000000000000001',
        sourceType: 'OUTPATIENT',
        admissionType: 'NORMAL',
        issuedDoctorId: 'D001',
      }),
    );
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    expect(admissions.list).toHaveBeenCalledTimes(2);
  });

  it('作废经中档确认（文案冻结）后出网并刷新候床队列', async () => {
    vi.mocked(admissions.list).mockResolvedValue(oneRowQueue(admissionMock()));
    vi.mocked(admissions.cancel).mockResolvedValue(admissionMock({ status: 'CANCELLED' }));
    const wrapper = mount(AdmissionView);
    await flushPromises();
    await clickButton(wrapper, '作废');
    // 中档确认文案逐字冻结（危险/不可逆操作不走钤印，G2）
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '即将作废住院证 AD20260925001，预约床位将联动释放，确认？',
      '住院证作废确认',
      expect.objectContaining({ confirmButtonText: '确认作废', cancelButtonText: '取消' }),
    );
    await flushPromises();
    expect(admissions.cancel).toHaveBeenCalledWith('AD20260925001');
    expect(admissions.list).toHaveBeenCalledTimes(2);
  });

  /** PR-4F 权限态挂载：注入 pinia 与 v-perm 指令（main.ts 全局注册仅应用装配态，单测自备） */
  function mountView() {
    return mount(AdmissionView, {
      global: { plugins: [pinia], directives: { perm: permDirective } },
    });
  }

  it('PR-4F 权限态：会话无 inpatient:admission:btn:create 时创建住院证按钮不渲染（D-34 无码全隐藏）', async () => {
    // 覆写 beforeEach 含码种子为无码会话（auth store 于挂载时自 sessionStorage 恢复）
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({ token: 't', refreshToken: 'r', user: { userId: 1 } }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('住院证登记');
    expect(wrapper.text()).not.toContain('创建住院证');
    wrapper.unmount();
  });

  it('PR-4F 权限态：会话含 inpatient:admission:btn:create 时创建住院证按钮渲染', async () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['inpatient:admission:btn:create'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.findAll('button').some((b) => b.text() === '创建住院证')).toBe(true);
    wrapper.unmount();
  });

  it('PR-4F 权限态：有 #14 无 #13（REGISTRAR 人格）会话行内登记钮可见且登记确认可达（修复环 R1 断链面锁定）', async () => {
    // V1121 种子 #13→DOCTOR、#14→REGISTRAR/NURSE：登记员会话无 #13——行内「登记」为
    // #14 入区链路前置选择钮（不挂码），若被 #13 门控移除则无法选证、登记确认恒禁用
    vi.mocked(admissions.list).mockResolvedValue(
      oneRowQueue(admissionMock({ status: 'SCHEDULED' })),
    );
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 't',
        refreshToken: 'r',
        user: { userId: 1, permissions: ['inpatient:admission:btn:register'] },
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    // 行内「登记」钮渲染存在（无 #13 不被 DOM 移除，入区链路入口完整）
    expect(wrapper.findAll('button').some((b) => b.text() === '登记')).toBe(true);
    // 未选证时登记确认钮已渲染（#14 含码）但因 selectedAdmission 为空而禁用
    const confirmBefore = wrapper.findAll('button').find((b) => b.text() === '登记确认');
    expect(confirmBefore?.attributes('disabled')).toBeDefined();
    // 选中住院证后禁用解锁——登记确认可达，链路（选择→确认）对该人格整体可用
    await clickButton(wrapper, '登记');
    const confirmAfter = wrapper.findAll('button').find((b) => b.text() === '登记确认');
    expect(confirmAfter?.attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });
});
