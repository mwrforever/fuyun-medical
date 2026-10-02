package com.fuyun.nursing.controller;

import com.fuyun.nursing.service.INurseBoardService;
import com.fuyun.nursing.vo.NurseBoardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护士站大屏快照端点（/api/v1/nursing，Task 11 / FU-M05-08）：REST 快照兜底（首屏与 WS 断连
 * 10s 轮询兜底——≤2s 刷新面归 /topic/nursing/board/{wardId} WS 主通道）。controller 禁业务
 * 逻辑与事务（A.1-8）；查询面不加审计读注记（W-47 口径：board 归 GET 快照面统一收口，
 * InfusionController 在途清单同款）。
 */
@Tag(name = "M05 护士站大屏", description = "病区大屏四段快照（床位墙/任务逾期/出入院动态/危急值占位）")
@RestController
@RequiredArgsConstructor
@Validated
public class BoardController {

    private final INurseBoardService boardService;

    /**
     * 病区大屏快照（四段聚合 + Redis TTL 5s read-through）：床位总览墙（投影行+护理级别+责任
     * 护士+风险标记）、任务逾期清单、近 24h 出入院动态、危急值段（固定空数组——M07 缺位降级
     * 明示）。
     *
     * @param wardId 病区编码（路径参数，大屏书签 query.wardId 承载），非空
     * @return 大屏快照（generatedAt=北京钟面），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 wardId 空白）
     */
    @Operation(summary = "病区大屏四段快照（REST 兜底，WS 主通道增量）", operationId = "getNursingBoard")
    @GetMapping("/api/v1/nursing/board/{wardId}")
    public NurseBoardVO board(@PathVariable("wardId") @NotBlank String wardId) {
        return boardService.board(wardId);
    }
}
