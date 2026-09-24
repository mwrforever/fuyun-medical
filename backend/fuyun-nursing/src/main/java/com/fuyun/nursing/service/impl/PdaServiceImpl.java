package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.utils.SensitiveMasker;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.PdaPatrolRequest;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.WardPatientStatus;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IPdaService;
import com.fuyun.nursing.service.IVitalSignService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.PdaPatientSummaryVO;
import com.fuyun.nursing.vo.VitalSignVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.api.PatientIdentityQuery;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDA 护理面服务实现（Task 10：标识解析患者摘要 + 巡视打卡）。标识三形态解析：
 * 14 位 I 头腕带就诊编码直查在区行（ShiftHandoverServiceImpl 同款 NursingWardPatientMapper
 * 直查先例）；其余经 PatientIdentityQuery 盲索引归一（18 位 X/x 结尾判 ID_CARD，否则
 * VISIT_CARD），PAT-1001 统一映射 NS-1003（M05 错误码出口唯一）。摘要链复用
 * IWardMetaService#detail 聚合病区上下文（VitalSignServiceImpl 在区校验同款消费面），
 * 不在区降级为基本信息（PDA 患者查询不限在区）；巡视链四参委托 Task 7 冻结的
 * patrol 面（行生而终态，不发事件）。
 * 线程安全：无状态 singleton；两方法 @Transactional 收口（patientSummary 链内 detail →
 * inFlightByVisit 含惰性逾期 CAS 写，禁 readOnly——PG 只读事务 UPDATE 必败）。
 */
@Slf4j
public class PdaServiceImpl implements IPdaService {

    /** 腕带就诊编码定长（I 型 visit_id：1 位类型码 + 13 位数字段） */
    private static final int VISIT_CODE_LENGTH = 14;

    /** 腕带就诊编码类型码（I=住院，M04 签发） */
    private static final char VISIT_CODE_PREFIX = 'I';

    /** 证件号形态长度（18 位含校验位） */
    private static final int ID_CARD_LENGTH = 18;

    /** 标识类型词表值：证件号（PatientIdentityQuery:17 javadoc 词表） */
    private static final String IDENTIFIER_TYPE_ID_CARD = "ID_CARD";

    /** 标识类型词表值：就诊卡号（PatientIdentityQuery:17 javadoc 词表） */
    private static final String IDENTIFIER_TYPE_VISIT_CARD = "VISIT_CARD";

    private final NursingWardPatientMapper wardPatientMapper;

    private final PatientIdentityQuery identityQuery;

    private final PatientContextResolver contextResolver;

    private final AllergyChecker allergyChecker;

    private final IWardMetaService wardMetaService;

    private final IVitalSignService vitalSignService;

    private final INursingTaskService taskService;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param wardPatientMapper 病区患者视图 mapper，非空；腕带编码在区行直查（按就诊号/按患者双路）
     * @param identityQuery     标识介质解析（patient api），非空；卡号/证件号盲索引归一主档
     * @param contextResolver   患者上下文解析（patient api），非空；FROZEN 拦截与 MERGED 收敛
     * @param allergyChecker    过敏项嵌查（patient api），非空；摘要过敏面按收敛主档实时取数
     * @param wardMetaService   病区元数据服务，非空；摘要病区上下文聚合（detail 消费面）
     * @param vitalSignService  生命体征服务，非空；最近一次体征摘要取数
     * @param taskService       护理任务服务，非空；巡视打卡四参委托（Task 7 冻结面）
     */
    public PdaServiceImpl(
            NursingWardPatientMapper wardPatientMapper,
            PatientIdentityQuery identityQuery,
            PatientContextResolver contextResolver,
            AllergyChecker allergyChecker,
            IWardMetaService wardMetaService,
            IVitalSignService vitalSignService,
            INursingTaskService taskService) {
        this.wardPatientMapper = wardPatientMapper;
        this.identityQuery = identityQuery;
        this.contextResolver = contextResolver;
        this.allergyChecker = allergyChecker;
        this.wardMetaService = wardMetaService;
        this.vitalSignService = vitalSignService;
        this.taskService = taskService;
    }

