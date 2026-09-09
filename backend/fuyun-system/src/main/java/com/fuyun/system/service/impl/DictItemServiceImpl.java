package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.dto.DictItemCreateRequest;
import com.fuyun.system.entity.DictItemEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.mapper.DictItemMapper;
import com.fuyun.system.service.IDictItemService;
import com.fuyun.system.service.IDictVersionService;
import com.fuyun.system.vo.DictItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典条目服务实现（system.dict_item 数据访问与条目新增执行点）。
 *
 * <p>版本状态前置校验：仅 DRAFT 版本允许维护条目（PUBLISHED 禁改、DEPRECATED 终态禁改，
 * 版本不可变性 M01 Spec §5）；版本查询经 IDictVersionService（A.4.3-21 禁直接操作他人 mapper）。
 * 装配归 SystemWebConfig @Import。
 */
@Slf4j
public class DictItemServiceImpl extends ServiceImpl<DictItemMapper, DictItemEntity> implements IDictItemService {

    private final IDictVersionService dictVersionService;

    private final DictConverter dictConverter;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictVersionService 字典版本服务，非空；版本存在性与状态校验
     * @param dictConverter      字典域转换器，非空；响应组装
     */
    public DictItemServiceImpl(IDictVersionService dictVersionService, DictConverter dictConverter) {
        this.dictVersionService = dictVersionService;
        this.dictConverter = dictConverter;
    }

    @Override
    @Transactional
    public DictItemVO addItem(Long versionId, DictItemCreateRequest request) {
        // 版本存在性校验（SYS-1012/404）
        DictVersionEntity version = dictVersionService.getById(versionId);
        if (version == null) {
            log.warn("字典条目新增被拒（版本不存在）：versionId={}", versionId);
            throw new BizException(SystemErrorCode.DICT_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND, "字典版本不存在");
        }
        // 版本不可变性校验：仅 DRAFT 可维护条目（SYS-1013/409）
        if (version.getStatus() != DictVersionStatus.DRAFT) {
            log.warn("字典条目新增被拒（版本状态不允许维护）：versionId={}，status={}", versionId, version.getStatus());
            throw new BizException(
                    SystemErrorCode.DICT_VERSION_NOT_PUBLISHABLE,
                    HttpStatus.CONFLICT,
                    "仅草稿版本的字典允许维护条目，当前状态：" + version.getStatus().getCode());
        }
        DictItemEntity entity = new DictItemEntity();
        entity.setDictVersionId(versionId);
        entity.setItemCode(request.itemCode());
        entity.setItemName(request.itemName());
        entity.setParentCode(request.parentCode());
        // 排序号缺省 0（Integer 入参可空语义收口，小者在前）
        entity.setSort(request.sort() != null ? request.sort() : 0);
        this.save(entity);
        log.info("字典条目新增完成：versionId={}，itemCode={}，id={}", versionId, entity.getItemCode(), entity.getId());
        return dictConverter.toItemVO(entity);
    }
}
