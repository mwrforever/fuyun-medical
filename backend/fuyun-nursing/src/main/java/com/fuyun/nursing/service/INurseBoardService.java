package com.fuyun.nursing.service;

import com.fuyun.nursing.vo.NurseBoardVO;

/**
 * 护士站大屏快照服务（FU-M05-08，Task 11）：board 四段聚合只读面（床位总览墙/任务逾期清单/
 * 出入院动态/危急值空段），REST 兜底唯一入口（GET /api/v1/nursing/board/{wardId}）；WS 增量
 * 推送面归 NurseBoardPushListener（本服务不触推送）。
 */
public interface INurseBoardService {

    /**
     * 病区大屏快照聚合（四段组装 + Redis TTL 5s read-through）。
     *
     * <p>执行流程：缓存命中直返（读失败/JSON 损坏降级直算，缓存面缺席不阻断聚合主链——
     * fy:iot:snapshot:dashboard 同款形态）；穿透则直算四段并回写缓存。四段口径见
     * {@link NurseBoardVO} 类注释（危急值段固定空数组——M07 缺位降级明示）。
     *
     * @param wardId 病区编码，非空；来源：REST 路径参数（大屏书签 query.wardId）
     * @return 大屏快照（generatedAt=北京钟面当前时点），非空
     * @throws com.fuyun.common.exception.BizException NS-1019（400 wardId 缺失或空白）
     */
    NurseBoardVO board(String wardId);
}
