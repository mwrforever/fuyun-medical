package com.fuyun.patient.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.entity.CardAccount;
import com.fuyun.patient.vo.CardAccountVO;
import com.fuyun.patient.vo.CardTxnVO;

/**
 * 一卡通账户服务（FU-M02-04 台账侧）：开户（发卡联动）/冻结/销户/流水查询 + api 记账登记实现。
 */
public interface ICardAccountService extends IService<CardAccount>, com.fuyun.patient.api.CardAccountLedger {

    /**
     * 按患者开户（启用开关关闭时返回 null 不建户——发卡联动入口）。
     *
     * @param patientId 患者主索引，非空
     * @return 账户 id；未启用返回 null
     */
    Long openIfEnabled(long patientId);

    /**
     * 冻结账户（ACTIVE/FROZEN 双向；挂失联动由就诊卡服务调用）。
     *
     * @param id 账户 id，非空
     * @throws com.fuyun.common.exception.BizException PAT-1013/PAT-1014（CLOSED 不可冻结）
     */
    void freeze(long id);

    /**
     * 销户（余额必须为零，未结清拒绝——M02 §5 card_account 状态机）。
     *
     * @param id 账户 id，非空
     * @throws com.fuyun.common.exception.BizException PAT-1013/PAT-1014/PAT-1015
     */
    void close(long id);

    /**
     * 账户流水分页（时序倒序）。
     *
     * @param accountId 账户 id，非空；page 0 基；size 1-200
     * @return 流水分页，非空
     */
    PageResult<CardTxnVO> listTxns(long accountId, int page, int size);

    /**
     * 按患者取账户出参。
     *
     * @param patientId 患者主索引，非空
     * @return 账户出参，非空
     * @throws com.fuyun.common.exception.BizException PAT-1013 无账户
     */
    CardAccountVO getByPatient(long patientId);
}
