package com.fuyun.patient.service.impl;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.dto.CardBindRequest;
import com.fuyun.patient.dto.CardIssueRequest;
import com.fuyun.patient.dto.CardReplaceRequest;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.service.ICardAccountService;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.service.VisitCardService;
import com.fuyun.patient.vo.CardVO;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 就诊卡全生命周期实现（FU-M02-04）：介质本体 = patient_identifier 的 VISIT_CARD 标识行
 * （标识值=卡面号，密文/盲索引随 attach 落库），状态机 ACTIVE→LOST→REPLACED / ACTIVE→DISABLED。
 *
 * <p>事务与事件语义：写方法均在事务内先落库后发布 identifier.changed 应用事件（Task 7 发布点
 * 依赖调用方活动事务，Task 13 AFTER_COMMIT 中继后出 MQ）；挂失账户联动仅吞咽 PAT-1013
 * （一卡通未启用），其余联动异常随事务回滚。补卡「旧卡信息与账户余额按快照转移至新标识」
 * 收口为：账户挂患者不动（余额零迁移动作）、标识重发（新卡号 attach 同档案）。
 *
 * <p>敏感红线：卡号明文禁入日志，日志以患者 id + 卡号 HMAC 摘要形态定位卡片。
 */
@Slf4j
public class VisitCardServiceImpl implements VisitCardService {

    private final IPatientIdentifierService identifierService;

    private final ICardAccountService cardAccountService;

    /** 加密构件（日志侧卡号 HMAC 摘要用；密文落库在 identifierService.attach 内完成） */
    private final PatientFieldCrypto crypto;

    /**
     * 全参构造器（装配归 PatientWebConfig @Import）。
     *
     * @param identifierService  患者标识注册表服务（介质行读写与事件发布），非空
     * @param cardAccountService 一卡通账户服务（发卡开户/挂失冻结联动），非空
     * @param crypto             敏感字段加密构件（日志摘要），非空
     */
    public VisitCardServiceImpl(
            IPatientIdentifierService identifierService,
            ICardAccountService cardAccountService,
            PatientFieldCrypto crypto) {
        this.identifierService = identifierService;
        this.cardAccountService = cardAccountService;
        this.crypto = crypto;
    }

