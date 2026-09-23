package com.fuyun.nursing.service;

import com.fuyun.nursing.dto.NurseAssignmentRequest;
import com.fuyun.nursing.dto.WardPatientRegisterRequest;
import com.fuyun.nursing.dto.WardPatientRemoveRequest;
import com.fuyun.nursing.vo.NurseAssignmentVO;
import com.fuyun.nursing.vo.WardConfigVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.nursing.vo.WardPatientVO;
import java.util.List;

/**
 * 病区元数据域服务（V801 三表业务面；Task 5/7/8 消费的冻结接口——在区校验/appendRiskFlag/
 * 详情卡聚合均挂本面）。端点面冻结（2026-09-22 批复「禁写路径下渗」）：ward 面仅 register/remove/
 * listByWard/detail 四能力 + 责任分配三能力，禁补录入院时间、独立转床端点、护理级别权威变更、
 * 床位主数据维护、出院业务状态变更等任何 ADT 写能力（M05 对住院业务状态零权威）。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface IWardMetaService {

    /**
     * 入区登记（P1 过渡通道，幂等 upsert）：VisitIdValidator 结构校验（NS-1003）→
     * PatientContextResolver 拦截（FROZEN NS-1004；MERGED 按 resolvedPatientId 收敛主档）→
     * 床位占用检查（NS-1002）→ insert（双唯一约束冲突兜底转 NS-1002）。同 visit_id 已在区时
     * 语义为视图属性更新：视图属性全等零写入直接返回；有差异更新既有行并按新床位校验占用。
     *
     * @param req 登记入参，非空；来源：操作者工作站表单
     * @return 登记行出参，非空
     * @throws BizException NS-1003（400 visitId 结构不合法）/ NS-1004（409 档案冻结）/
     *                      NS-1002（409 床位占用或唯一约束冲突）/ NS-1019（400 护理级别 code 非法）
     */
    WardPatientVO register(WardPatientRegisterRequest req);

    /**
     * 移出病区一览（GC38 四护栏）：仅置本地视图行 status=REMOVED 的单表单语句 CAS——
     * <b>零外发</b>（不发事件/不登记/不可订阅）、<b>触达最小</b>（无级联、reason 仅入留痕不落库）、
     * <b>无任何住院业务状态变更</b>（出院/转科语义归 M04）。移出后床位占用谓词自然解除（可再登记）。
     * 零回读语义：不回读行数据，返回仅携 visitId 的确认出参（其余组件 null）。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @param req     移出入参（reason 留痕），非空；来源：操作者录入
     * @return 确认出参（仅 visitId 有值），非空
     * @throws BizException NS-1001（404 在区行不存在或已移出）
     */
    WardPatientVO remove(String visitId, WardPatientRemoveRequest req);

    /**
     * 病区在区患者一览（床位序）：仅 IN_WARD 行，排除 REMOVED；按 bed_no、admitted_at 升序
     * （DB 侧排序，一览床位序展示依据）。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @return 在区行出参清单（无行返回空清单，非 null）；按床位序
     */
    List<WardPatientVO> listByWard(String wardId);

    /**
     * 患者详情卡聚合：在区行 + 过敏实时嵌查（AllergyChecker）+ 当班责任护士 + 在途任务段
     * （INursingTaskService#inFlightByVisit 实时填充，Task 7 补入；读路径含惰性逾期写，禁 readOnly）。
     * 不含体征摘要（前端另调体征查询组装，防服务间循环依赖）。
     *
     * @param visitId 住院就诊号，非空；来源：路径参数
     * @return 详情卡出参，非空
     * @throws BizException NS-1001（404 在区行不存在）
     */
    WardPatientDetailVO detail(String visitId);

    /**
     * 责任护士分配（FU-M05-01）：类型一致性校验（NS-1019）→ 床位/患者双唯一前置查重（NS-1002）→
     * 落 ACTIVE 行（部分唯一索引兜底转 NS-1002）。
     *
     * @param req 分配入参，非空；来源：操作者护士站表单
     * @return 分配行出参，非空
     * @throws BizException NS-1019（400 类型与必填组件不一致或 code 非法）/
     *                      NS-1002（409 床位或患者班次分配重复）
     */
    NurseAssignmentVO assign(NurseAssignmentRequest req);

    /**
     * 撤销责任分配（ACTIVE→CANCELLED，逻辑留痕不删行）。
     *
     * @param id 分配 id，非空；来源：路径参数
     * @throws BizException NS-1016（409 分配不存在或已撤销）
     */
    void unassign(Long id);

    /**
     * 病区指定班次的生效分配清单（当日有效窗口内；交接班 Task 9 消费）。
     *
     * @param wardId    病区编码，非空；来源：查询参数
     * @param shiftCode 班次 code，非空；来源：查询参数
     * @return 分配出参清单（无行返回空清单，非 null）
     */
    List<NurseAssignmentVO> listAssignments(String wardId, String shiftCode);

    /**
     * 病区护理配置读取（结构化出参：体征频次/班次定义/IoT 开关）。
     *
     * @param wardId 病区编码，非空；来源：路径参数
     * @return 配置出参，非空
     * @throws BizException NS-1016（404 未知病区：无配置行）
     */
    WardConfigVO wardConfig(String wardId);

    /**
     * 风险标识追加回写（Task 8 评估高危消费）：追加缺失项、不重复追加、逗号分隔；
     * 已含该标识时零写入直接返回。
     *
     * @param visitId 住院就诊号，非空；来源：评估单载荷
     * @param flag    风险标识 code（如 FALL/PRESSURE），非空；来源：评估单高危结果
     * @throws BizException NS-1001（404 在区行不存在）
     */
    void appendRiskFlag(String visitId, String flag);
}
