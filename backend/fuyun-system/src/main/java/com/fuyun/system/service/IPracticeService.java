package com.fuyun.system.service;

import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.dto.PracticeGrantCreateRequest;
import com.fuyun.system.vo.PracticeCheckResponse;
import com.fuyun.system.vo.PracticeGrantVO;
import java.util.List;

/**
 * 执业授权校验与管理服务（M01 §7 practice/check 用例 + FU-M01-04 授权登记/停权/清单）。
 *
 * <p>check 响应契约自 P0 冻结（PracticeServiceImpl javadoc 承诺）：employeeId/grantType/checkTime
 * 入参回显 + passed + reason，真实校验经 V704 practice_grant 表 EFFECTIVE 授权 + 有效期判定，
 * 授权类型词表 = PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL
 * （全仓消费方逐字引用）。管理方法为写路径：方法级事务，practice.changed 经 AFTER_COMMIT
 * 发布（V5 id 6 既有登记，零新增）。
 */
public interface IPracticeService {

    /**
     * 执业授权校验：EFFECTIVE 且有效期含校验日判通过，未命中区分「已过期/无记录」两态 reason。
     *
     * @param request 校验请求，非空；employeeId/grantType 由 JSR-303 校验非空，checkTime 可空
     *                （null 取服务端当前时刻）
     * @return 校验响应（入参回显 + passed + reason），非空；passed=false 的两态 reason 文案见实现
     */
    PracticeCheckResponse check(PracticeCheckRequest request);

    /**
     * 执业授权登记：落 EFFECTIVE 行并发布 practice.changed（EFFECTIVE）。
     *
     * @param request 登记请求，非空；grantType 词表由 JSR-303 @Pattern 校验
     * @return 新登记授权行 ID（雪花 ID），非空
     * @throws BizException SYS-1022/409 同员工同类型已存在 EFFECTIVE 行（唯一索引兜底并发双登记）
     */
    long grant(PracticeGrantCreateRequest request);

    /**
     * 执业授权停权：仅 EFFECTIVE 可停（CAS 条件更新），成功后发布 practice.changed（SUSPENDED）。
     *
     * @param id     授权行主键，非空
     * @param reason 停权理由（留痕：warn 日志 + 审计行），非空
     * @throws BizException SYS-1021/404 行不存在或已非 EFFECTIVE（并发已停/竞态落败同口径）
     */
    void withdraw(long id, String reason);

    /**
     * 按员工查询授权清单（管理端展示）：valid_to 已过的 EFFECTIVE 行读侧派生 EXPIRED 展示，
     * 不回写库。
     *
     * @param employeeId 员工 ID，非空
     * @return 授权行展示清单（id 降序），无授权返回空清单，非空
     */
    List<PracticeGrantVO> listByEmployee(long employeeId);
}
