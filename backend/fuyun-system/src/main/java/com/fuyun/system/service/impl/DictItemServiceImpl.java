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

    /**
     * 新增字典条目（POST /api/v1/system/dict-versions/{versionId}/items 执行点）：仅在 DRAFT
     * 草稿版本内追加条目，PUBLISHED/DEPRECATED 版本禁改（版本不可变性 M01 Spec §5）。
     *
     * <p>执行流程：版本存在性校验（经 IDictVersionService 跨表查询，A.4.3-21）→ 版本状态
     * 校验（仅 DRAFT 放行）→ 条目实体组装 → 落库 → 转 VO。同版本内 itemCode 唯一性不做
     * 前置查询，由 uk_dict_item_version_code 部分唯一索引兜底——并发同码插入时落库抛重复键
     * 随事务回滚。
     *
     * @param versionId 所属字典版本 ID，非空；来源：管理端路径参数
     * @param request   条目创建请求，非空（itemCode/itemName 非空由 controller 层 @Valid 保证）；
     *                  itemCode 同版本内唯一、parentCode 空=顶层条目、sort 空按 0（小者在前）
     * @return 条目出参（含落库后雪花 ID），非空
     * @throws BizException SYS-1012（字典版本不存在，HTTP 404）、SYS-1013（版本状态非 DRAFT
     *                      不允许维护条目，HTTP 409，建议刷新版本状态后操作）
     */
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