    /**
     * 发卡并绑定档案（VISIT_CARD 标识 ACTIVE；一卡通启用时联动开户）。
     *
     * @param request 发卡请求，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1012（卡号已登记——attach 唯一约束兜底 PAT-1002 转义）
     */
    @Override
    @Transactional
    public CardVO issue(CardIssueRequest request) {
        try {
            // 标识值=卡面号（发卡场景卡号即介质标识值，密文/盲索引在 attach 内部落库）
            Long id = identifierService.attach(
                    request.patientId(), "VISIT_CARD", request.cardNo(), request.cardNo(), false);
            // 一卡通启用时联动开户（默认关闭由 openIfEnabled 内部短路返回 null，发卡侧不感知开关）
            cardAccountService.openIfEnabled(request.patientId());
            return toVO(identifierService.getById(id));
        } catch (BizException e) {
            if (e.getErrorCode() == PatientErrorCode.IDENTIFIER_ALREADY_BOUND) {
                // 卡号撞唯一约束（同卡号重复登记）：按就诊卡语义转 PAT-1012，而非标识占用提示
                throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "卡号已登记");
            }
            throw e;
        }
    }

    /**
     * 绑定既有无主卡到档案（仅未挂接的无主卡可绑定，有主卡一律拒绝）。
     *
     * @param request 绑定请求，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1011（404 卡号无命中）/ PAT-1012（409 卡已挂接档案）
     */
    @Override
    @Transactional
    public CardVO bind(CardBindRequest request) {
        PatientIdentifier card = requireCard(request.cardNo());
        // 无主卡口径收口（审查 Important 2）：仅未挂接（patientId 空）或零值占位的卡可绑定，
        // 有主卡一律拒绝（含挂接同档）——防 LOST/DISABLED 卡经 bind 复活绕过挂失状态机；
        // 0L 为原始值比较规避装箱陷阱；错误码取最近似既有值 PAT-1012（卡状态不允许该操作），
        // LOST 找回合法转移缺失已登记 TASK.md D-14 待 Spec 裁决
        if (card.getPatientId() != null && card.getPatientId() != 0L) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "卡已挂接档案");
        }
        // 无主卡改挂档案（标识值密文与盲索引不动，仅换挂接）
        card.setPatientId(request.patientId());
        card.setStatus("ACTIVE");
        identifierService.updateById(card);
        identifierService.publishChanged(request.patientId(), "VISIT_CARD", request.cardNo(), "BOUND");
        return toVO(card);
    }

    /**
     * 挂失（ACTIVE→LOST 解析立即失效；账户联动冻结；identifier.changed LOST）。
     *
     * @param cardNo 卡面号，非空
     * @throws BizException PAT-1011/PAT-1012
     */
    @Override
    @Transactional
    public void loss(String cardNo) {
        PatientIdentifier card = requireCard(cardNo);
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅正常状态卡片可挂失");
        }
        card.setStatus("LOST");
        card.setUnboundAt(OffsetDateTime.now());
        identifierService.updateById(card);
        identifierService.publishChanged(card.getPatientId(), "VISIT_CARD", cardNo, "LOST");
        // 账户挂失联动（M02 §5 card_account：CLOSED 终态静默跳过；一卡通未启用时 PAT-1013 静默跳过）
        try {
            cardAccountService.freezeByPatient(card.getPatientId());
        } catch (BizException e) {
            if (e.getErrorCode() != PatientErrorCode.CARD_ACCOUNT_NOT_FOUND) {
                throw e;
            }
        }
        // 卡号明文禁入日志：以 HMAC 摘要形态定位卡片
        log.info("就诊卡挂失：patientId={}，卡号摘要={}", card.getPatientId(), crypto.hash(cardNo));
    }

    /**
     * 补卡（旧卡 LOST→REPLACED 终态；新卡发号绑定同档案；账户挂患者自然随档——余额零迁移动作）。
     *
     * @param request 补卡请求（旧卡号+新卡号），非空
     * @return 新卡出参，非空
     * @throws BizException PAT-1011/PAT-1012/PAT-1002
     */
    @Override
    @Transactional
    public CardVO replace(CardReplaceRequest request) {
        PatientIdentifier oldCard = requireCard(request.cardNo());
        if (!"LOST".equals(oldCard.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅挂失卡片可补卡");
        }
        oldCard.setStatus("REPLACED");
        identifierService.updateById(oldCard);
        Long newId = identifierService.attach(
                oldCard.getPatientId(), "VISIT_CARD", request.newCardNo(), request.newCardNo(), false);
        identifierService.publishChanged(oldCard.getPatientId(), "VISIT_CARD", request.newCardNo(), "REPLACED");
        return toVO(identifierService.getById(newId));
    }

    /**
     * 解绑（ACTIVE→DISABLED；标识终态，账户不销户）。
     *
     * @param cardNo 卡面号，非空
     * @throws BizException PAT-1011/PAT-1012
     */
    @Override
    @Transactional
    public void unbind(String cardNo) {
        PatientIdentifier card = requireCard(cardNo);
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅正常状态卡片可解绑");
        }
        card.setStatus("DISABLED");
        card.setUnboundAt(OffsetDateTime.now());
        identifierService.updateById(card);
        identifierService.publishChanged(card.getPatientId(), "VISIT_CARD", cardNo, "UNBOUND");
        // 卡号明文禁入日志：以 HMAC 摘要形态定位卡片
        log.info("就诊卡解绑：patientId={}，卡号摘要={}", card.getPatientId(), crypto.hash(cardNo));
    }

    /**
     * 按卡号取卡出参。
     *
     * @param cardNo 卡面号，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1011
     */
    @Override
    @Transactional(readOnly = true)
    public CardVO getByCardNo(String cardNo) {
        return toVO(requireCard(cardNo));
    }

    /**
     * 卡行存在守卫（按卡面号查任意状态行——挂失/补卡需触达非 ACTIVE 行；
     * ACTIVE 等值解析 resolveActive 仅解析服务用，不在卡状态机链路）。
     *
     * @param cardNo 卡面号，非空
     * @return 标识行，非空
     * @throws BizException PAT-1011（404）卡号无命中
     */
    private PatientIdentifier requireCard(String cardNo) {
        PatientIdentifier card = identifierService.findByCardNo(cardNo);
        if (card == null) {
            throw new BizException(PatientErrorCode.CARD_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊卡不存在");
        }
        return card;
    }

    /**
     * 标识行→卡出参（MapStruct 集中映射；标识值密文/盲索引不映射，与 IdentifierVO 按方法名区分）。
     *
     * @param entity 标识行实体，非空
     * @return 就诊卡出参，非空
     */
    private CardVO toVO(PatientIdentifier entity) {
        return Mappers.getMapper(PatientConverter.class).toCardVO(entity);
    }
}
