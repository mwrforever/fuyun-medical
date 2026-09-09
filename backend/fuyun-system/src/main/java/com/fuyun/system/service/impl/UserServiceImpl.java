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
