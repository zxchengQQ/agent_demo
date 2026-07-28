package com.agentdemo.rag.entity;

/**
 * 文档处理状态枚举
 * <p>
 * 业务含义：标识文档从上传到入库的异步处理生命周期阶段，
 * 用于前端轮询展示处理进度和状态机流转控制。
 * </p>
 */
public enum DocumentStatus {
    /** 待处理：文档已上传，等待异步处理线程执行 */
    PENDING,
    /** 处理中：异步线程正在解析、分块、向量化 */
    PROCESSING,
    /** 已完成：文档已成功入库，可被检索 */
    COMPLETED,
    /** 失败：解析或向量化过程中发生异常 */
    FAILED
}
