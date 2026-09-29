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
import com.fuyun.patient.service.IVisitCardService;
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
 * <p>并发收口（EX-25）：bind/loss/replace/unbind 四写方法以旧状态谓词 CAS 条件更新抢锚
 * （{@link IPatientIdentifierService} cas 族，fuyun-billing RefundServiceImpl BUG-10 同款时序）——
 * 并发交错恰一赢；输家 0 行重读定性拒（行消失 404 PAT-1011 / 已被并发处理 409 PAT-1012），
 * 禁以过期快照整行覆写（双 bind 互相覆盖丢单链的共根源）。
 *
 * <p>敏感红线：卡号明文禁入日志，日志以患者 id + 卡号 HMAC 摘要形态定位卡片。
 */
@Slf4j
public class VisitCardServiceImpl implements IVisitCardService {

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
     * <p>并发收口（EX-25）：所有权守卫通过后以「未挂接」谓词 CAS 抢锚（{@link
     * IPatientIdentifierService#casBindUnowned}）——并发双 bind 同卡恰一赢，输家 0 行重读定性拒。
     *
     * @param request 绑定请求，非空
     * @return 卡出参，非空
     * @throws BizException PAT-1011（404 卡号无命中/输家重读行消失）/ PAT-1012（409 卡已挂接档案
     *                      或已被并发绑定）
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
        // EX-25 无主卡绑定 CAS 抢锚（读后写收口，RefundServiceImpl BUG-10 同款时序）：谓词钉死
        // 未挂接——并发双 bind 同卡恰一赢，输家 0 行重读定性拒，后提交者不得覆写先到者挂接
        if (identifierService.casBindUnowned(card.getId(), request.patientId()) != 1) {
            throw cardConcurrentConflict(request.cardNo());
        }
        // 无主卡改挂档案（标识值密文与盲索引不动，仅换挂接；CAS 已原子落库，此处同步内存镜像）
        card.setPatientId(request.patientId());
        card.setStatus("ACTIVE");
        identifierService.updateById(card);
        identifierService.publishChanged(request.patientId(), "VISIT_CARD", request.cardNo(), "BOUND");
        // 卡号明文禁入日志：以 HMAC 摘要形态定位卡片（数据库写操作必须 info，对齐 loss/unbind 留痕）
        log.info("就诊卡绑定：patientId={}，卡号摘要={}", request.patientId(), crypto.hash(request.cardNo()));
        return toVO(card);
    }

