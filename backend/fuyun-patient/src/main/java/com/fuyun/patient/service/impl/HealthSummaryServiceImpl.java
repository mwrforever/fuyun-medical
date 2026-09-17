package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientHealthSummaryUpdatedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.dto.HealthItemCorrectRequest;
import com.fuyun.patient.dto.HealthItemCreateRequest;
import com.fuyun.patient.entity.HealthItem;
import com.fuyun.patient.entity.HealthSummary;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.mapper.HealthItemMapper;
import com.fuyun.patient.mapper.HealthSummaryMapper;
import com.fuyun.patient.service.IHealthSummaryService;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.vo.HealthItemVO;
import com.fuyun.patient.vo.HealthSummaryVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 健康档案服务实现（FU-M02-05）：摘要懒创建 + 明细维护 + 纠错留痕 + api 过敏校验接口位。
 *
 * <p>纠错不改原记录（Spec §4）：旧行置 CORRECTED 保留、新行 correct_of_item_id 回链，再纠错以最新
 * 纠错为准（不设状态守卫）；summary_updated_at 为事件时间锚点，新增/纠错后刷新并发布
 * patient.health-summary.updated（载荷 hasAllergy/allergyCodes 聚合自 ACTIVE 过敏项，非敏感）。
 *
 * <p>事务与事件语义：写方法事务边界在本类方法级（controller 禁事务）；事件在事务内发 Spring 应用
 * 事件，发布器 AFTER_COMMIT 转 MQ（A.4.2-7）。聚合行懒创建：查询路径无行不落库，首笔写入
 * （touchSummary）时才建行。
 */
