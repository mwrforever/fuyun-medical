package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.entity.UserEntity;
import com.fuyun.system.mapper.UserMapper;
import com.fuyun.system.service.IUserService;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户账号服务实现（system.sys_user 数据访问与登录状态机执行点）。
 *
 * <p>登录状态机（M01 Spec §5）：失败计数累加，连续失败达 {@link SecurityConstants#LOGIN_FAIL_LOCK_THRESHOLD}
 * 置 locked_until（自动到期恢复，P0 无手动解锁端点）；成功登录清零计数并清空锁定时刻。
 * 锁定语义只驱动 locked_until 列，不改 status 列（status=LOCKED 预留 P1 显式锁定管理）。
 *
 * <p>单表操作走 ServiceImpl 内置 lambda 链式（宪法 A.4.3-13），update 精确投影目标列
 * （A.4.3-14 按需取列的写侧对称：不回写口令哈希等未变更字段）；时间戳列由数据库触发器维护，
 * 应用层只写业务列。装配说明：com.fuyun.system 不在组件扫描范围，Bean 注册点为
 * SystemWebConfig @Import（宪法 B.1 装配归 app 侧配置，integration 先例）。
 */
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, UserEntity> implements IUserService {

    /** 登录失败防枚举文案口径外的内部告警前缀：日志区分失败形态，响应文案统一 */
    private static final int FAIL_COUNT_INITIAL = 0;

    /**
     * 按登录名精确查询账号（登录用例第一步的账号加载）。
     *
     * <p>select 精确投影认证链路所需列（口令哈希与锁定状态字段等，不取审计列）；逻辑删行由
     * @TableLogic 自动过滤，已删账号与不存在账号同归 null。
     *
     * @param loginName 登录名，非空；来源：登录请求入参
     * @return 账号实体（含口令哈希与锁定状态，供认证链路使用）；登录名不存在或已逻辑删返回
     *         null（调用方按 SYS-1001 防枚举文案拒绝）
     */
    @Override
    @Transactional(readOnly = true)
    public UserEntity findByLoginName(String loginName) {
        // select 精确投影（A.4.3-14）：认证链路所需列，不取审计列
        return this.lambdaQuery()
                .eq(UserEntity::getLoginName, loginName)
                .select(
                        UserEntity::getId,
                        UserEntity::getLoginName,
                        UserEntity::getPasswordHash,
                        UserEntity::getUserType,
                        UserEntity::getStatus,
                        UserEntity::getFailCount,
                        UserEntity::getLockedUntil,
                        UserEntity::getLastLoginAt)
                .one();
    }

    /**
     * 记录一次登录失败（失败计数状态机写路径，方法级事务）。
     *
     * <p>执行流程：fail_count 以加载时值为基累加 → 连续失败达锁定阈值时计算 locked_until
     * （now + 锁定时长，到期自动恢复）→ 仅更新 fail_count 与 locked_until 两列（锁定列条件
     * set，未达阈值不触碰既有锁定状态），不回写口令等其他字段。
     *
     * <p>并发边界：以加载时值为基的累加在并发登录失败场景存在丢计数可能（P0 可接受，锁定兜底
     * 为数据库行级更新）。
     *
     * @param user 已按登录名加载的账号实体，非空（调用方已确保存在）；fail_count 取加载时值
     */
    @Override
    @Transactional
    public void recordLoginFailure(UserEntity user) {
        // 连续失败计数：以加载时值为基累加（并发登录失败存在丢计数可能，锁定兜底为数据库行级更新，P0 可接受）
        int failCount = (user.getFailCount() == null ? FAIL_COUNT_INITIAL : user.getFailCount()) + 1;
        OffsetDateTime lockedUntil = null;
        if (failCount >= SecurityConstants.LOGIN_FAIL_LOCK_THRESHOLD) {
            // 达阈值置锁定截止时刻（now + 30 分钟，自动到期恢复）
            lockedUntil = OffsetDateTime.now().plus(SecurityConstants.LOGIN_LOCK_DURATION);
            log.warn(
                    "连续登录失败达阈值，账号进入锁定：loginName={}，failCount={}，lockedUntil={}",
                    user.getLoginName(),
                    failCount,
                    lockedUntil);
        }
        this.lambdaUpdate()
                .eq(UserEntity::getId, user.getId())
                .set(UserEntity::getFailCount, failCount)
                // 锁定列仅在达阈值时写入（条件 set），未达阈值不触碰既有锁定状态
                .set(lockedUntil != null, UserEntity::getLockedUntil, lockedUntil)
                .update();
        log.info("登录失败计数更新：loginName={}，failCount={}", user.getLoginName(), failCount);
    }

    /**
     * 记录一次登录成功（状态机复位写路径，方法级事务）。
     *
     * <p>复位语义：fail_count 清零、locked_until 显式写 null 清空（残留即误锁）、last_login_at
     * 更新为当前时刻；不触碰口令等其他字段。
     *
     * @param user 已按登录名加载的账号实体，非空（调用方已确保存在）
     */
    @Override
    @Transactional
    public void recordLoginSuccess(UserEntity user) {
        // 成功登录状态机复位：计数清零 + 锁定清空（显式写 null）+ 最近登录时刻
        this.lambdaUpdate()
                .eq(UserEntity::getId, user.getId())
                .set(UserEntity::getFailCount, FAIL_COUNT_INITIAL)
                .set(UserEntity::getLockedUntil, null)
                .set(UserEntity::getLastLoginAt, OffsetDateTime.now())
                .update();
        log.info("登录成功状态更新：loginName={}，userId={}", user.getLoginName(), user.getId());
    }
}
