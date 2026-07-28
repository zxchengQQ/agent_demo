package com.agentdemo.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * RAG 异步处理配置
 * <p>
 * 业务含义：为文档异步处理提供独立线程池，避免阻塞主线程。
 * 文档上传后通过 @Async 在后台执行解析、分块、向量化、入库流程。
 */
@Configuration
@EnableAsync
public class RagAsyncConfig {

    /**
     * RAG 文档处理专用线程池
     * <p>
     * 业务含义：核心线程数 2、最大 4，队列容量 50，
     * 队列满时由调用线程执行（CallerRunsPolicy 降级为同步）。
     */
    @Bean("ragTaskExecutor")
    public Executor ragTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rag-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
