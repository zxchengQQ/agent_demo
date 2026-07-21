package com.agentdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Agent 示例项目启动类
 * <p>
 * 业务含义：Spring Boot 应用入口，扫描 com.agentdemo 包下所有组件。
 * - @EnableScheduling：启用定时任务（会话超时清理）
 * - @EnableConfigurationProperties：启用配置属性绑定
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.agentdemo")
@EnableScheduling
@EnableConfigurationProperties
public class AgentDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentDemoApplication.class, args);
    }
}
