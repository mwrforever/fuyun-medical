package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.constants.NursingSecurityConstants;
import com.fuyun.nursing.entity.NurseAssignment;
import com.fuyun.nursing.mapper.NurseAssignmentMapper;
import com.fuyun.nursing.service.IWardAccessService;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * 病区归属校验服务实现（PR-4C Task 4，W-40 方案 A）：以 nurse_assignment 当班 ACTIVE 绑定集为
 * 唯一授权依据的病区级访问防线——集外一律 fail-closed 403 NS-1028（D-29：无绑定行一律拒绝，
 * ADMIN 亦无豁免）；大屏哨兵操作者豁免直通（HTTP 层已限行+wardId 一致性校验，此处为防御纵深
 * 而非二次授权）。消费方：Task 6 大屏/病区端点守卫与 Task 7 WS SUBSCRIBE 防线（显式传参版）。
 * 聚合型服务不继承 IService、经构造器注入 mapper（backend 宪法 A.4.3-20；装配归
 * NursingWebConfig @Import）。线程安全：无状态 singleton，可并发调用。
 */
@Slf4j
public class WardAccessServiceImpl implements IWardAccessService {

    private final NurseAssignmentMapper assignmentMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param assignmentMapper 责任护士分配 mapper（当班绑定集查询数据源），非空
     */
    public WardAccessServiceImpl(NurseAssignmentMapper assignmentMapper) {
        this.assignmentMapper = assignmentMapper;
    }

    /**
     * 查操作者当班 ACTIVE 病区绑定集（接口契约见 {@link IWardAccessService#activeBoundWardIds}）：
     * nurse_id 等值 + ACTIVE 态 + 当日双边有效窗（valid_to NULL 视为长期），ward_id 精确投影
     * 按需取列（backend 宪法 A.4.3-14）后去重。当日窗口一律北京钟面医疗日（时区红线 GC4
     * ——禁 DB 会话时区 CURRENT_DATE/裸 now()）。
     *
     * @param operatorId 操作者标识（护士 userId 十进制字符串），非空
     * @return 当班绑定病区编码清单（去重，无有效绑定返回空清单，非 null）
     */
    @Override
    public List<String> activeBoundWardIds(String operatorId) {
        // 当日窗口按北京钟面医疗日（时区红线 GC4）：绑定窗口判定不随容器/DB 时区漂移
        LocalDate today = LocalDate.now(TimeConstants.HEALTHCARE_TZ);
        // 数据库读操作：当班 ACTIVE 绑定行（非本 service 主表，Wrappers 静态工厂——A.4.3-13；
        // valid_to IS NULL OR valid_to>=当日：NULL 长期行入窗、过期行由谓词滤除）
        List<NurseAssignment> rows = assignmentMapper.selectList(Wrappers.lambdaQuery(NurseAssignment.class)
                .select(NurseAssignment::getWardId)
                .eq(NurseAssignment::getNurseId, operatorId)
                .eq(NurseAssignment::getStatus, "ACTIVE")
                .le(NurseAssignment::getValidFrom, today)
                .and(w -> w.isNull(NurseAssignment::getValidTo).or().ge(NurseAssignment::getValidTo, today)));
        return rows.stream().map(NurseAssignment::getWardId).distinct().toList();
    }

    /**
     * REST 守卫入口（接口契约见 {@link IWardAccessService#assertWardAllowed}）：取 ThreadLocal
     * 操作者身份委托显式传参版统一校验（两版语义等价）。
     *
     * @param wardId 目标病区编码，非空；来源：路径/查询参数
     * @throws BizException NS-1028（403）：身份缺失/无绑定行（fail-closed）/越区，见显式传参版
     */
    @Override
    public void assertWardAllowed(String wardId) {
        String operator = OperatorContextHolder.get();
        assertWardAllowedFor(operator, wardId);
    }

    /**
     * 显式传参校验版（接口契约见 {@link IWardAccessService#assertWardAllowedFor}）：哨兵豁免
     * （防御纵深）→ 身份缺失 fail-closed → 当班绑定集校验（空集或越区同拒）。
     *
     * @param operatorId 操作者标识（userId 十进制字符串），可空——空/空白按身份缺失拒绝
     * @param wardId     目标病区编码，非空；来源：REST 参数或 WS SUBSCRIBE 目的地解析
     * @throws BizException NS-1028（403）：操作者身份缺失（无/空白）、无 ACTIVE 绑定行
     *             （fail-closed，ADMIN 无豁免——D-29）或目标病区不在绑定集内（越区访问）
     */
    @Override
    public void assertWardAllowedFor(String operatorId, String wardId) {
        if (NursingSecurityConstants.BIGSCREEN_SENTINEL_OPERATOR_ID.equals(operatorId)) {
            // 哨兵豁免：HTTP 层已限行+wardId 一致性校验（防御纵深，非二次授权）
            return;
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw denied("操作者身份缺失，病区访问被拒（fail-closed）");
        }
        List<String> bound = activeBoundWardIds(operatorId);
        if (bound.isEmpty() || !bound.contains(wardId)) {
            // D-29 fail-closed：无绑定行一律 403；有绑定但越区同拒（日志记 operator/wardId，禁打令牌——GC12）
            log.warn("病区访问被拒：operator={}，wardId={}，boundWards={}", operatorId, wardId, bound);
            throw denied("病区不在当班绑定范围或无有效绑定（fail-closed）");
        }
    }

    /**
     * 构造 NS-1028 拒绝异常（统一 403 传输语义）。
     *
     * @param detail 业务可读拒绝详情（不含敏感值），非空
     * @return 携带 WARD_ACCESS_DENIED（NS-1028）与 403 状态的业务异常
     */
    private BizException denied(String detail) {
        return new BizException(NursingErrorCode.WARD_ACCESS_DENIED, HttpStatus.FORBIDDEN, detail);
    }
}
