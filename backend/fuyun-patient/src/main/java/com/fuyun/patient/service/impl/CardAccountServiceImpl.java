package com.fuyun.patient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.CardTxnRecord;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.entity.CardAccount;
import com.fuyun.patient.entity.CardTxn;
import com.fuyun.patient.mapper.CardAccountMapper;
import com.fuyun.patient.mapper.CardTxnMapper;
import com.fuyun.patient.properties.PatientCardProperties;
import com.fuyun.patient.service.ICardAccountService;
import com.fuyun.patient.vo.CardAccountVO;
import com.fuyun.patient.vo.CardTxnVO;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一卡通账户实现（patient.card_account 主表 + patient.card_txn 只增流水）。
 *
 * <p>串行化台账：余额唯一写点 = 单语句原子 UPDATE（balance = balance ± amount），无自研锁（A.5-2）；
 * 出账后余额为负即拒绝（不超扣，Spec §10「并发充值与消费按串行化台账不超扣」）。
 * api {@code CardAccountLedger} 实现（拍板 5：M13 到位前无调用方的接口位，实现与单测齐备）。
 */
@Slf4j
public class CardAccountServiceImpl extends ServiceImpl<CardAccountMapper, CardAccount> implements ICardAccountService {

    private final CardTxnMapper cardTxnMapper;

    private final PatientCardProperties properties;

    /** 全参构造器（装配归 PatientWebConfig @Import） */
    public CardAccountServiceImpl(CardTxnMapper cardTxnMapper, PatientCardProperties properties) {
        this.cardTxnMapper = cardTxnMapper;
        this.properties = properties;
    }

    /**
     * 按患者开户（一人一账户，uk 兜底；启用开关关闭返回 null）。
     *
     * @param patientId 患者主索引，非空
     * @return 账户 id；未启用 null
     */
    @Override
    @Transactional
    public Long openIfEnabled(long patientId) {
        if (!properties.accountEnabled()) {
            return null;
        }
        CardAccount existing =
                lambdaQuery().eq(CardAccount::getPatientId, patientId).one();
        if (existing != null) {
            return existing.getId();
        }
        CardAccount account = new CardAccount();
        account.setPatientId(patientId);
        account.setBalance(0L);
        account.setStatus("ACTIVE");
        save(account);
        log.info("一卡通开户：patientId={}，accountId={}", patientId, account.getId());
        return account.getId();
    }

    /**
     * 冻结账户（ACTIVE ⇄ FROZEN 双向切换）。
     *
     * <p>并发窗口登记见 TASK.md D-13，M13 接线前收口。
     *
     * @param id 账户 id，非空
     * @throws BizException PAT-1013/PAT-1014
     */
    @Override
    @Transactional
    public void freeze(long id) {
        CardAccount account = requireActiveOrFrozen(id);
        account.setStatus("FROZEN".equals(account.getStatus()) ? "ACTIVE" : "FROZEN");
        updateById(account);
        log.info("一卡通账户冻结状态切换：accountId={}，status={}", id, account.getStatus());
    }

    /**
     * 销户（余额必须为零）。
     *
     * <p>并发窗口登记见 TASK.md D-13，M13 接线前收口。
     *
     * @param id 账户 id，非空
     * @throws BizException PAT-1013/PAT-1014/PAT-1015
     */
    @Override
    @Transactional
    public void close(long id) {
        CardAccount account = requireActiveOrFrozen(id);
        if (account.getBalance() != null && account.getBalance() != 0L) {
            throw new BizException(
                    PatientErrorCode.CARD_ACCOUNT_BALANCE_NOT_SETTLED, HttpStatus.CONFLICT, "账户余额未结清，禁止销户");
        }
        account.setStatus("CLOSED");
        account.setClosedAt(OffsetDateTime.now());
        updateById(account);
        log.info("一卡通销户完成：accountId={}", id);
    }

