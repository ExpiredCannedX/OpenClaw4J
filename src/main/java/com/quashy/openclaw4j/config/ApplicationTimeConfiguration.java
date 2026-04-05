package com.quashy.openclaw4j.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 提供应用级统一时间源，使 reminder、观测和未来其他时间敏感组件都能通过注入方式共享稳定时钟。
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationTimeConfiguration {

    /**
     * 返回默认系统时区时钟，作为生产环境的统一时间来源，并为测试替换留出清晰注入点。
     */
    @Bean
    public Clock applicationClock() {
        return Clock.systemDefaultZone();
    }
}
