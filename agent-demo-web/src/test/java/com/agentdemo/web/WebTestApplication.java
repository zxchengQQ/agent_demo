package com.agentdemo.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web 模块测试启动类
 * 业务含义：为 @WebMvcTest 提供 @SpringBootConfiguration 入口
 * （web 模块本身无 @SpringBootApplication，启动类在 bootstrap 模块）
 */
@SpringBootApplication
public class WebTestApplication {
}
