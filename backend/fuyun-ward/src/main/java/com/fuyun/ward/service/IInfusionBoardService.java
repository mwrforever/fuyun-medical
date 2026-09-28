package com.fuyun.ward.service;

import com.fuyun.ward.vo.InfusionBoardVO;
import com.fuyun.ward.vo.InfusionHistoryVO;

/**
 * 输液看板服务接口（FU-M16 输液监控编排）：病区维度余量/滴速聚合与告警档位映射 + 设备维度
 * 输液历史追溯（遥测曲线面）。实现归 InfusionBoardServiceImpl（装配归 WardWebConfig）。
 */
public interface IInfusionBoardService {

    /**
     * 病区输液看板（iot 遥测最新值聚合 + 三档告警映射；5ml 红档呼叫落行走事件消费链）。
     *
     * @param wardId 病区 ID，非空
     * @return 看板视图（设备行清单）
     */
    InfusionBoardVO board(Long wardId);

    /**
     * 输液历史追溯（余量/滴速双曲线；告警聚合缺位注记——iot/api 无告警查询端口实测结论）。
     *
     * @param deviceId IoTDA 设备标识，非空
     * @return 历史视图
     */
    InfusionHistoryVO history(String deviceId);
}