    /**
     * 记账登记（api CardAccountLedger 实现；调用方事务内同事务记账）。
     *
     * @param record 记账参数，非空
     * @return 流水行 id
     * @throws BizException PAT-1013（账户不存在）/ PAT-1014（非 ACTIVE）/ PAT-1016（余额不足）
     */
    @Override
    @Transactional
    public long record(CardTxnRecord record) {
        // ①金额恒正契约收口（审查 I5）：方向由 txn_type 表达，零/负值会反向入账，一律 400 拒绝
        if (record.amount() <= 0) {
            throw new BizException(PatientErrorCode.CARD_TXN_AMOUNT_INVALID, HttpStatus.BAD_REQUEST, "记账金额必须为正数");
        }
        boolean credit = record.txnType().isCredit();
        long delta = credit ? record.amount() : -record.amount();
        // ②原子记账并回读（审查 I5）：UPDATE ... RETURNING 单语句取记账后余额；null = 无行受影响
        Long balanceAfter = baseMapper.recordBalance(record.accountId(), delta);
        if (balanceAfter == null) {
            CardAccount existing = getById(record.accountId());
            if (existing == null) {
                throw new BizException(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "一卡通账户不存在");
            }
            throw new BizException(
                    PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "账户非 Active 状态，禁止记账");
        }
        if (balanceAfter < 0) {
            // ③出账超扣：抛错回滚整个调用方事务（余额 UPDATE 一并回滚，串行化台账不超扣）
            throw new BizException(PatientErrorCode.CARD_ACCOUNT_INSUFFICIENT_BALANCE, HttpStatus.CONFLICT, "账户余额不足");
        }
        // ④插流水（只增台账；balance_after 取 RETURNING 原子回读值，对账锚点）
        CardTxn txn = new CardTxn();
        txn.setAccountId(record.accountId());
        txn.setTxnType(record.txnType().name());
        txn.setAmount(record.amount());
        txn.setBalanceAfter(balanceAfter);
        txn.setBizRef(record.bizRef());
        cardTxnMapper.insert(txn);
        log.info(
                "一卡通记账：accountId={}，type={}，amount={}分，balanceAfter={}",
                record.accountId(),
                record.txnType(),
                record.amount(),
                balanceAfter);
        return txn.getId();
    }

    /**
     * 账户流水分页。
     *
     * @param accountId 账户 id，非空；page 0 基；size 1-200
     * @return 流水分页，非空
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<CardTxnVO> listTxns(long accountId, int page, int size) {
        Page<CardTxn> result = new Page<>(page + 1, size);
        cardTxnMapper.selectPage(
                result,
                new LambdaQueryWrapper<CardTxn>()
                        .eq(CardTxn::getAccountId, accountId)
                        .orderByDesc(CardTxn::getOccurredAt));
        List<CardTxnVO> rows = result.getRecords().stream()
                .map(row -> Mappers.getMapper(com.fuyun.patient.convert.PatientConverter.class)
                        .toVO(row))
                .toList();
        return PageResult.of(rows, page, size, result.getTotal());
    }

    /**
     * 按患者取账户出参。
     *
     * @param patientId 患者主索引，非空
     * @return 账户出参，非空
     * @throws BizException PAT-1013
     */
    @Override
    @Transactional(readOnly = true)
    public CardAccountVO getByPatient(long patientId) {
        CardAccount account =
                lambdaQuery().eq(CardAccount::getPatientId, patientId).one();
        if (account == null) {
            throw new BizException(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "一卡通账户不存在");
        }
        return Mappers.getMapper(com.fuyun.patient.convert.PatientConverter.class)
                .toVO(account);
    }

    /**
     * 按患者冻结账户（就诊卡挂失联动入口）：无账户 PAT-1013 由调用方决定吞咽、
     * CLOSED 终态静默跳过、其余状态一律置 FROZEN。
     *
     * <p>事务语义（Task 10 修复轮次审查 Important 1）：本方法不带 @Transactional——单行读改写，
     * 须在调用方事务内执行（当前唯一调用方 VisitCardServiceImpl.loss）；若挂 REQUIRED 事务代理，
     * 账户不存在抛 BizException 时内层拦截器会把共享事务标记 rollback-only，调用方吞咽 PAT-1013
     * 后外层提交必抛 UnexpectedRollbackException（默认配置下每次挂失都 500 回滚）。独立调用方须自行开事务。
     *
     * <p>并发窗口登记见 TASK.md D-13，M13 接线前收口。
     *
     * @param patientId 患者主索引，非空
     * @throws BizException PAT-1013（404）无账户
     */
    @Override
    public void freezeByPatient(long patientId) {
        // 一人一账户（uk 兜底），等值 patient_id 单行查
        CardAccount account =
                lambdaQuery().eq(CardAccount::getPatientId, patientId).one();
        if (account == null) {
            throw new BizException(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "一卡通账户不存在");
        }
        if ("CLOSED".equals(account.getStatus())) {
            // 已销户为终态：挂失联动静默跳过，不复活不改写（M02 §5 账户状态机）
            return;
        }
        account.setStatus("FROZEN");
        updateById(account);
        log.info("一卡通账户挂失联动冻结：patientId={}，accountId={}", patientId, account.getId());
    }

    /** 账户存在且非 CLOSED 守卫 */
    private CardAccount requireActiveOrFrozen(long id) {
        CardAccount account = getById(id);
        if (account == null) {
            throw new BizException(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "一卡通账户不存在");
        }
        if ("CLOSED".equals(account.getStatus())) {
            throw new BizException(PatientErrorCode.CARD_ACCOUNT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "账户已销户");
        }
        return account;
    }
}
