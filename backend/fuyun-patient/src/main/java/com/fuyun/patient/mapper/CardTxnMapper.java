package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.CardTxn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 一卡通流水 mapper：只增台账（V503 口径），仅 INSERT/SELECT 能力（BaseMapper），
 * 禁任何 UPDATE/DELETE 调用（台账红线）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface CardTxnMapper extends BaseMapper<CardTxn> {}