    /**
     * PDA 患者摘要（床旁速览）：守卫链见接口注。病区上下文经 detail 聚合（在区行定位后
     * 委托），行定位与 detail 之间被并发移出时降级为不在区摘要（仅 NS-1001 一种可降级，
     * 其余异常原样上抛）；过敏与最近体征按收敛主档取数（合并后临床数据挂主档，CF-3）。
     *
     * @param identifier 扫码标识（腕带就诊编码/就诊卡号/证件号三合一），非空；来源：PDA 扫码或手工录入
     * @return 患者摘要出参（不在区时病区上下文字段为空、在途计数 0），非空
     * @throws BizException NS-1019（400 标识空白）/ NS-1003（400 标识未命中在档患者，PAT-1001 映射）/
     *                      NS-1001（404 腕带就诊编码无在区行）/ NS-1004（409 档案冻结）
     */
    @Override
    @Transactional
    public PdaPatientSummaryVO patientSummary(String identifier) {
        // 本方法纯读为主，但链内 detail → inFlightByVisit 含惰性逾期写（casMarkOverdue UPDATE），
        // 且 Spring 默认传播（REQUIRED）下 readOnly 标记随本事务传播至内层——禁 readOnly，
        // 否则过夜逾期任务场景下 PG 只读事务内 UPDATE 直接报错
        String normalized = identifier == null ? "" : identifier.trim();
        // 守卫链①：标识空白显式拒绝（服务面校验覆盖模块内直调场景，Web 层由必填参数兜底）
        if (normalized.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "PDA 扫码标识不能为空");
        }
        // 守卫链②：标识解析归一主档（腕带编码直查在区行 / 卡号与证件号盲索引归一）
        NursingWardPatient visitRow = null;
        long patientId;
        if (isVisitCode(normalized)) {
            visitRow = requireInWardByVisit(normalized);
            patientId = visitRow.getPatientId();
        } else {
            patientId = resolveByIdentifier(normalized);
        }
        // 守卫链③：档案拦截与合并收敛（FROZEN 拒查；MERGED 收敛主档，业务数据一律挂收敛值）
        long resolvedPatientId = requireNotBlockedAndConverge(patientId);
        // 病区上下文：卡路径按收敛主档定位在区行（腕带编码路径复用解析行），在区才聚合 detail
        NursingWardPatient wardRow = visitRow != null ? visitRow : findInWardByPatient(resolvedPatientId);
        WardPatientDetailVO wardCtx = null;
        if (wardRow != null) {
            try {
                // 第三方接口调用（模块内）：病区详情聚合（在区行 + 当班责任护士 + 在途任务段）
                wardCtx = wardMetaService.detail(wardRow.getVisitId());
            } catch (BizException e) {
                if (!NursingErrorCode.WARD_PATIENT_NOT_FOUND
                        .getCode()
                        .equals(e.getErrorCode().getCode())) {
                    // 仅并发移出（NS-1001）可降级：其余失败（如病区配置异常）不吞，原样上抛
                    throw e;
                }
                log.warn("PDA 摘要病区上下文并发移出，降级为不在区摘要：visitId={}，patientId={}", wardRow.getVisitId(), resolvedPatientId);
            }
        }
        // 第三方接口调用：patient 过敏项实时嵌查（按收敛主档取数，合并后过敏面挂主档）
        List<AllergyItem> allergies = allergyChecker.listActiveAllergies(resolvedPatientId);
        // 数据库读操作（模块内）：最近一次体征单行点查（ALGO-01：O(1)，替代全史清单拉取取末位），无记录为 null（可空语义）
        VitalSignVO latestVitals = vitalSignService.latestByPatient(resolvedPatientId);
        PdaPatientSummaryVO vo = new PdaPatientSummaryVO(
                resolvedPatientId,
                // 脱敏输出：姓名掩码出网（保留姓氏），证件号/手机号类字段不进 VO 面
                wardCtx == null ? null : SensitiveMasker.maskName(wardCtx.patientName()),
                wardCtx == null ? null : wardCtx.wardId(),
                wardCtx == null ? null : wardCtx.bedNo(),
                wardCtx == null ? null : wardCtx.nursingLevel(),
                allergies,
                latestVitals,
                wardCtx == null ? 0 : wardCtx.inFlightTasks().size());
        log.info(
                "PDA 患者摘要完成：identifierTail={}，patientId={}，wardId={}，bedNo={}，inFlightTaskCount={}",
                identifierTail(normalized),
                resolvedPatientId,
                vo.wardId(),
                vo.bedNo(),
                vo.inFlightTaskCount());
        return vo;
    }

    /**
     * PDA 巡视打卡：守卫链见接口注。归属校验双保险——标识解析出的患者与就诊在区行
     * patient_id 必须一致（扫错腕带给错人打卡防线，NS-1016 资源冲突语义）；wardId 由
     * 在区行装配（不信客户端），随四参委托 Task 7 冻结面（行生而终态、打卡记录不发事件）。
     *
     * @param req 打卡入参（identifier 扫码标识 + visitId 归属校验键），非空；来源：PDA 扫码
     * @return 打卡任务出参（COMPLETED 态，携 taskNo），非空
     * @throws BizException NS-1019（400 标识或就诊号空白）/ NS-1003（400 标识未命中在档患者）/
     *                      NS-1001（404 腕带就诊编码无在区行）/ NS-1004（409 档案冻结）/
     *                      NS-1016（409 就诊不在区或与扫码患者归属不符）
     */
    @Override
    @Transactional
    public NursingTaskVO patrol(PdaPatrolRequest req) {
        // 委托链含 insert 写（Task 7 patrol 面建 PATROL 行），读写事务收口
        String identifier = req.identifier() == null ? "" : req.identifier().trim();
        if (identifier.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "PDA 扫码标识不能为空");
        }
        String visitId = req.visitId() == null ? "" : req.visitId().trim();
        if (visitId.isBlank()) {
            throw new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "PDA 巡视就诊号不能为空");
        }
        // 标识解析归一主档（与患者摘要同口径：FROZEN 拒 / MERGED 收敛 / PAT-1001 → NS-1003）
        long patientId = requireNotBlockedAndConverge(
                isVisitCode(identifier)
                        ? requireInWardByVisit(identifier).getPatientId()
                        : resolveByIdentifier(identifier));
        // 归属校验：就诊在区行必须存在且属于扫码患者（wardId 由在区行装配，不信客户端）
        NursingWardPatient row = findInWardByVisit(visitId);
        if (row == null) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "就诊号不在区，无法核对巡视打卡归属：visitId=" + visitId + "，patientId=" + patientId);
        }
        if (!Objects.equals(row.getPatientId(), patientId)) {
            // 扫错腕带给错人打卡防线：标识与就诊号患者不一致按资源冲突拒收（NS-1016）
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "扫码标识与就诊号患者不一致，禁止巡视打卡：visitId=" + visitId + "，patientId=" + patientId);
        }
        // 委托 Task 7 冻结面：建 PATROL 行直落 COMPLETED（identifier 落 source_ref 留痕，不发事件）
        NursingTaskVO vo = taskService.patrol(patientId, visitId, row.getWardId(), identifier);
        log.info(
                "PDA 巡视打卡完成：taskNo={}，visitId={}，wardId={}，identifierTail={}",
                vo.taskNo(),
                visitId,
                row.getWardId(),
                identifierTail(identifier));
        return vo;
    }

    /** 腕带就诊编码形态判定：14 位定长且 I 头（I 型 visit_id，M04 签发）。 */
    private boolean isVisitCode(String identifier) {
        return identifier.length() == VISIT_CODE_LENGTH && identifier.charAt(0) == VISIT_CODE_PREFIX;
    }

    /**
     * 卡号/证件号形态经 PatientIdentityQuery 盲索引归一主档。
     *
     * @param identifier 标识值明文，非空；仅本方法生命周期内存活，禁入日志
     * @return 归一后主档患者主索引
     * @throws BizException NS-1003（400 标识未登记/挂失/解绑/替换即解析失效——PAT-1001 映射，
     *                      M05 错误码出口唯一，禁透传 PAT- 前缀）
     */
    private long resolveByIdentifier(String identifier) {
        // 形态判定：18 位 X/x 校验位结尾视为证件号，其余按就诊卡号（patient api 词表仅此两值）
        boolean idCardForm =
                identifier.length() == ID_CARD_LENGTH && (identifier.endsWith("X") || identifier.endsWith("x"));
        String identifierType = idCardForm ? IDENTIFIER_TYPE_ID_CARD : IDENTIFIER_TYPE_VISIT_CARD;
        try {
            return identityQuery.resolveActivePatientId(identifierType, identifier);
        } catch (BizException e) {
            // M05 错误码出口唯一：patient 侧解析失败（PAT-1001）统一映射 NS-1003，禁透传 PAT- 前缀
            log.warn(
                    "PDA 标识解析未命中（错误码映射 {} → NS-1003）：identifierTail={}",
                    e.getErrorCode().getCode(),
                    identifierTail(identifier));
            throw new BizException(
                    NursingErrorCode.VISIT_ID_INVALID, HttpStatus.BAD_REQUEST, "标识未命中在档患者（未登记/已挂失/已解绑），禁止 PDA 操作");
        }
    }

    /**
     * 档案拦截与合并收敛（CF-3 归一语义）：FROZEN 拒绝 PDA 操作（NS-1004）；
     * MERGED 按 resolvedPatientId 收敛主档（业务数据一律挂收敛值）。
     *
     * @param patientId 解析所得患者主索引（可为从档 id），非空
     * @return 归一后主档患者主索引（正常档案时与入参相同）
     * @throws BizException NS-1004（409 档案冻结，禁止 PDA 操作）
     */
    private long requireNotBlockedAndConverge(long patientId) {
        PatientContextView ctx = contextResolver.resolve(patientId);
        if (ctx.blocked()) {
            throw new BizException(
                    NursingErrorCode.PATIENT_BLOCKED,
                    HttpStatus.CONFLICT,
                    "患者档案已冻结，禁止 PDA 操作：patientId=" + patientId + "，原因：" + ctx.blockReason());
        }
        return ctx.resolvedPatientId();
    }

    /**
     * 按就诊号定位在区行（未命中定性 NS-1001——腕带就诊编码无在区行时无法解析患者）。
     *
     * @param visitId 住院就诊号，非空；来源：腕带编码解析/打卡归属校验
     * @return 在区行，非空
     * @throws BizException NS-1001（404 在区行不存在或已移出）
     */
    private NursingWardPatient requireInWardByVisit(String visitId) {
        NursingWardPatient row = findInWardByVisit(visitId);
        if (row == null) {
            throw new BizException(
                    NursingErrorCode.WARD_PATIENT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "标识就诊号不在区，无法解析患者：visitId=" + visitId);
        }
        return row;
    }

    /** 按就诊号查在区行（与 WardMetaServiceImpl 在区谓词同源；逻辑删由 @TableLogic 自动过滤）。 */
    private NursingWardPatient findInWardByVisit(String visitId) {
        // 数据库读操作：在区行定位（visit_id + IN_WARD 双谓词）
        return wardPatientMapper.selectOne(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getVisitId, visitId)
                .eq(NursingWardPatient::getStatus, WardPatientStatus.IN_WARD.getCode()));
    }

    /** 按患者主索引查在区行（卡路径摘要定位；同患者极端多行取最近入区，无行返回 null 交调用方降级）。 */
    private NursingWardPatient findInWardByPatient(long patientId) {
        // 数据库读操作：在区行定位（patient_id + IN_WARD 谓词，入区时间倒序取首行）
        List<NursingWardPatient> rows = wardPatientMapper.selectList(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getPatientId, patientId)
                .eq(NursingWardPatient::getStatus, WardPatientStatus.IN_WARD.getCode())
                .orderByDesc(NursingWardPatient::getAdmittedAt));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 标识日志脱敏：仅保留后四位（≤4 位全星回退）——腕带/卡号/证件号明文禁入日志（等保红线）。 */
    private String identifierTail(String identifier) {
        if (identifier == null || identifier.length() <= 4) {
            return "****";
        }
        return "****" + identifier.substring(identifier.length() - 4);
    }
}
