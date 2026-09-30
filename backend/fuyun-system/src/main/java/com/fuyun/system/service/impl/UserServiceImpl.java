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
     * <p>执行流程：单条条件 UPDATE 行内原子完成计数累加与锁定判定（EX-28）——fail_count 以
     * 数据库当前值为基自增（setSql 原子累加，同 DeadLetterServiceImpl CAS 先例），锁定判定以
     * SQL 侧 CASE 表达：累加后值达锁定阈值（fail_count + 1 ≥ 阈值，与原 Java 侧判定
     * {@code 旧值+1 >= LOGIN_FAIL_LOCK_THRESHOLD} 逐字等价，PostgreSQL SET 表达式引用列取更新前
     * 旧值）则置 locked_until = now + 锁定时长（应用时钟），未达阈值锁定列回写自身值（不触碰既有
     * 锁定状态），不回写口令等其他字段。
     *
     * <p>并发边界（EX-28 修复）：读-改-写在并发登录失败场景会丢计数（两并发失败基于同一陈旧快照
     * 各写同一字面值，只计 1 次，暴力破解防护被稀释）；行内原子累加由数据库行级锁串行化，每次
     * 失败各贡献一次 +1 且阈值判定不漏锁。阈值预警日志基于加载快照投影（并发下可能滞后），
     * 锁定的权威判定在 SQL 侧。
     *
     * @param user 已按登录名加载的账号实体，非空（调用方已确保存在）；fail_count 取加载时值（仅作
     *             预警日志投影与追溯参考，累加基数为数据库当前值）
     */
    @Override
    @Transactional
    public void recordLoginFailure(UserEntity user) {
        // 阈值预警基于加载快照投影（旧值+1 ≥ 阈值，口径与 SQL 侧 CASE 逐字一致）：并发下快照可能滞后，
        // 锁定是否真实生效以 SQL 侧判定为准（日志仅供运维观察，不承载业务语义）
        int projectedFailCount = (user.getFailCount() == null ? FAIL_COUNT_INITIAL : user.getFailCount()) + 1;
        if (projectedFailCount >= SecurityConstants.LOGIN_FAIL_LOCK_THRESHOLD) {
            // 达阈值置锁定截止时刻（now + 30 分钟，自动到期恢复；应用时钟口径与锁定校验侧一致）
            OffsetDateTime projectedLockedUntil = OffsetDateTime.now().plus(SecurityConstants.LOGIN_LOCK_DURATION);
            log.warn(
                    "连续登录失败达阈值（基于加载快照），账号进入锁定：loginName={}，failCount={}，lockedUntil={}",
                    user.getLoginName(),
                    projectedFailCount,
                    projectedLockedUntil);
        }
        // 单语句原子累加 + 锁定判定（EX-28）：锁定时刻参数经 {1} 绑定（阈值 {0} 同为绑定参数，杜绝拼接）
        this.lambdaUpdate()
                .eq(UserEntity::getId, user.getId())
                .setSql("fail_count = fail_count + 1")
                .setSql(
                        "locked_until = CASE WHEN fail_count + 1 >= {0} THEN {1} ELSE locked_until END",
                        SecurityConstants.LOGIN_FAIL_LOCK_THRESHOLD,
                        OffsetDateTime.now().plus(SecurityConstants.LOGIN_LOCK_DURATION))
                .update();
        log.info("登录失败计数原子累加：loginName={}，loadedFailCount={}", user.getLoginName(), user.getFailCount());
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