    /**
     * 挂失（ACTIVE→LOST 解析立即失效；账户联动冻结；identifier.changed LOST）。
     *
     * <p>并发收口（EX-25）：ACTIVE 守卫通过后以 ACTIVE 谓词 CAS 抢锚（{@link
     * IPatientIdentifierService#casMarkLost}）——与解绑/补卡并发交错时输家 0 行重读定性拒。
     *
     * @param cardNo 卡面号，非空
     * @throws BizException PAT-1011/PAT-1012（含并发输家 CAS 0 行重读定性）
     */
    @Override
    @Transactional
    public void loss(String cardNo) {
        PatientIdentifier card = requireCard(cardNo);
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅正常状态卡片可挂失");
        }
        // EX-25 挂失 CAS 抢锚（读后写收口）：谓词钉死 ACTIVE——ACTIVE→LOST 单次迁移，
        // 后提交者不得以过期快照整行覆写先到者的终态迁移
        if (identifierService.casMarkLost(card.getId()) != 1) {
            throw cardConcurrentConflict(cardNo);
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
     * <p>并发收口（EX-25）：LOST 守卫通过后以 LOST 谓词 CAS 抢锚（{@link
     * IPatientIdentifierService#casRetireReplaced}）——并发双补卡恰一赢，输家 0 行重读定性拒；
     * 新卡发号严格后置于锚抢占成功（防重复发号单链）。
     *
     * @param request 补卡请求（旧卡号+新卡号），非空
     * @return 新卡出参，非空
     * @throws BizException PAT-1011/PAT-1012/PAT-1002（含并发输家 CAS 0 行重读定性）
     */
    @Override
    @Transactional
    public CardVO replace(CardReplaceRequest request) {
        PatientIdentifier oldCard = requireCard(request.cardNo());
        if (!"LOST".equals(oldCard.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅挂失卡片可补卡");
        }
        // EX-25 补卡旧卡退役 CAS 抢锚（读后写收口）：谓词钉死 LOST——并发双补卡恰一赢，
        // 输家 0 行重读定性拒且不得发新卡号（重复发号=同档案双活介质）
        if (identifierService.casRetireReplaced(oldCard.getId()) != 1) {
            throw cardConcurrentConflict(request.cardNo());
        }
        oldCard.setStatus("REPLACED");
        identifierService.updateById(oldCard);
        Long newId = identifierService.attach(
                oldCard.getPatientId(), "VISIT_CARD", request.newCardNo(), request.newCardNo(), false);
        identifierService.publishChanged(oldCard.getPatientId(), "VISIT_CARD", request.newCardNo(), "REPLACED");
        // 卡号明文禁入日志：新旧双卡各以 HMAC 摘要定位（数据库写操作必须 info，对齐 loss/unbind 留痕）
        log.info(
                "就诊卡补卡：patientId={}，旧卡号摘要={}，新卡号摘要={}",
                oldCard.getPatientId(),
                crypto.hash(request.cardNo()),
                crypto.hash(request.newCardNo()));
        return toVO(identifierService.getById(newId));
    }

    /**
     * 解绑（ACTIVE→DISABLED；标识终态，账户不销户）。
     *
     * <p>并发收口（EX-25）：ACTIVE 守卫通过后以 ACTIVE 谓词 CAS 抢锚（{@link
     * IPatientIdentifierService#casDisable}）——与挂失/补卡并发交错时输家 0 行重读定性拒。
     *
     * @param cardNo 卡面号，非空
     * @throws BizException PAT-1011/PAT-1012（含并发输家 CAS 0 行重读定性）
     */
    @Override
    @Transactional
    public void unbind(String cardNo) {
        PatientIdentifier card = requireCard(cardNo);
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅正常状态卡片可解绑");
        }
        // EX-25 解绑 CAS 抢锚（读后写收口）：谓词钉死 ACTIVE——ACTIVE→DISABLED 单次迁移，
        // 后提交者不得以过期快照整行覆写先到者的终态迁移
        if (identifierService.casDisable(card.getId()) != 1) {
            throw cardConcurrentConflict(cardNo);
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
     * 卡状态 CAS 0 行命中的重读定性（EX-25，RefundServiceImpl 并发冲突定性同语义）：重读最新行——
     * 行已消失归 404（PAT-1011，与入口查询语义一致禁漂移）；仍在表即状态/挂接已被并发事务迁移，
     * 「已被并发处理」显式拒 PAT-1012——禁盲目重试，更禁以过期快照整行覆写（双 bind 互相覆盖
     * 丢单链的共根源）。卡号明文禁入日志：以 HMAC 摘要定位。
     *
     * @param cardNo 卡面号，非空；来源：各写入口请求参数（重读定位键）
     * @return 待抛业务异常（404 卡无命中 / 409 并发冲突），非空；调用方恒 throw
     */
    private BizException cardConcurrentConflict(String cardNo) {
        // 数据库读操作：CAS 败北后重读最新行定性（不可重试——状态机单次迁移，重试无正确性收益）
        PatientIdentifier latest = identifierService.findByCardNo(cardNo);
        if (latest == null) {
            return new BizException(PatientErrorCode.CARD_NOT_FOUND, HttpStatus.NOT_FOUND, "就诊卡不存在");
        }
        log.warn("就诊卡操作并发冲突（CAS 0 行，已被并发处理）：卡号摘要={}，当前状态={}", crypto.hash(cardNo), latest.getStatus());
        return new BizException(
                PatientErrorCode.CARD_STATE_NOT_ALLOWED,
                HttpStatus.CONFLICT,
                "卡片状态已被并发处理，当前操作冲突：" + latest.getStatus());
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
