package com.agentdemo.tools.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 工具模块通用配置
 * <p>
 * 业务含义：注册 RestTemplate Bean 供 HttpTool 构造注入使用（带连接/读取超时）。
 * 说明：HttpTool 同时保留无参构造（内部自建 RestTemplate），带参构造由 Spring
 * 通过 @Autowired 注入本 Bean，保证生产环境清洗链路生效。
 * </p>
 */
@Configuration
public class ToolsConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return new RestTemplate(factory);
    }
}
