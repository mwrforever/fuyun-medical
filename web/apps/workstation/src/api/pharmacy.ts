/**
 * 药事域 API（web A.3-5 模块化）：药品字典/开方作废/调剂三段/退药受理/占用查询/
 * 住院用药审方（M06 薄切片工作台：清单/通过/驳回）/住院摆药计划（dispense-plans
 * 八端点：分页/生成/摆药流五步/PIVAS 贴签数据面）。
 * 路径前缀 /v1/pharmacy/**（baseURL 已含 /api）；雪花 id 与数量一律 string 承载（web A.3-6），
 * 本域无金额运算面（计费权威在 M13）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';
// 分页壳（shared 手写声明）；import 恒置顶（lint import 序）
import type { PageResult } from '@fuyun/shared';

/** 契约类型别名（生成物唯一来源，A.3-3） */
export type DrugVO = components['schemas']['DrugVO'];
export type DrugSaveRequest = components['schemas']['DrugSaveRequest'];
export type InsuranceMappingRequest = components['schemas']['InsuranceMappingRequest'];
export type PrescriptionVO = components['schemas']['PrescriptionVO'];
export type PrescriptionCreateRequest = components['schemas']['PrescriptionCreateRequest'];
export type DispenseVO = components['schemas']['DispenseVO'];
export type PickRequest = components['schemas']['PickRequest'];
export type DispenseReturnRequest = components['schemas']['DispenseReturnRequest'];
export type OccupancyVO = components['schemas']['OccupancyVO'];
/** 审方任务行（医嘱号/患者号面/药品明细串/申请科室/状态/药师意见） */
export type ReviewTaskVO = components['schemas']['ReviewTaskVO'];
/** 审方任务分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type ReviewTaskPage = components['schemas']['PageResultReviewTaskVO'];
/** 审方决策入参（opinion 驳回必填由服务端守卫——缺/空白 PH-1020） */
export type ReviewDecisionRequest = components['schemas']['ReviewDecisionRequest'];
/** 摆药计划行（planNo/医嘱号/患者号面/病区/类型/给药时点/状态/排批/时间线；全字段可缺省） */
export type DispensePlanVO = components['schemas']['DispensePlanVO'];
/** 摆药计划分页出参（common PageResult 单泛型生成物：content/page/size/total） */
export type DispensePlanPage = components['schemas']['PageResultDispensePlanVO'];
/** 计划生成入参（m04OrderNo+目标病区均必填——病区由发起端显式声明，后端裁决口径） */
export type DispensePlanGenerateRequest = components['schemas']['DispensePlanGenerateRequest'];
/** 配送交接入参（carrier 可空——无落列载体，后端日志留痕承载） */
export type DispensePlanDeliverRequest = components['schemas']['DispensePlanDeliverRequest'];
/** 病区签收入参（receivedBy 兼容保留——服务端一律以令牌身份落值，W-72；原『与药房操作者分权留痕』语义随服务端强制收敛） */
export type DispensePlanReceiveRequest = components['schemas']['DispensePlanReceiveRequest'];
/** PIVAS 贴签数据面出参（脱敏患者名/病区/排批/调配核对双人/药品明细；打印归 M01 降级注记） */
export type DispensePlanLabelVO = components['schemas']['DispensePlanLabelVO'];
/** 住院可退明细读面出参（W-66：退药弹窗多行化数据源；数量 DECIMAL string 承载——可退净量=已发-已退） */
export type DispensePlanReturnableVO = components['schemas']['DispensePlanReturnableVO'];

/** 审方任务状态选项（工作台状态过滤词表：待审/已通过/已驳回） */
export const REVIEW_TASK_STATUS_OPTIONS: ReadonlyArray<{ code: string; label: string }> = [
  { code: 'PENDING', label: '待审' },
  { code: 'APPROVED', label: '已通过' },
  { code: 'REJECTED', label: '已驳回' },
];

/** 摆药计划五列看板列定义（后端 DispenseStatus 住院链五活动态 1:1 映射；列序即渲染序，
 * spec 冻结断言——CANCELLED 作废态与退药态（由调剂行承载，计划行不迁）不进列） */
export const DISPENSE_PLAN_COLUMNS: ReadonlyArray<{ status: string; label: string }> = [
  { status: 'CREATED', label: '待摆药' },
  { status: 'PICKING', label: '摆药中' },
  { status: 'PICKED', label: '已配待核对' },
  { status: 'CHECKED', label: '已核对待交接' },
  { status: 'DELIVERED', label: '病区已签收' },
];

/** 计划类型标签词表（后端 resolvePlanType 判定三值：单剂量口服/PIVAS 静配/整包） */
export const DISPENSE_PLAN_TYPE_LABELS: Record<string, string> = {
  SINGLE_DOSE: '单剂量',
  PIVAS: 'PIVAS',
  WHOLE: '整包',
};

