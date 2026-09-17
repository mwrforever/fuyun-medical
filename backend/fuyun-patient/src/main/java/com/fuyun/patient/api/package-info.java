/**
 * M02 患者主索引对外契约唯一出口（宪法 B.1 api 包）：跨模块接口/契约 DTO/事件对象/错误码；
 * NamedInterface 将本嵌套包对其他模块显式可见，api 之外的一切包保持模块私有。
 *
 * <p>本包同时是 CF-3 冻结载体的代码侧落点：{@link PatientContextResolver}（解析契约）、
 * {@link OngoingVisitQuery}（在途就诊 SPI，放行语义冻结于其 javadoc）、
 * {@link VisitIdValidator}（visit_id 结构校验规则下发）与八个事件 payload record——
 * 契约变更属双向评审事项（消费方 M03/M04/M05/M06/M13/M14/M16 及全部临床模块）。
 */
@NamedInterface("api")
package com.fuyun.patient.api;

import org.springframework.modulith.NamedInterface;
