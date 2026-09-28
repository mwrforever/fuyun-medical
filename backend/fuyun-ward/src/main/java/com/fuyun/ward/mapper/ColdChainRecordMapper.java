package com.fuyun.ward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.ward.entity.ColdChainRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 冷链记录 mapper：记录登记/列表/巡检 overdue 聚合走 BaseMapper wrapper 通道
 * （idx_cold_chain_record_archive_type 准入；ALARM_HANDLE 校验与归档事件发布归服务层）。
 */
@Mapper
public interface ColdChainRecordMapper extends BaseMapper<ColdChainRecordEntity> {}