/** 选药检索（keyword/essential/antibioClass/insuranceMapped；默认启用面）。 */
export async function searchDrugs(params: {
  keyword?: string;
  essential?: boolean;
  antibioClass?: string;
  insuranceMapped?: boolean;
  page?: number;
  size?: number;
}): Promise<PageResult<DrugVO>> {
  const resp = await http.get<PageResult<DrugVO>>('/v1/pharmacy/drugs/search', { params });
  return resp.data;
}

/** 药品建档。 */
export async function createDrug(payload: DrugSaveRequest): Promise<DrugVO> {
  const resp = await http.post<DrugVO>('/v1/pharmacy/drugs', payload);
  return resp.data;
}

/** 药品档案变更。 */
export async function updateDrug(id: string, payload: DrugSaveRequest): Promise<DrugVO> {
  const resp = await http.put<DrugVO>(`/v1/pharmacy/drugs/${id}`, payload);
  return resp.data;
}

/** 医保编码对照（变更类型 MAPPING 广播；对照后 insuredSettleable 转 true）。 */
export async function mapInsurance(id: string, payload: InsuranceMappingRequest): Promise<void> {
  await http.post(`/v1/pharmacy/drugs/${id}/insurance-mapping`, payload);
}

/** 开方（同步返回处方号+预检分级；操作者由后端登录上下文注入）。 */
export async function createPrescription(
  payload: PrescriptionCreateRequest,
): Promise<PrescriptionVO> {
  const resp = await http.post<PrescriptionVO>('/v1/pharmacy/prescriptions', payload);
  return resp.data;
}

