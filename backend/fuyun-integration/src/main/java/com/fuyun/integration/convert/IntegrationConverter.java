package com.fuyun.integration.convert;

import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.entity.EventPublication;
import com.fuyun.integration.entity.EventRegistry;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import com.fuyun.integration.vo.EventPublicationVO;
import com.fuyun.integration.vo.EventRegistryVO;
import com.fuyun.integration.vo.ReceivedEventVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

/**
 * M20 治理域 MapStruct 转换器（backend 宪法 A.7-4）：台账实体 → 出参对象映射。
 *
 * <p>componentModel 取默认（非 spring）：Bean 注册点为 IntegrationWebConfig 经
 * {@link Mappers#getMapper} 装配（宪法 B.1 装配归 app 侧配置）。
 *
 * <p>最小暴露原则：死信列表行只出载荷头部预览（{@link MessagingConstants#DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH}
 * 字符）+ SHA-256 摘要，载荷全文只经详情端点（运维诊断看载荷场景，M20 §3.2）。
 *
 * <p>截断方法以 {@link Named} 限定作用域：MapStruct 会把未限定的 String→String 方法套用到
 * 全部同型字段映射（实测生成物），fail_reason 与详情载荷全文会被误截断——经 qualifiedByName
 * 收口后仅 payloadPreview 一处生效，其余字段保持直传。
 */
@Mapper
public interface IntegrationConverter {

    /** 默认组件模型的生成实现获取入口（单测与装配同源） */
    IntegrationConverter INSTANCE = Mappers.getMapper(IntegrationConverter.class);

    /**
     * 死信实体 → 列表行出参。
     *
     * @param entity 死信实体，非空
     * @return 列表行出参（payloadBody 不出现，仅预览），非空
     */
    @Mapping(target = "payloadPreview", source = "payloadBody", qualifiedByName = "payloadPreview")
    DeadLetterVO toDeadLetterVO(DeadLetter entity);

    /**
     * 死信实体清单 → 列表行出参清单。
     *
     * @param entities 死信实体清单，非空（可为空清单）
     * @return 列表行出参清单，非 null
     */
    List<DeadLetterVO> toDeadLetterVOs(List<DeadLetter> entities);

    /**
     * 死信实体 → 详情出参（含载荷全文、处理人/备注/时间留痕）。
     *
     * @param entity 死信实体，非空
     * @return 详情出参，非空
     */
    DeadLetterDetailVO toDeadLetterDetailVO(DeadLetter entity);

    /**
     * 消费台账实体 → 行出参。
     *
     * @param entity 消费台账实体（received_event），非空
     * @return 行出参，非空
     */
    ReceivedEventVO toReceivedEventVO(ReceivedEvent entity);

    /**
     * 消费台账实体清单 → 行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 行出参清单，非 null
     */
    List<ReceivedEventVO> toReceivedEventVOs(List<ReceivedEvent> entities);

    /**
     * 投递记录实体 → 行出参（完成态由 completion_date 空/非空派生）。
     *
     * @param entity 投递记录实体，非空
     * @return 行出参（status=COMPLETED/INCOMPLETE），非空
     */
    @Mapping(target = "status", source = "completionDate")
    EventPublicationVO toEventPublicationVO(EventPublication entity);

    /**
     * 投递记录实体清单 → 行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 行出参清单，非 null
     */
    List<EventPublicationVO> toEventPublicationVOs(List<EventPublication> entities);

    /**
     * 契约台账实体 → 行出参。
     *
     * @param entity 契约台账实体，非空
     * @return 行出参，非空
     */
    EventRegistryVO toEventRegistryVO(EventRegistry entity);

    /**
     * 契约台账实体清单 → 行出参清单。
     *
     * @param entities 实体清单，非空（可为空清单）
     * @return 行出参清单，非 null
     */
    List<EventRegistryVO> toEventRegistryVOs(List<EventRegistry> entities);

    /**
     * 完成态派生：completion_date 非空 = COMPLETED，为空 = INCOMPLETE（框架完成标记的唯一判据）。
     *
     * @param completionDate 完成时刻，可空
     * @return COMPLETED 或 INCOMPLETE，非空
     */
    default String mapPublicationStatus(OffsetDateTime completionDate) {
        return completionDate == null
                ? MessagingConstants.PUBLICATION_STATUS_INCOMPLETE
                : MessagingConstants.PUBLICATION_STATUS_COMPLETED;
    }

    /**
     * 载荷预览：截取原文头部固定长度（列表页防大字段刷屏与最小暴露）。
     *
     * <p>以 {@code @Named} 限定，仅服务 {@code payloadPreview} 一处映射（限定的方法不再被
     * MapStruct 自动套用到其他 String 同型字段）。
     *
     * @param payloadBody 载荷原文，可空
     * @return 长度不超过预览上限的文本；入参为 null 返回 null
     */
    @Named("payloadPreview")
    default String mapPayloadPreview(String payloadBody) {
        return TextTruncate.truncate(payloadBody, MessagingConstants.DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH);
    }
}
