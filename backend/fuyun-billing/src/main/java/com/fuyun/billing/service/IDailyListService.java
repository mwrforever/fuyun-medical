package com.fuyun.billing.service;

import com.fuyun.billing.vo.DailyListVO;
import java.time.LocalDate;

/**
 * 一日清单服务（billing.fee_record 只读聚合，FU-M13-04）：按日费用明细+大类汇总+总额，
 * 供 M04 工作站、床旁屏、患者端取数展示。三层勾稽（Spec §9 第三层）依赖明细查询与
 * XML 聚合查询同事务、同谓词人群（visit_id+billing_date）。
 */
public interface IDailyListService {

    /**
     * 按日查一日清单（GET /daily-lists?visitId=&date= 消费；纯读出口）。
     *
     * @param visitId CF-3 住院就诊号，非空；来源：工作站/床旁屏/患者端路由参数
     * @param date    清单计费日，非空；来源：前端日期选择
     * @return 清单出参（无费用日出空明细/空大类、总额 0，不报错），非空
     * @throws com.fuyun.common.exception.BizException BILL-1013（400 就诊号非住院形态）
     */
    DailyListVO dailyList(String visitId, LocalDate date);
}
