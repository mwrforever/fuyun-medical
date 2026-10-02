package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.NursingTaskLinkagePort;
import com.fuyun.nursing.api.NursingTaskLinkageRequest;
import com.fuyun.nursing.api.NursingTaskLinkageResult;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.vo.NursingTaskVO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * 联动任务创建端口实现（M14→M05 联动回接，P2 PR-3 Task 12）：IOT_LINKAGE 任务幂等创建单点
 * ——幂等键=linkageNo（source=IOT_LINKAGE + source_ref=linkageNo 业务级回查去重，无唯一
 * 索引不追加，并发防御由 linkage 执行侧 attempt 语义保序）；创建路径复用
 * {@code INursingTaskService.create} 既有面（发号/补录红线校验/PENDING 默认态/
 * nursing.task.created 事件发布全复用——零新事件零新队列，勿在本类复制创建逻辑）。
 *
 * <p>操作者上下文：iot 联动链路经 MQ 消费线程调起（无登录上下文），create 内部回退
 * system（V805 审计列默认同源）。
 *
 * <p>线程安全：无状态端口；写路径事务由 create 的 @Transactional 收口（经服务 Bean 代理
 * 调用，非自调用）。装配归 NursingWebConfig @Import。
 */
@Slf4j
public class NursingTaskLinkagePortImpl implements NursingTaskLinkagePort {

    private final INursingTaskService taskService;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param taskService 护理任务域服务，非空；幂等回查与创建复用通道
     */
    public NursingTaskLinkagePortImpl(INursingTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 联动任务幂等创建（先回查后创建，语义见接口注）。
     *
     * @param request 联动创建请求，非空；来源：iot LinkageExecutor NURSING_TASK 动作
     * @return 创建结果（首建 created=true／幂等重放 created=false），非空
     * @throws BizException NS-1019（400 幂等键/患者锚/病区缺失）／NS-1019·NS-1016（底层
     *                 create 校验拒绝原样透传——iot 侧走失败重试路）
     */
    @Override
    public NursingTaskLinkageResult createTask(NursingTaskLinkageRequest request) {
        // 守卫链：幂等键与患者/病区锚缺失前置拦截（DB NOT NULL 列裸触达会以 DataIntegrity
        // 异常而非 BizException 出栈，iot 侧重试语义不可分辨——统一 NS-1019 业务拒绝）
        requireParam(request.linkageNo(), "linkageNo");
        requireParam(request.patientId(), "patientId");
        requireParam(request.visitId(), "visitId");
        requireParam(request.wardId(), "wardId");
        // 数据库读操作：幂等键回查（source=IOT_LINKAGE + source_ref=linkageNo 双键；deleted=0
        // 惯例由 @TableLogic 自动携带）
        List<NursingTask> existing = taskService.list(Wrappers.<NursingTask>lambdaQuery()
                .eq(NursingTask::getSource, TaskSource.IOT_LINKAGE.getCode())
                .eq(NursingTask::getSourceRef, request.linkageNo()));
        if (!existing.isEmpty()) {
            // 幂等重放：PENDING 行人工重推/链路重投由同一 linkageNo 回查命中，返回原任务零新建零事件
            log.info(
                    "联动任务幂等重放（回查原任务）：linkageNo={}，taskNo={}，visitId={}",
                    request.linkageNo(),
                    existing.get(0).getTaskNo(),
                    request.visitId());
            return new NursingTaskLinkageResult(existing.get(0).getTaskNo(), false);
        }
        // 创建路径复用 create 既有面：source 固定 IOT_LINKAGE（端口语义，调用方不可伪造他源）、
        // sourceRef=linkageNo 幂等锚、priority 缺省 NORMAL、assignedNurse 空由任务工作台按组展示
        NursingTaskVO created = taskService.create(new NursingTaskCreateRequest(
                request.patientId(),
                request.visitId(),
                request.wardId(),
                null,
                request.taskType(),
                TaskSource.IOT_LINKAGE.getCode(),
                request.linkageNo(),
                request.planTime(),
                null,
                null));
        log.info(
                "联动任务已创建：linkageNo={}，taskNo={}，visitId={}，wardId={}，taskType={}，planTime={}",
                request.linkageNo(),
                created.taskNo(),
                request.visitId(),
                request.wardId(),
                request.taskType(),
                request.planTime());
        return new NursingTaskLinkageResult(created.taskNo(), true);
    }

    /**
     * 必填参数守卫（缺失即 NS-1019，消息定位字段名）。
     *
     * @param value       参数值，可空
     * @param fieldName   业务字段名（消息定位用），非空
     */
    private static void requireParam(Object value, String fieldName) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "联动任务创建参数缺失：" + fieldName);
        }
    }
}
