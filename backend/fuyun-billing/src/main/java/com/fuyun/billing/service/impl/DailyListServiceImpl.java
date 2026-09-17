package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.record.DailyListRow;
import com.fuyun.billing.service.IDailyListService;
import com.fuyun.billing.vo.DailyListVO;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一日清单服务（billing.fee_record 只读聚合，FU-M13-04）：按日费用明细+大类汇总+总额，
 * 供 M04 工作站、床旁屏、患者端取数展示。
 *
 * <p>勾稽前提：明细查询（wrapper 谓词级）与 XML 聚合查询（GROUP BY fee_category_snapshot）
 * 同一 readOnly 事务、同一谓词人群（visit_id+billing_date）——任一侧口径漂移即三层勾稽失真，
 * 两侧谓词由单测 SQL 守卫钉死。清单面向患者展示，按当日全部留痕行出（与费用查询同口径，
 * 不做状态过滤）。
 */
public class DailyListServiceImpl implements IDailyListService {

    private final FeeRecordMapper feeRecordMapper;

    /**
     * 全参构造器（装配归 fuyun-app 侧 config @Import——Task 16 交付）。
     *
     * @param feeRecordMapper 费用行 mapper（明细查询+XML 聚合查询双通道），非空
     */
    public DailyListServiceImpl(FeeRecordMapper feeRecordMapper) {
        this.feeRecordMapper = feeRecordMapper;
    }

    /**
     * 按日查一日清单（纯读出口）。
     *
     * <p>执行流程：就诊号守卫 → 明细查询（visit_id+billing_date 等值、id 升序稳定行序）→
     * XML 聚合查询（GROUP BY+SUM+ORDER BY 大类唯一序）→ 同人群装配三层勾稽载体。
     *
     * @param visitId CF-3 住院就诊号，非空
     * @param date    清单计费日，非空
     * @return 清单出参（无费用日出空明细/空大类、总额 0），非空
     * @throws BizException BILL-1013（400 就诊号非住院形态）
     */
    @Override
    @Transactional(readOnly = true)
    public DailyListVO dailyList(String visitId, LocalDate date) {
        requireInpatientVisitId(visitId);
        // 数据库读操作：当日费用明细（行序 id 升序——A.4.3-17 无 ORDER BY 的行序不可依赖）
        List<FeeRecord> fees = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .eq(FeeRecord::getVisitId, visitId)
                .eq(FeeRecord::getBillingDate, date)
                .orderByAsc(FeeRecord::getId));
        // 数据库读操作：大类聚合（XML：GROUP BY fee_category_snapshot + SUM + ORDER BY 唯一序）
        List<DailyListRow> rows = feeRecordMapper.dailyListSummary(visitId, date);
        return DailyListVO.of(visitId, date, fees, rows);
    }

    /**
     * 住院就诊号守卫：一日清单为住院 FU-M13-04 业务，门诊 O 前缀/非法结构显式拒。
     *
     * @param visitId 就诊号原文，允许为空（空即拒）
     * @throws BizException BILL-1013（400 结构非法或非住院前缀）
     */
    private static void requireInpatientVisitId(String visitId) {
        if (!VisitIdValidator.isValid(visitId) || !visitId.startsWith(VisitIdValidator.TYPE_INPATIENT)) {
            throw new BizException(
                    BillingErrorCode.VISIT_ID_MALFORMED,
                    HttpStatus.BAD_REQUEST,
                    "一日清单仅受理住院就诊号（I 前缀 14 位定长）：" + visitId);
        }
    }
}
