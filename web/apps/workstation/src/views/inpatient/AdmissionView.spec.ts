// 入院登记台单测（/inpatient/admission，M04 FU-M04-01 前端面）：候床队列加载渲染（后端
// 冻结排序=急诊优先＞预约时段＞候床时长，前端只做标识透出）、预约动作出网（携预约时段）、
// 登记确认成功提示并刷新队列、FROZEN 患者 IP-1003 ProblemDetail detail 原文透出（4xx 口径）、
// 未选医保类型零出网显式校验、创建住院证成功出网并刷新队列。
// api mock 承载零出网（vi.mock('@/api/inpatient') 整模块替身 + @/api/patient 替身），
// 不打真实网络；断言业务结果不绑定实现细节。
import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import { admissions, beds } from '@/api/inpatient';
import type { AdmissionVO, BedMapVO } from '@/api/inpatient';
import { searchPatients } from '@/api/patient';
import type { PatientVO } from '@/api/patient';
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
  BED_STATUS_LABELS: {
    FREE: '空床',
    RESERVED: '预占',
    OCCUPIED: '占床',
    DISINFECTING: '消毒中',
    MAINTENANCE: '维修中',
  },
  WARD_OPTIONS: [{ code: 'W01', label: 'W01 演示病区' }],
  admissions: {
    create: vi.fn(),
    list: vi.fn(),
    schedule: vi.fn(),
    cancel: vi.fn(),
    register: vi.fn(),
  },
  visits: { admitWard: vi.fn(), arrears: vi.fn() },
  beds: {
    map: vi.fn(),
    reserve: vi.fn(),
    assign: vi.fn(),
    release: vi.fn(),
    disinfectDone: vi.fn(),
    maintain: vi.fn(),
    maintainDone: vi.fn(),
  },
  transfer: { execute: vi.fn(), changeBed: vi.fn() },
}));

vi.mock('@/api/patient', () => ({
  matchCheck: vi.fn(),
  createPatient: vi.fn(),
  searchPatients: vi.fn(),
  getPatient: vi.fn(),
  changeFreeze: vi.fn(),
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

/** 按按钮文案点击（el-button 通用） */
async function clickButton(wrapper: VueWrapper, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((b) => b.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  await button.trigger('click');
}

describe('入院登记台', () => {
  beforeEach(() => {
    for (const fn of [
      admissions.create,
      admissions.list,
      admissions.schedule,
      admissions.cancel,
      admissions.register,
      beds.map,
      searchPatients,
    ]) {
      vi.mocked(fn).mockReset();
    }
    vi.mocked(ElMessage.warning).mockClear();
    vi.mocked(ElMessage.error).mockClear();
    vi.mocked(ElMessage.success).mockClear();
    vi.mocked(ElMessageBox.confirm).mockClear();
    // 只读面兜底空：防未 stub 的 resolve 断链
    vi.mocked(admissions.list).mockResolvedValue(emptyQueue());
    vi.mocked(beds.map).mockResolvedValue([]);
    vi.mocked(searchPatients).mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total: 0,
    });
  });

  it('候床队列加载渲染并透出急诊优先标识（后端冻结排序，前端只读标识）', async () => {
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
    const text = wrapper.text();
    expect(text).toContain('AD20260925002');
    expect(text).toContain('AD20260925001');
    // 急诊入院行透出「急诊优先」排序标识（普通入院行无；类契约机器判据）
    expect(wrapper.findAll('.admission-emergency-flag').length).toBe(1);
    // 候床队列计数与冻结排序说明可见
    expect(text).toContain('共 2 条');
    expect(text).toContain('急诊优先 ＞ 预约时段 ＞ 候床时长');
    expect(admissions.list).toHaveBeenCalledWith({ status: undefined, page: 0, size: 50 });
  });

  it('预约动作出网携预约时段并刷新候床队列', async () => {
    vi.mocked(admissions.list).mockResolvedValue({
      content: [admissionMock({ status: 'WAITING' })],
      page: '0',
      size: '50',
      total: '1',
    });
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
      expect.objectContaining({ expectDate: '2026-09-28' }),
    );
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 预约成功后队列重载（list 第二次调用）
    expect(admissions.list).toHaveBeenCalledTimes(2);
  });

  it('登记确认成功提示并刷新候床队列（同事务签发 visit 号由后端承载）', async () => {
    vi.mocked(admissions.list).mockResolvedValue({
      content: [admissionMock()],
      page: '0',
      size: '50',
      total: '1',
    });
    vi.mocked(admissions.register).mockResolvedValue({
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
    });
    const wrapper = mount(AdmissionView);
    await flushPromises();
    // 行内「登记」选中候床证 → 右栏登记确认
    await clickButton(wrapper, '登记');
    await wrapper.find('select[aria-label="医保类型"]').setValue('职工医保');
    await clickButton(wrapper, '登记确认');
    await flushPromises();
    expect(admissions.register).toHaveBeenCalledWith('AD20260925001', {
      insuranceType: '职工医保',
    });
    expect(vi.mocked(ElMessage.success)).toHaveBeenCalled();
    // 登记确认成功后队列刷新（list 第二次调用）
    expect(admissions.list).toHaveBeenCalledTimes(2);
  });

  it('FROZEN 患者登记确认透出 IP-1003 ProblemDetail detail 原文（4xx 口径）', async () => {
    vi.mocked(admissions.list).mockResolvedValue({
      content: [admissionMock()],
      page: '0',
      size: '50',
      total: '1',
    });
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
    vi.mocked(admissions.list).mockResolvedValue({
      content: [admissionMock()],
      page: '0',
      size: '50',
      total: '1',
    });
    const wrapper = mount(AdmissionView);
    await flushPromises();
    await clickButton(wrapper, '登记');
    await clickButton(wrapper, '登记确认');
    await flushPromises();
    expect(vi.mocked(ElMessage.warning)).toHaveBeenCalledWith('请选择医保类型');
    expect(admissions.register).not.toHaveBeenCalled();
  });

  it('创建住院证成功出网并刷新候床队列', async () => {
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
});