@Slf4j
public class HealthSummaryServiceImpl extends ServiceImpl<HealthSummaryMapper, HealthSummary>
        implements IHealthSummaryService {

    /** 状态词表：明细有效（HealthItemStatus） */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态词表：已纠错（纠错置旧行留痕） */
    private static final String STATUS_CORRECTED = "CORRECTED";

    /** 项类型词表：过敏（AllergyChecker 校验对象） */
    private static final String ITEM_TYPE_ALLERGY = "ALLERGY";

    private final IPatientService patientService;

    private final HealthItemMapper healthItemMapper;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param patientService  患者主表服务（档案存在守卫），非空
     * @param healthItemMapper 明细行读写（新增/纠错/过敏校验），非空
     * @param eventPublisher  应用事件发布器（health-summary.updated 出口），非空
     */
    public HealthSummaryServiceImpl(
            IPatientService patientService,
            HealthItemMapper healthItemMapper,
            ApplicationEventPublisher eventPublisher) {
        this.patientService = patientService;
        this.healthItemMapper = healthItemMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 取患者健康档案摘要（懒创建：无聚合行返回空摘要不落库）。
     *
     * @param patientId 患者主索引，非空
     * @return 摘要出参（items 含全部状态项供留痕展示），非空
     * @throws BizException PAT-1001（404）档案不存在
     */
    @Override
    @Transactional(readOnly = true)
    public HealthSummaryVO getSummary(long patientId) {
        requirePatient(patientId);
        // 懒创建：无聚合行以仅含 patientId 的内存实体组装空摘要（其余字段 null），查询路径不落库
        HealthSummary summary =
                lambdaQuery().eq(HealthSummary::getPatientId, patientId).one();
        if (summary == null) {
            summary = new HealthSummary();
            summary.setPatientId(patientId);
        }
        // items 查全量（含 CORRECTED 留痕行），按录入时序展示
        List<HealthItemVO> items = healthItemMapper
                .selectList(new LambdaQueryWrapper<HealthItem>()
                        .eq(HealthItem::getPatientId, patientId)
                        .orderByAsc(HealthItem::getId))
                .stream()
                .map(this::toItemVO)
                .toList();
        return withItems(Mappers.getMapper(PatientConverter.class).toVO(summary), items);
    }

    /**
     * 新增健康档案项（落 ACTIVE 首录行 + 刷新锚点 + health-summary.updated 事件）。
     *
     * @param patientId 患者主索引，非空
     * @param request   新增请求（@Valid 已过校验），非空
     * @return 明细出参，非空
     * @throws BizException PAT-1001（404）档案不存在
     */
    @Override
    @Transactional
    public HealthItemVO addItem(long patientId, HealthItemCreateRequest request) {
        requirePatient(patientId);
        HealthItem item = new HealthItem();
        item.setPatientId(patientId);
        item.setItemType(request.itemType());
        item.setItemCode(request.itemCode());
        item.setItemName(request.itemName());
        item.setSeverity(request.severity());
        item.setOnsetDate(parseOnsetDate(request.onsetDate()));
        // 首录行恒为有效态且无纠错回链（纠错链仅由 correct 建立）
        item.setStatus(STATUS_ACTIVE);
        item.setSource(request.source());
        item.setNote(request.note());
        // 数据库写操作：明细落库（主键 ASSIGN_ID 插入期回填）
        healthItemMapper.insert(item);
        log.info(
                "健康档案项新增：patientId={}，itemId={}，itemType={}，itemName={}",
                patientId,
                item.getId(),
                item.getItemType(),
                item.getItemName());
        touchSummary(patientId);
        publishUpdated(patientId);
        return toItemVO(item);
    }

    /**
     * 纠错（旧行置 CORRECTED 保留 + 新行 correct_of_item_id 回链；事件同 addItem）。
     *
     * <p>已纠错行再纠错允许（以最新纠错为准），不设状态守卫（PAT-1010 语义复用禁止）。
     *
     * @param itemId  被纠错明细 id，非空
     * @param request 纠错请求（@Valid 已过校验），非空
     * @return 新明细出参，非空
     * @throws BizException PAT-1017（404）明细不存在
     */
    @Override
    @Transactional
    public HealthItemVO correct(long itemId, HealthItemCorrectRequest request) {
        HealthItem old = healthItemMapper.selectById(itemId);
        if (old == null) {
            throw new BizException(PatientErrorCode.HEALTH_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND, "健康档案项不存在");
        }
        // 纠错不改原记录：旧行仅在原行上置 CORRECTED 留痕，纠正内容全部落新行
        old.setStatus(STATUS_CORRECTED);
        // 数据库写操作：旧行状态置 CORRECTED
        healthItemMapper.updateById(old);
        HealthItem fresh = new HealthItem();
        // 归属与类型沿旧行（纠错请求无 patientId/itemType/source 字段）
        fresh.setPatientId(old.getPatientId());
        fresh.setItemType(old.getItemType());
        fresh.setItemCode(request.itemCode());
        fresh.setItemName(request.itemName());
        fresh.setSeverity(request.severity());
        fresh.setOnsetDate(parseOnsetDate(request.onsetDate()));
        fresh.setStatus(STATUS_ACTIVE);
        fresh.setSource(old.getSource());
        // 纠错说明落新行 note，全程留痕
        fresh.setNote(request.note());
        // 回链指向被纠错原行（纠错链展示依据）
        fresh.setCorrectOfItemId(old.getId());
        // 数据库写操作：纠错重录行落库（主键 ASSIGN_ID 插入期回填）
        healthItemMapper.insert(fresh);
        log.info("健康档案项纠错：oldItemId={}，newItemId={}，patientId={}", old.getId(), fresh.getId(), old.getPatientId());
        touchSummary(old.getPatientId());
        publishUpdated(old.getPatientId());
        return toItemVO(fresh);
    }

    /**
     * 取患者当前有效过敏项清单（api AllergyChecker 实现：M06 审方/开单嵌查入口）。
     *
     * @param patientId 患者主索引，非空
     * @return 有效过敏项清单（ACTIVE + ALLERGY；无过敏为空清单非 null）
     */
    @Override
    @Transactional(readOnly = true)
    public List<AllergyItem> listActiveAllergies(long patientId) {
        return listActiveAllergyRows(patientId).stream()
                .map(item -> new AllergyItem(item.getId(), item.getItemCode(), item.getItemName(), item.getSeverity()))
                .toList();
    }

    /**
     * 摘要锚点刷新：无聚合行懒创建落库，summary_updated_at 刷为当前时刻（事件载荷时间锚点）。
     *
     * @param patientId 患者主索引，非空
     */
    private void touchSummary(long patientId) {
        HealthSummary summary =
                lambdaQuery().eq(HealthSummary::getPatientId, patientId).one();
        if (summary == null) {
            // 懒创建落库：首笔明细触发建聚合行（一档一份，uk 兜底）
            summary = new HealthSummary();
            summary.setPatientId(patientId);
            save(summary);
        }
        summary.setSummaryUpdatedAt(OffsetDateTime.now());
        // 数据库写操作：聚合行事件时间锚点刷新
        updateById(summary);
    }

    /**
     * 发布 health-summary.updated（载荷非敏感：hasAllergy/allergyCodes 聚合自 ACTIVE 过敏项）。
     *
     * @param patientId 患者主索引，非空
     */
    private void publishUpdated(long patientId) {
        List<HealthItem> activeAllergies = listActiveAllergyRows(patientId);
        // 无 code 的手工项不入清单，仅以 hasAllergy 表达（payload 契约口径）
        List<String> allergyCodes = activeAllergies.stream()
                .map(HealthItem::getItemCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        // 消息发送：事务内发应用事件，发布器 AFTER_COMMIT 转 MQ
        eventPublisher.publishEvent(new PatientDomainEvent(
                PatientMessagingConstants.EVENT_HEALTH_SUMMARY_UPDATED,
                new PatientHealthSummaryUpdatedPayload(patientId, !activeAllergies.isEmpty(), allergyCodes)));
        log.info(
                "健康档案变更事件发布：patientId={}，hasAllergy={}，allergyCode 数={}",
                patientId,
                !activeAllergies.isEmpty(),
                allergyCodes.size());
    }

    /**
     * 查 ACTIVE 过敏明细行（listActiveAllergies 与事件聚合共用的单一查询口径）。
     *
     * @param patientId 患者主索引，非空
     * @return ACTIVE + ALLERGY 明细行清单（可能为空，非 null）
     */
    private List<HealthItem> listActiveAllergyRows(long patientId) {
        return healthItemMapper.selectList(new LambdaQueryWrapper<HealthItem>()
                .eq(HealthItem::getPatientId, patientId)
                .eq(HealthItem::getItemType, ITEM_TYPE_ALLERGY)
                .eq(HealthItem::getStatus, STATUS_ACTIVE));
    }

    /**
     * 档案存在守卫。
     *
     * @param patientId 患者主索引，非空
     * @throws BizException PAT-1001（404）档案不存在
     */
    private void requirePatient(long patientId) {
        if (patientService.getById(patientId) == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");
        }
    }

    /**
     * 发生日期解析（DTO 契约：ISO 文本可空；空文本按无发生日期处理）。
     *
     * <p>非空非法 ISO 文本（如 2026-13-40）抛 DateTimeParseException 走全局 500——契约缺口，
     * 错误码与校验策略待裁决（TASK.md D-15）。
     *
     * @param onsetDate ISO-8601 日期文本，可空
     * @return 解析后日期；空文本返回 null
     */
    private LocalDate parseOnsetDate(String onsetDate) {
        return onsetDate == null || onsetDate.isBlank() ? null : LocalDate.parse(onsetDate);
    }

    /**
     * 明细实体→出参（MapStruct 集中映射，纠错链 correctOfItemId 直出）。
     *
     * @param entity 明细行实体，非空
     * @return 明细出参，非空
     */
    private HealthItemVO toItemVO(HealthItem entity) {
        return Mappers.getMapper(PatientConverter.class).toVO(entity);
    }

    /**
     * 摘要出参回填 items 清单（record 不可变：聚合行直映后重建补清单）。
     *
     * @param base  聚合行直映出的摘要出参（items 为 null），非空
     * @param items 明细出参清单（可为空清单），非 null
     * @return 补齐 items 的摘要出参，非空
     */
    private HealthSummaryVO withItems(HealthSummaryVO base, List<HealthItemVO> items) {
        return new HealthSummaryVO(
                base.patientId(),
                base.bloodType(),
                base.rhType(),
                base.pastHistory(),
                base.familyHistory(),
                base.summaryUpdatedAt(),
                items);
    }
}
