package com.fuyun.ward.service;

import com.fuyun.ward.vo.VitalBoardVO;

/**
 * 体征看板服务接口（FU-M16-02 编排视图）：床垫 presence 视图锚 + 采集质量注记（anomaly 消费
 * 落 Redis 快照）。落卡权威归 M05（PR-3，GC17⑥——本 PR 仅视图）。实现归 VitalSignBoardServiceImpl
 * （装配归 WardWebConfig）。
 */
public interface IVitalSignBoardService {

    /**
     * 病区体征看板（presence 指标锚 + anomaly 注记清单——deviceId 维度，病区过滤待绑定面闭合）。
     *
     * @param wardId 病区 ID，非空
     * @return 看板视图
     */
    VitalBoardVO board(Long wardId);
}
