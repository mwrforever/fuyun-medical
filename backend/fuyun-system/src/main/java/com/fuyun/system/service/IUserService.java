package com.fuyun.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.system.entity.UserEntity;

/**
 * 用户账号服务（system.sys_user 数据访问与登录状态机写入口，BRIEF-PR3-01 §3.1）。
 *
 * <p>CRUD 型接口继承 IService（宪法 A.4.3-20）；登录状态机（失败计数/锁定/成功续期）为
 * 认证链路的写路径，事务边界在实现层方法级（宪法 A.4.2-7）。
 */
public interface IUserService extends IService<UserEntity> {

    /**
     * 按登录名精确查询账号（登录用例第一步）。
     *
     * @param loginName 登录名，非空；来源：登录请求入参
     * @return 账号实体（含口令哈希与锁定状态字段，供认证链路使用）；不存在或已逻辑删返回 null
     */
    UserEntity findByLoginName(String loginName);

    /**
     * 记录一次登录失败：fail_count+1，连续失败达锁定阈值置 locked_until（锁定时长见
     * SecurityConstants.LOGIN_LOCK_DURATION，到期自动恢复）。
     *
     * <p>写路径：仅更新 fail_count 与 locked_until 两列（达阈值才写 locked_until），
     * 不触碰口令等其他字段。
     *
     * @param user 已按登录名加载的账号实体，非空（调用方已确保存在）；fail_count 取加载时值
     */
    void recordLoginFailure(UserEntity user);

    /**
     * 记录一次登录成功：fail_count 清零、locked_until 清空、last_login_at 更新为当前时刻。
     *
     * @param user 已按登录名加载的账号实体，非空（调用方已确保存在）
     */
    void recordLoginSuccess(UserEntity user);
}
