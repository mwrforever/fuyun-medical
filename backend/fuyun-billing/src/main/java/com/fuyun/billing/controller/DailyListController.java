package com.fuyun.billing.controller;

import com.fuyun.billing.service.IDailyListService;
import com.fuyun.billing.vo.DailyListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一日清单端点（FU-M13-04）：按日费用明细+大类汇总+总额查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：勾稽装配/就诊号守卫全归服务层（纯读端点）。
 */
@Tag(name = "billing-daily-lists", description = "M13 一日清单（住院按日费用明细与汇总）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class DailyListController {

    private final IDailyListService dailyListService;

    /**
     * 构造器注入（A.1-7），装配归 fuyun-app 侧 config @Import（Task 16 交付）。
     *
     * @param dailyListService 一日清单服务，非空
     */
    public DailyListController(IDailyListService dailyListService) {
        this.dailyListService = dailyListService;
    }

    /**
     * 按日查一日清单（GET /daily-lists?visitId=&date=，三层勾稽载体；纯读）。
     *
     * @param visitId CF-3 住院就诊号（查询参数）；来源：M04 工作站/床旁屏/患者端路由参数
     * @param date    清单计费日（ISO yyyy-MM-dd）；来源：前端日期选择
     * @return 清单出参（无费用日出空明细/空大类、总额 0）；200
     * @throws com.fuyun.common.exception.BizException BILL-1013（400 就诊号非住院形态）
     */
    @Operation(summary = "按日查一日清单（明细+大类汇总+总额三层勾稽）", operationId = "getDailyList")
    @GetMapping("/daily-lists")
    public DailyListVO dailyList(
            @RequestParam("visitId") String visitId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dailyListService.dailyList(visitId, date);
    }
}
