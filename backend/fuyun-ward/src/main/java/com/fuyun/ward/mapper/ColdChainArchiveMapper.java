package com.fuyun.ward.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.ward.entity.ColdChainArchiveEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 冷链档案 mapper：档案 CRUD 走 BaseMapper 通道（archive_no 单查/用途过滤分页/逻辑删，
 * uk_cold_chain_archive_no 部分唯一索引兜底并发建档窗口）。
 */
@Mapper
public interface ColdChainArchiveMapper extends BaseMapper<ColdChainArchiveEntity> {}
