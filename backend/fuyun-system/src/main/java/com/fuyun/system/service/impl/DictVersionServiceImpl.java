package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.entity.DictTypeEntity;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.enums.DictVersionStatus;
import com.fuyun.system.internal.DictVersionPublishedEvent;
import com.fuyun.system.mapper.DictVersionMapper;
import com.fuyun.system.service.IDictTypeService;
import com.fuyun.system.service.IDictVersionService;
import com.fuyun.system.vo.DictVersionVO;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字典版本服务实现（system.dict_version 数据访问与 DRAFT→PUBLISHED→DEPRECATED 状态机执行点）。
 *
 * <p>发布事务边界（M01 Spec §5"同一 type 同一时刻仅一个 PUBLISHED"，应用层保证）：
 * publish 为方法级事务——版本存在性与类型一致性前置校验 → 条件更新原子抢占发布权
 * （{@code UPDATE ... WHERE id=? AND status='DRAFT'}，影响行数=0 抛 SYS-1013，防并发双
 * publish 双广播）→ 同类型其余 PUBLISHED 行置 DEPRECATED → 事务上下文内发布 Spring 应用
 * 事件（B.3-1 同步事件），由 SystemEventPublisher AFTER_COMMIT 监听在事务提交后才发 MQ
 * ——事务回滚则广播不出（A.4.2-7 事务内禁消息发送）。
 *
 * <p>跨表查询经 IDictTypeService（A.4.3-21 禁直接操作他人 mapper）；主表单表链式
 * （A.4.3-13）+ select 精确投影（A.4.3-14）。装配归 SystemWebConfig @Import。
 */