/** 处方分页查询（visitId/patientId/rxNo/status；status=PENDING_DISPENSE 即发药队列）。 */
export async function listPrescriptions(params: {
  visitId?: string;
  patientId?: string;
  rxNo?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<PrescriptionVO>> {
  const resp = await http.get<PageResult<PrescriptionVO>>('/v1/pharmacy/prescriptions', {
    params,
  });
  return resp.data;
}

/** 处方作废（未缴费联动费用作废；已缴费后端拒并弹错引导退药/退费）。 */
export async function cancelPrescription(rxNo: string, reason: string): Promise<void> {
  await http.post(`/v1/pharmacy/prescriptions/${rxNo}/cancel`, { reason });
}

/** 按处方号查发药单（工作台回显；定版出参 List<DispenseVO>，无单为空数组由调用方判空取首行）。 */
export async function listDispenses(params: { rxNo: string }): Promise<DispenseVO[]> {
  const resp = await http.get<DispenseVO[]>('/v1/pharmacy/dispenses', { params });
  return resp.data;
}

/** 配药（FEFO 选批+批次锁定；追溯码逐码录入，「无码不结」）。 */
export async function pickDispense(dispenseNo: string, payload: PickRequest): Promise<void> {
  await http.post(`/v1/pharmacy/dispenses/${dispenseNo}/pick`, payload);
}

/** 扫码核对（第二药师；同人由后端 PH-1011 拒）。 */
export async function verifyDispense(dispenseNo: string): Promise<void> {
  await http.post(`/v1/pharmacy/dispenses/${dispenseNo}/verify`);
}

/** 发药签名（终笔；发药即出库+计费占用）。 */
export async function issueDispense(dispenseNo: string): Promise<void> {
  await http.post(`/v1/pharmacy/dispenses/${dispenseNo}/issue`);
}

/** 退药受理（实物核验=追溯码逐码；mode=ISSUED_RETURN|DISPENSING_CANCEL）。 */
export async function createDispenseReturn(payload: DispenseReturnRequest): Promise<void> {
  await http.post('/v1/pharmacy/dispense-returns', payload);
}

/** 执行占用查询（M13 对接位）。 */
export async function listMedicationOccupancy(params: {
  patientId: string;
  visitId?: string;
  itemCode?: string;
}): Promise<OccupancyVO[]> {
  const resp = await http.get<OccupancyVO[]>('/v1/pharmacy/medication-occupancy', { params });
  return resp.data;
}

/** 住院用药审方资源组（M06 薄切片工作台）：清单（先到先审 FIFO）/通过/驳回。
 * 任务 id 为雪花 long 后端 string 化输出，前端 string 承载（web A.3-6）。 */
export const reviewTasks = {
  /** 审方工作台列表（状态过滤可空=全部；写操作后由调用方重拉刷新）。 */
  list: async (params: {
    status?: string;
    page: number;
    size: number;
  }): Promise<ReviewTaskPage> => {
    const resp = await http.get<ReviewTaskPage>('/v1/pharmacy/review-tasks', { params });
    return resp.data;
  },
  /** 审方通过（PENDING→APPROVED）：回执 pharmacy.medication-order.audit-completed 回流 M04
   * 置医嘱可执行；意见可选（body 可缺省）。 */
  approve: async (id: string, payload?: ReviewDecisionRequest): Promise<void> => {
    await http.post(`/v1/pharmacy/review-tasks/${id}/approve`, payload);
  },
  /** 审方驳回（PENDING→REJECTED）：意见必填由服务端守卫（缺/空白 PH-1020——400 先拦会破坏
   * 冻结语义）；回执 audit-rejected 驱动医生站修改重提。 */
  reject: async (id: string, payload: ReviewDecisionRequest): Promise<void> => {
    await http.post(`/v1/pharmacy/review-tasks/${id}/reject`, payload);
  },
};

/** 住院摆药计划资源组（P2 PR-3 Task 16，dispense-plans 八端点）：分页查询/计划生成/
 * 摆药流五步（pick→verify→issue→deliver→receive，deliver 为 CHECKED 态内时间线半步
 * 不迁状态、receive 才迁 DELIVERED——Task 8 裁决口径）/ PIVAS 贴签数据面。
 * 摆药五步为法定留痕写操作（后端 WRITE 审计），操作后由调用方重拉看板刷新。 */
export const dispensePlans = {
  /** 计划分页查询（医嘱号/病区/状态三过滤全可空组合；planTime 升序由后端承载）。 */
  list: async (params: {
    m04OrderNo?: string;
    wardId?: string;
    status?: string;
    page?: number;
    size?: number;
  }): Promise<DispensePlanPage> => {
    const resp = await http.get<DispensePlanPage>('/v1/pharmacy/dispense-plans', { params });
    return resp.data;
  },
  /** 生成住院摆药计划（APPROVED 前置+长期频次分解+uk 幂等；返回该医嘱全部未删计划）。 */
  generate: async (payload: DispensePlanGenerateRequest): Promise<DispensePlanVO[]> => {
    const resp = await http.post<DispensePlanVO[]>('/v1/pharmacy/dispense-plans/generate', payload);
    return resp.data;
  },
  /** 摆药开始（CREATED→PICKING；单剂量人工摆药+库存预校验、PIVAS 排药+排批号）。 */
  pick: async (no: string): Promise<void> => {
    await http.post(`/v1/pharmacy/dispense-plans/${no}/pick`);
  },
  /** 药师核对（PICKING→PICKED；双人核对第二签分权同人拒、PIVAS 链贴签核对置位）。 */
  verify: async (no: string): Promise<void> => {
    await http.post(`/v1/pharmacy/dispense-plans/${no}/verify`);
  },
  /** 出库交接（PICKED→CHECKED；落住院调剂行+库存扣减+批次回填）。 */
  issue: async (no: string): Promise<void> => {
    await http.post(`/v1/pharmacy/dispense-plans/${no}/issue`);
  },
  /** 配送交接（CHECKED 态内 issued_at 时间线半步——不迁移状态，签收归 receive；
   * carrier 可空无落列载体，后端日志留痕承载；入参缺省时无请求体出网）。
   */
  deliver: async (no: string, payload?: DispensePlanDeliverRequest): Promise<void> => {
    if (payload === undefined) {
      await http.post(`/v1/pharmacy/dispense-plans/${no}/deliver`);
      return;
    }
    await http.post(`/v1/pharmacy/dispense-plans/${no}/deliver`, payload);
  },
  /** 病区签收（CHECKED→DELIVERED CAS+completed 事件住院载荷；receivedBy 兼容保留
   * ——服务端一律以令牌身份落值，W-72，原『与药房操作者分权留痕』语义随服务端强制收敛）。 */
  receive: async (no: string, payload: DispensePlanReceiveRequest): Promise<void> => {
    await http.post(`/v1/pharmacy/dispense-plans/${no}/receive`, payload);
  },
  /** PIVAS 贴签数据面（脱敏患者名/病区/排批/药品明细；仅 PIVAS 链承载，非 PIVAS 后端
   * 409 拒——调用方按 planType 门控）。 */
  label: async (no: string): Promise<DispensePlanLabelVO> => {
    const resp = await http.get<DispensePlanLabelVO>(`/v1/pharmacy/dispense-plans/${no}/label`);
    return resp.data;
  },
  /** 可退明细读面（DELIVERED 计划的 NORMAL 明细与可退净量——退药弹窗打开拉取，
   * 非 DELIVERED 409 归失败弹错）。 */
  returnable: async (no: string): Promise<DispensePlanReturnableVO> => {
    const resp = await http.get<DispensePlanReturnableVO>(
      `/v1/pharmacy/dispense-plans/${no}/returnable`,
    );
    return resp.data;
  },
};
