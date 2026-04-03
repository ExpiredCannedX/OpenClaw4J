package com.quashy.openclaw4j;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * OpenClaw4J 应用启动入口，负责激活组件扫描和当前阶段所需的集中配置绑定。
 */
@SpringBootApplication
@EnableConfigurationProperties(OpenClawProperties.class)
public class OpenClaw4JApplication {

    /**
     * 启动 Spring Boot 应用，让 direct-message 开发入口和统一 Agent 主链路都能按配置装配运行。
     */
    public static void main(String[] args) {
        SpringApplication.run(OpenClaw4JApplication.class, args);
    }

}
