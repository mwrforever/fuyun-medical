package com.fuyun.iot.service;

import com.fuyun.common.web.PageResult;
import com.fuyun.iot.dto.LinkageLogQueryRequest;
import com.fuyun.iot.dto.SaveLinkageRuleRequest;
import com.fuyun.iot.vo.LinkageLogVO;
import com.fuyun.iot.vo.LinkageRuleVO;
import java.util.List;

/**
 * 联动规则服务契约（FU-M14-10 管理面）：规则 CRUD（软删）+ 联动执行日志分页 + FAILED 行人工
 * 重推（幂等面）。
 *
 * <p>人工重推语义：仅 FAILED 行可重推（重执行动作 + CAS 迁移结果 + 发布 executed 事件同事务）；
 * 非终态行（SUCCESS/PENDING）、规则已删除/停用、并发窗口被他方承接均 IOT-1018 409 拒绝。
 * 装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
public interface ILinkageRuleService {

    /**
     * 规则清单（全量，id 升序稳定输出；@TableLogic 自动携带 deleted=0）。
     *
     * @return 规则视图清单，非空；空表为空清单
     */
    List<LinkageRuleVO> list();

    /**
     * 规则登记（触发条件词表校验通过后落行，enabled 缺省补 true）。
     *
     * @param request 登记请求，非空；来源：管理台表单（@Valid 后置校验已过）
     * @return 落库后的规则视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1018（409 条件词表违例：未知键/非对象/
     *                                                  空白值）
     */
    LinkageRuleVO create(SaveLinkageRuleRequest request);

    /**
     * 规则更新（既有行字段全量覆写，触发条件词表校验同登记）。
     *
     * @param id      规则行 id，非空
     * @param request 更新请求，非空
     * @return 更新后的规则视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1017（404 规则不存在）/ IOT-1018
     *                                                  （409 条件词表违例）
     */
    LinkageRuleVO update(Long id, SaveLinkageRuleRequest request);

    /**
     * 规则软删（@TableLogic 逻辑删；历史联动日志 rule_id 留痕不受影响）。
     *
     * @param id 规则行 id，非空
     * @throws com.fuyun.common.exception.BizException IOT-1017（404 规则不存在）
     */
    void delete(Long id);

    /**
     * 联动执行日志分页（page 0 基；executed_at 降序稳定输出）。
     *
     * @param request 分页查询请求（过滤条件可空），非空
     * @return 分页出参，非空
     */
    PageResult<LinkageLogVO> page(LinkageLogQueryRequest request);

    /**
     * 人工重推（FAILED 行专属幂等面）：重执行动作（事务外）→ CAS 迁移结果 + 发布 executed 事件
     * （同事务）。成功后 retry_count 续增；再次耗尽仍落 FAILED 可再次重推。
     *
     * @param linkageNo 联动执行业务号，非空；来源：重推端点路径变量
     * @return 重推后的日志视图，非空
     * @throws com.fuyun.common.exception.BizException IOT-1017（404 借承——日志行不存在）、
     *                                                  IOT-1018（409 非 FAILED 行/规则已删除或停用/
     *                                                  并发已被他方承接）
     */
    LinkageLogVO retry(String linkageNo);
}
