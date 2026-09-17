package com.fuyun.billing.vo;

import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.record.DailyListRow;
import java.time.LocalDate;
import java.util.List;

/**
 * 一日清单出参（GET /daily-lists?visitId=&date= 出网载体，供 M04 工作站/床旁屏/患者端取数展示；
 * 组件清单为 Task 18 IT 与 Task 19 前端唯一依据，禁改名改序）：三层勾稽载体——明细合计
 * （items 求和）=大类汇总合计（categories 求和）=总额（totalAmount），Spec §9 第三层。
 *
 * @param visitId     CF-3 住院就诊号
 * @param date        清单计费日
 * @param items       当日费用明细（行序 id 升序稳定，组件同源 FeeRecordVO）
 * @param categories  费用大类汇总（序=XML ORDER BY fee_category_snapshot 唯一序）
 * @param totalAmount 当日总额（分，=明细合计，两层勾稽基准）
 */
public record DailyListVO(
        String visitId, LocalDate date, List<FeeRecordVO> items, List<CategorySummary> categories, Long totalAmount) {

    /**
     * 费用大类汇总行（组件冻结：feeCategory=费用行 feeCategorySnapshot 快照同源换名，禁改名）。
     *
     * @param feeCategory 清单费用大类
     * @param amount      该大类当日金额合计（分，SUM 聚合值）
     */
    public record CategorySummary(String feeCategory, Long amount) {}

    /**
     * 组装静态工厂（service 只读事务内两查询同人群装配，controller 出网边界专用）。
     *
     * @param visitId CF-3 住院就诊号，非空
     * @param date    清单计费日，非空
     * @param fees    当日费用明细实体列表，非空（可空列表=空日清单）；来源：wrapper 明细查询
     * @param rows    大类聚合投影列表，非空（可空列表=空日清单）；来源：dailyListSummary 聚合查询
     * @return 出参 VO，非空；totalAmount 取明细合计（与聚合查询同人群同事务，三层勾稽恒成立）
     */
    public static DailyListVO of(String visitId, LocalDate date, List<FeeRecord> fees, List<DailyListRow> rows) {
        List<FeeRecordVO> items = fees.stream().map(FeeRecordVO::from).toList();
        List<CategorySummary> categories = rows.stream()
                .map(row -> new CategorySummary(row.feeCategorySnapshot(), row.amount()))
                .toList();
        return new DailyListVO(
                visitId,
                date,
                items,
                categories,
                items.stream().mapToLong(FeeRecordVO::amount).sum());
    }
}