@Slf4j
public class DictVersionServiceImpl extends ServiceImpl<DictVersionMapper, DictVersionEntity>
        implements IDictVersionService {

    private final IDictTypeService dictTypeService;

    private final DictConverter dictConverter;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param dictTypeService 字典类型服务，非空；类型存在性校验与 typeCode 查询
     * @param dictConverter   字典域转换器，非空；响应组装
     * @param eventPublisher  Spring 应用事件发布器，非空；发布事务后广播触发事件
     */
    public DictVersionServiceImpl(
            IDictTypeService dictTypeService, DictConverter dictConverter, ApplicationEventPublisher eventPublisher) {
        this.dictTypeService = dictTypeService;
        this.dictConverter = dictConverter;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建草稿字典版本（POST /api/v1/system/dict-types/{typeCode}/versions 执行点）：为既有
     * 类型开出新 DRAFT 版本供条目维护，新版本自此进入 DRAFT→PUBLISHED→DEPRECATED 状态机。
     *
     * <p>执行流程：类型存在性校验（经 IDictTypeService.getByTypeCode 跨表查询，A.4.3-21）→
     * 版本号自增（同类型现有最大版本 +1，无版本从 1 起）→ 组装 DRAFT 实体落库 → 转 VO。
     * 读最大值+1 非原子，并发开版本由 uk_dict_version_type_version 唯一索引兜底回滚。
     *
     * @param typeCode 所属字典类型编码，非空；来源：管理端路径参数
     * @return 版本出参（typeCode + 自增版本号 + DRAFT 状态 + 空条目清单），非空
     * @throws BizException SYS-1011（字典类型不存在，HTTP 404）
     */
    @Override
    @Transactional
    public DictVersionVO createVersion(String typeCode) {
        DictTypeEntity type = dictTypeService.getByTypeCode(typeCode);
        if (type == null) {
            log.warn("字典版本创建被拒（类型不存在）：typeCode={}", typeCode);
            throw new BizException(SystemErrorCode.DICT_TYPE_NOT_FOUND, HttpStatus.NOT_FOUND, "字典类型不存在：" + typeCode);
        }
        // 版本号自增：取同类型现有最大版本 +1，无版本从 1 起（唯一索引 uk_dict_version_type_version 兜底并发）
        Integer nextVersion = this.lambdaQuery()
                        .eq(DictVersionEntity::getDictTypeId, type.getId())
                        .select(DictVersionEntity::getVersion)
                        .list()
                        .stream()
                        .map(DictVersionEntity::getVersion)
                        .max(Integer::compareTo)
                        .orElse(0)
                + 1;
        DictVersionEntity entity = new DictVersionEntity();
        entity.setDictTypeId(type.getId());
        entity.setVersion(nextVersion);
        entity.setStatus(DictVersionStatus.DRAFT);
        this.save(entity);
        log.info("字典版本创建完成：typeCode={}，version={}，id={}", typeCode, nextVersion, entity.getId());
        return dictConverter.toVersionVO(type, entity, List.of());
    }

    /**
     * 发布字典版本（POST /api/v1/system/dict-versions/{versionId}/publish 执行点）：DRAFT→
     * PUBLISHED 状态迁移，同类型旧 PUBLISHED 版本随之置 DEPRECATED（同一 type 同一时刻仅一个
     * PUBLISHED，M01 Spec §5），发布即生效（published_at/effective_at 同取 now，P0 无定时生效）。
     *
     * <p>执行流程：①版本与所属类型存在性校验 → ②条件更新原子抢占发布权（{@code UPDATE ...
     * WHERE id=? AND status='DRAFT'}，影响行数=0 即状态已变或并发落败，防并发双 publish 双
     * 广播）→ ③同类型其余 PUBLISHED 行置 DEPRECATED → ④事务内发布 Spring 应用事件，
     * AFTER_COMMIT 监听事务提交后才广播 system.dict.published 至 MQ（A.4.2-7 事务内禁消息
     * 发送；事务回滚则广播不出）。缓存联动写实：发布侧自身不触碰任何缓存——system 模块 P0
     * 无服务端字典缓存（本地缓存失效属 P1 接入），下游消费方（如药房主数据缓存）凭事务后
     * 广播事件自行刷新。
     *
     * @param versionId 字典版本 ID，非空；来源：管理端路径参数
     * @throws BizException SYS-1012（字典版本不存在，HTTP 404）、SYS-1011（所属类型缺失，
     *                      HTTP 404，外键悬空属数据一致性异常，建议核查数据）、SYS-1013
     *                      （版本状态非 DRAFT 或并发发布落败，HTTP 409，建议刷新版本状态后重试）
     */
    @Override
    @Transactional
    public void publish(Long versionId) {
        // 1. 版本存在性校验（SYS-1012/404）
        DictVersionEntity version = this.getById(versionId);
        if (version == null) {
            log.warn("字典版本发布被拒（版本不存在）：versionId={}", versionId);
            throw new BizException(SystemErrorCode.DICT_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND, "字典版本不存在");
        }
        DictTypeEntity type = dictTypeService.getById(version.getDictTypeId());
        if (type == null) {
            // 类型缺失属数据一致性异常（外键不在应用层由 V300 约定保证），按类型不存在口径处置
            log.error("字典版本发布被拒（所属类型缺失）：versionId={}，dictTypeId={}", versionId, version.getDictTypeId());
            throw new BizException(SystemErrorCode.DICT_TYPE_NOT_FOUND, HttpStatus.NOT_FOUND, "字典类型不存在");
        }
        // 2. 条件更新原子抢占发布权（WHERE id=? AND status='DRAFT'，MP 单表链式 A.4.3-13）：
        //    读-检-写在并发双 publish 下会双双通过并触发两次 AFTER_COMMIT 广播，条件更新保证
        //    仅影响行数=1 的调用者完成发布，=0（已发布/废弃/竞态落败）抛 SYS-1013 拒绝
        OffsetDateTime now = OffsetDateTime.now();
        boolean published = this.lambdaUpdate()
                .eq(DictVersionEntity::getId, versionId)
                .eq(DictVersionEntity::getStatus, DictVersionStatus.DRAFT)
                .set(DictVersionEntity::getStatus, DictVersionStatus.PUBLISHED)
                .set(DictVersionEntity::getPublishedAt, now)
                .set(DictVersionEntity::getEffectiveAt, now)
                .update();
        if (!published) {
            log.warn("字典版本发布被拒（状态已变更或并发发布落败）：versionId={}", versionId);
            throw new BizException(
                    SystemErrorCode.DICT_VERSION_NOT_PUBLISHABLE, HttpStatus.CONFLICT, "仅草稿版本的字典允许发布，版本状态可能已变更，请刷新后重试");
        }
        // 3. 同类型旧 PUBLISHED 行置 DEPRECATED（同一 type 同一时刻仅一个 PUBLISHED）
        this.lambdaUpdate()
                .eq(DictVersionEntity::getDictTypeId, version.getDictTypeId())
                .eq(DictVersionEntity::getStatus, DictVersionStatus.PUBLISHED)
                .ne(DictVersionEntity::getId, versionId)
                .set(DictVersionEntity::getStatus, DictVersionStatus.DEPRECATED)
                .update();
        log.info("字典版本发布完成：typeCode={}，version={}，versionId={}", type.getTypeCode(), version.getVersion(), versionId);
        // 4. 事务上下文内发布应用事件：仅条件更新成功者触达，AFTER_COMMIT 监听者于事务提交后才发 MQ
        //    （回滚则不广播；防并发双广播语义见步骤 2）
        eventPublisher.publishEvent(new DictVersionPublishedEvent(type.getTypeCode(), version.getVersion()));
    }
}
