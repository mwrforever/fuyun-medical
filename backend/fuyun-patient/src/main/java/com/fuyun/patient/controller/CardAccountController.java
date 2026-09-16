package com.fuyun.patient.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.CardTxnQuery;
import com.fuyun.patient.entity.CardAccount;
import com.fuyun.patient.service.ICardAccountService;
import com.fuyun.patient.vo.CardAccountVO;
import com.fuyun.patient.vo.CardTxnVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一卡通账户端点（M02 Spec §7：GET /card-accounts/{id}、freeze、close、txns 四端点）。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：事务边界在 service impl 方法级；freeze/close 为状态
 * 变更动作挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截）。记账登记无 HTTP 端点
 * （api {@code CardAccountLedger} 由 M13 进程内调用，拍板 5）。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class CardAccountController {

    private final ICardAccountService cardAccountService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param cardAccountService 一卡通账户服务（台账/状态机/流水），非空
     */
    public CardAccountController(ICardAccountService cardAccountService) {
        this.cardAccountService = cardAccountService;
    }

    /**
     * 账户详情（GET /card-accounts/{id}，余额分值直出）。
     *
     * @param id 账户 id（路径变量）
     * @return 账户出参；200
     * @throws BizException PAT-1013（404 账户不存在）
     */
    @GetMapping("/card-accounts/{id}")
    public CardAccountVO detail(@PathVariable long id) {
        CardAccount account = cardAccountService.getById(id);
        if (account == null) {
            throw new BizException(PatientErrorCode.CARD_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "一卡通账户不存在");
        }
        return Mappers.getMapper(com.fuyun.patient.convert.PatientConverter.class)
                .toVO(account);
    }

    /**
     * 冻结/解冻切换（POST /card-accounts/{id}/freeze，ACTIVE ⇄ FROZEN 双向；挂失联动入口；WRITE 审计）。
     *
     * @param id 账户 id（路径变量）
     * @return 204 无体
     * @throws BizException PAT-1013（404 账户不存在）/ PAT-1014（409 已销户）
     */
    @PostMapping("/card-accounts/{id}/freeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void freeze(@PathVariable long id) {
        cardAccountService.freeze(id);
    }

    /**
     * 销户（POST /card-accounts/{id}/close，余额必须为零；WRITE 审计）。
     *
     * @param id 账户 id（路径变量）
     * @return 204 无体
     * @throws BizException PAT-1013（404）/ PAT-1014（409 已销户）/ PAT-1015（409 余额未结清）
     */
    @PostMapping("/card-accounts/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void close(@PathVariable long id) {
        cardAccountService.close(id);
    }

    /**
     * 账户流水分页（GET /card-accounts/{id}/txns，时序倒序；balance_after 对账锚点直出）。
     *
     * @param id   账户 id（路径变量）
     * @param page 页码（0 基，缺省 0）
     * @param size 单页条数（1-200，越界收敛）
     * @return 流水分页；200
     */
    @GetMapping("/card-accounts/{id}/txns")
    public PageResult<CardTxnVO> txns(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 查询参数对象收敛三要素（A.7 参数对象化）：越界 size 在入口收敛后再下发
        CardTxnQuery query = new CardTxnQuery(id, page, Math.min(Math.max(size, 1), 200));
        return cardAccountService.listTxns(query.accountId(), query.page(), query.size());
    }
}
