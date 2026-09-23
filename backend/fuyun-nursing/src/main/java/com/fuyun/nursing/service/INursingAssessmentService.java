package com.fuyun.nursing.service;

import com.fuyun.nursing.constants.ScaleDefinition;
import com.fuyun.nursing.dto.NursingAssessmentCreateRequest;
import com.fuyun.nursing.enums.ScaleType;
import com.fuyun.nursing.vo.NursingAssessmentVO;
import com.fuyun.nursing.vo.ScaleDefinitionVO;
import java.util.List;

/**
 * 护理评估单域服务（V806 nursing_assessment 业务面）：五量表定义暴露（前端渲染评估表单唯一
 * 数据源）、评估创建（量表引擎判级 + 高危联动——自动生成防范任务与床旁风险标识回写）与患者
 * 评估清单查询。判级阈值与条目词表冻结于 {@link ScaleDefinition}（NursingScaleConstants 装配），
 * 改阈值属宪法级契约变更；CUSTOM 自定义量表引擎归 P2（NS-1009 拒绝）。业务时间口径（Spec
 * 红线 2 注记）：assessed_at 为临床实际评估时刻（请求携带强校验），审计列为服务器时间。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 */
public interface INursingAssessmentService {

    /**
     * 评估量表定义清单（五量表冻结定义全量暴露）：BRADEN/MORSE/NRS/BARTHEL/MEWS 条目词表、
     * 取值域与总分规则；纯内存常量读取，零 DB 触达。
     *
     * @return 量表定义出参清单，非空；按冻结展示序（BRADEN→MORSE→NRS→BARTHEL→MEWS）
     */
    List<ScaleDefinitionVO> scales();

    /**
     * 护理评估单创建（八步链）：①量表类型校验（词表外 NS-1009）→ ②条目完整性 + 取值范围
     * 校验（NS-1010）→ ③算总分与判级（阈值冻结清单）→ ④在区校验 + 业务时间双向校验
     * （不晚于当前、不早于入区时间，越界 NS-1016）→ ⑤发号器取 AS 号 → ⑥insert（唯一冲突
     * 兜底转 NS-1016 幂等拒绝）→ ⑦HIGH 分支联动：防范任务生成（PREVENTION/ASSESSMENT/HIGH/
     * sourceRef=assessNo/planTime=now）+ 风险标识回写（仅 BRADEN→PRESSURE、MORSE→FALL）+
     * triggered_task_ref 回填 → ⑧发布 nursing.assessment.completed（事务内发布 AFTER_COMMIT
     * 出站 GC8）。复评计划随判级盖章（HIGH 24h / MEDIUM 72h / LOW 168h）。
     *
     * @param req 创建入参，非空；来源：操作者工作站评估表单
     * @return 评估单出参（含判级结果与复评计划），非空
     * @throws BizException NS-1009（400 量表类型不支持）/ NS-1010（400 条目缺失或取值越界）/
     *                      NS-1004（409 患者不在区）/ NS-1016（409 评估时点越界或评估单号唯一冲突幂等拒绝）
     */
    NursingAssessmentVO create(NursingAssessmentCreateRequest req);

    /**
     * 患者评估单清单：visitId 必选，scaleType 可选（空=全量表），按评估时点降序
     * （DB 侧排序，最新评估优先展示）。
     *
     * @param visitId   住院就诊号，非空；来源：查询参数
     * @param scaleType 量表类型过滤，可空（空=全量表）；来源：查询参数
     * @return 评估单出参清单（无行返回空清单，非 null）；按评估时点降序
     */
    List<NursingAssessmentVO> listByVisit(String visitId, ScaleType scaleType);
}
