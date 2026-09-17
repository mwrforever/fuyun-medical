package com.fuyun.patient.config;

import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.controller.CardAccountController;
import com.fuyun.patient.controller.CardController;
import com.fuyun.patient.controller.DuplicateMergeController;
import com.fuyun.patient.controller.HealthController;
import com.fuyun.patient.controller.PatientController;
import com.fuyun.patient.controller.PatientIdentifierController;
import com.fuyun.patient.controller.PrivacyController;
import com.fuyun.patient.convert.PatientConverter;
import com.fuyun.patient.gateway.IdentityMediaGateway;
import com.fuyun.patient.gateway.ManualMediaAdapter;
import com.fuyun.patient.properties.PatientCardProperties;
import com.fuyun.patient.properties.PatientCryptoProperties;
import com.fuyun.patient.properties.PatientEmpiProperties;
import com.fuyun.patient.service.impl.CardAccountServiceImpl;
import com.fuyun.patient.service.impl.HealthSummaryServiceImpl;
import com.fuyun.patient.service.impl.MergeRecordServiceImpl;
import com.fuyun.patient.service.impl.PatientIdentifierServiceImpl;
import com.fuyun.patient.service.impl.PatientMatchingServiceImpl;
import com.fuyun.patient.service.impl.PatientRegistrationServiceImpl;
import com.fuyun.patient.service.impl.PatientServiceImpl;
import com.fuyun.patient.service.impl.PossibleDuplicateServiceImpl;
import com.fuyun.patient.service.impl.PrivacyAuthServiceImpl;
import com.fuyun.patient.service.impl.PrivacyMaskServiceImpl;
import com.fuyun.patient.service.impl.PrivacyServiceImpl;
import com.fuyun.patient.service.impl.VisitCardServiceImpl;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M02 患者域 Web/服务装配集中点（backend 宪法 B.1 装配归 app：com.fuyun.patient 不在
 * 组件扫描范围，本类由 fuyun-app PatientConfig @Import 生效；加密构件经 PatientCryptoConfig 引入）。
 */
@Configuration
@EnableConfigurationProperties({PatientCryptoProperties.class, PatientEmpiProperties.class, PatientCardProperties.class
})
@Import({
    PatientCryptoConfig.class,
    PatientServiceImpl.class,
    PatientIdentifierServiceImpl.class,
    PrivacyAuthServiceImpl.class,
    PatientMatchingServiceImpl.class,
    PatientRegistrationServiceImpl.class,
    PossibleDuplicateServiceImpl.class,
    MergeRecordServiceImpl.class,
    PatientCacheService.class,
    PrivacyMaskServiceImpl.class,
    CardAccountServiceImpl.class,
    VisitCardServiceImpl.class,
    HealthSummaryServiceImpl.class,
    PatientIdentifierController.class,
    PatientController.class,
    DuplicateMergeController.class,
    CardAccountController.class,
    CardController.class,
    HealthController.class,
    PrivacyServiceImpl.class,
    PrivacyController.class
})
public class PatientWebConfig {

    /**
     * 介质核验适配器 Bean：以接口类型暴露（调用方禁注入实现类，B.2-2；审查 M4 返回类型修正）。
     *
     * @return 手工兜底适配器（IdentityMediaGateway 当前唯一实现）
     */
    @Bean
    public IdentityMediaGateway manualMediaAdapter() {
        return new ManualMediaAdapter();
    }

    /**
     * 患者域 MapStruct 转换器 Bean（接口不可经 @Import 注册，Mappers.getMapper 装配，IntegrationWebConfig 同款）。
     *
     * @return 患者域转换器
     */
    @Bean
    public PatientConverter patientConverter() {
        return Mappers.getMapper(PatientConverter.class);
    }
}
