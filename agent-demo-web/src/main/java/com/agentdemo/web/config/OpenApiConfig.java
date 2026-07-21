package com.agentdemo.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger OpenAPI 配置
 * <p>
 * 业务含义：生成 API 文档，访问地址 http://localhost:8080/swagger-ui.html
 * </p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agentDemoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Agent 示例项目 API")
                        .version("1.0.0")
                        .description("基于 Java + LangChain4j + 火山引擎方舟的 AI Agent 示例项目")
                        .contact(new Contact()
                                .name("agent-demo")
                                .url("https://github.com/agent-demo")));
    }
}
