package com.fuyun.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.system.entity.DictVersionEntity;
import com.fuyun.system.vo.DictVersionVO;

/**
 * 字典版本服务（system.dict_version 数据访问与版本状态机用例，BRIEF-PR3-01 §3.2）。
 *
 * <p>CRUD 型接口继承 IService（宪法 A.4.3-20）；状态机 DRAFT→PUBLISHED→DEPRECATED
 * （M01 Spec §5），发布事务边界与事件时机见实现类。
 */
public interface IDictVersionService extends IService<DictVersionEntity> {

    /**
     * 创建草稿版本：版本号同类型内自增（现有最大版本 +1，无版本从 1 起），默认 DRAFT 状态。
     *
     * @param typeCode 所属字典类型编码，非空；类型不存在抛 SYS-1011
     * @return 版本出参（typeCode + 版本号 + DRAFT 状态，条目为空清单），非空
     * @throws com.fuyun.common.exception.BizException SYS-1011（字典类型不存在，404）
     */
    DictVersionVO createVersion(String typeCode);

    /**
     * 发布版本（仅 DRAFT 可发布）：以条件更新原子抢占发布权
     * （{@code UPDATE ... SET status='PUBLISHED' WHERE id=? AND status='DRAFT'}，影响行数=0
     * 抛 SYS-1013——并发双 publish 仅一者成功，防双广播）+ published_at/effective_at=now
     * （P0 发布即生效），同类型旧 PUBLISHED 行置 DEPRECATED；事务提交后经 AFTER_COMMIT
     * 监听广播 system.dict.published（事务内禁 MQ 发送，A.4.2-7/B.3-1）。
     *
     * @param versionId 字典版本 ID，非空
     * @throws com.fuyun.common.exception.BizException SYS-1012（字典版本不存在，404）、
     *                                                 SYS-1011（所属类型缺失，404）、
     *                                                 SYS-1013（版本状态不允许发布/并发发布落败，409）
     */
    void publish(Long versionId);
}
