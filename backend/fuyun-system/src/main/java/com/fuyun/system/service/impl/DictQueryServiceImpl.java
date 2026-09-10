package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.mapper.DictItemMapper;
import com.fuyun.system.mapper.DictTypeMapper;
import com.fuyun.system.mapper.DictVersionMapper;
import com.fuyun.system.service.IDictQueryService;
import com.fuyun.system.vo.DictVersionVO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典契约型读服务实现（版本+条目聚合读，GET /dicts/{type} 执行点）。
 *
 * <p>聚合读不继承 IService，直接注入三表 mapper（宪法 A.4.3-20 聚合型接口口径）；
 * 条件构造经 Wrappers 静态工厂（非本 service 主表场景，A.4.3-13），查询全部 select
 * 精确投影（A.4.3-14），条目清单按 sort 升序 + itemCode 次序键保证顺序唯一
 * （A.4.3-17 取前 N 必带唯一顺序约束）。装配归 SystemWebConfig @Import。
 */
@Slf4j
public class DictQueryServiceImpl implements IDictQueryService {

    private final DictTypeMapper dictTypeMapper;

    private final DictVersionMapper dictVersionMapper;

    private final DictItemMapper dictItemMapper;

    private final DictConverter dictConverter;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictTypeMapper    字典类型 mapper，非空
     * @param dictVersionMapper 字典版本 mapper，非空
     * @param dictItemMapper    字典条目 mapper，非空
     * @param dictConverter     字典域转换器，非空；出参组装
     */
    public DictQueryServiceImpl(
            DictTypeMapper dictTypeMapper,
            DictVersionMapper dictVersionMapper,
            DictItemMapper dictItemMapper,
            DictConverter dictConverter) {
        this.dictTypeMapper = dictTypeMapper;
        this.dictVersionMapper = dictVersionMapper;
        this.dictItemMapper = dictItemMapper;
        this.dictConverter = dictConverter;
    }

    @Override
    @Transactional(readOnly = true)
    public DictVersionVO readPublished(String typeCode, Integer version) {
        // 类型存在性校验（SYS-1011/404）；select 精确投影
        DictTypeEntity type = dictTypeMapper.selectOne(Wrappers.<DictTypeEntity>lambdaQuery()
                .eq(DictTypeEntity::getTypeCode, typeCode)
                .select(DictTypeEntity::getId, DictTypeEntity::getTypeCode));
        if (type == null) {
            log.warn("字典读取被拒（类型不存在）：typeCode={}", typeCode);
            throw new BizException(SystemErrorCode.DICT_TYPE_NOT_FOUND, HttpStatus.NOT_FOUND, "字典类型不存在：" + typeCode);
        }
        // 版本解析：version 空=当前 PUBLISHED；非空=指定版本（任意状态，预览/回溯口径）
        DictVersionEntity versionEntity = (version == null)
                ? dictVersionMapper.selectOne(Wrappers.<DictVersionEntity>lambdaQuery()
                        .eq(DictVersionEntity::getDictTypeId, type.getId())
                        .eq(DictVersionEntity::getStatus, DictVersionStatus.PUBLISHED)
                        .select(
                                DictVersionEntity::getId,
                                DictVersionEntity::getVersion,
                                DictVersionEntity::getStatus,
                                DictVersionEntity::getPublishedAt))
                : dictVersionMapper.selectOne(Wrappers.<DictVersionEntity>lambdaQuery()
                        .eq(DictVersionEntity::getDictTypeId, type.getId())
                        .eq(DictVersionEntity::getVersion, version)
                        .select(
                                DictVersionEntity::getId,
                                DictVersionEntity::getVersion,
                                DictVersionEntity::getStatus,
                                DictVersionEntity::getPublishedAt));
        if (versionEntity == null) {
            log.warn("字典读取被拒（版本不存在）：typeCode={}，version={}", typeCode, version);
            throw new BizException(SystemErrorCode.DICT_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND, "字典版本不存在或尚无已发布版本");
        }
        // 条目全量（契约型读豁免分页，理由见接口 javadoc）：sort 升序 + itemCode 唯一次序键
        List<DictItemEntity> items = dictItemMapper.selectList(Wrappers.<DictItemEntity>lambdaQuery()
                .eq(DictItemEntity::getDictVersionId, versionEntity.getId())
                .select(
                        DictItemEntity::getItemCode,
                        DictItemEntity::getItemName,
                        DictItemEntity::getParentCode,
                        DictItemEntity::getSort)
                .orderByAsc(DictItemEntity::getSort)
                .orderByAsc(DictItemEntity::getItemCode));
        return dictConverter.toVersionVO(type, versionEntity, dictConverter.toItemVOs(items));
    }
}
