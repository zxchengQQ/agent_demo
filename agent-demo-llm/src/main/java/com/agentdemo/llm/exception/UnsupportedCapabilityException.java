package com.agentdemo.llm.exception;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.capability.VisionChatModelProvider;

/**
 * LLM 厂商能力不支持异常（CR-002 Task-24 新增）
 * <p>
 * 业务含义：当编排层（{@link com.agentdemo.llm.registry.ModelFactory}）检测到当前激活的厂商
 * 未实现某能力接口（如 {@link VisionChatModelProvider}）时抛出此异常，
 * 错误信息明确包含厂商代码和缺失的能力名，避免隐式失败（对应 AC-021）。
 * </p>
 * <p>
 * 设计决策：继承 {@link BusinessException} 而非直接继承 RuntimeException，
 * 复用全局异常处理器的统一捕获与错误码体系；错误码使用
 * {@link ErrorCode#LLM_CAPABILITY_NOT_SUPPORTED}，与 {@link ErrorCode#LLM_PROVIDER_NOT_FOUND}
 * 区分两种不同的运行时配置异常。
 * </p>
 * <p>
 * 与 {@code BusinessException} 的关系说明：
 * <ul>
 *   <li>能力缺失本质上是配置错误（厂商未实现某能力接口），属于业务异常范畴</li>
 *   <li>继承 BusinessException 使其可被 GlobalExceptionHandler 统一处理，返回结构化错误响应</li>
 *   <li>封装为独立类型便于调用方按异常类型精确捕获（如视觉能力降级场景）</li>
 * </ul>
 * </p>
 */
public class UnsupportedCapabilityException extends BusinessException {

    /**
     * 厂商代码（如 "ark"、"bailian"）
     */
    private final String providerCode;

    /**
     * 缺失的能力名称（如 "vision"、"embedding"）
     */
    private final String capabilityName;

    /**
     * 构造能力不支持异常
     *
     * @param providerCode   厂商代码（禁止为 null）
     * @param capabilityName 缺失的能力名称（禁止为 null）
     */
    public UnsupportedCapabilityException(String providerCode, String capabilityName) {
        super(ErrorCode.LLM_CAPABILITY_NOT_SUPPORTED,
                String.format("LLM 厂商 [%s] 不支持能力 [%s]，请检查厂商实现类是否实现对应能力接口",
                        providerCode, capabilityName));
        this.providerCode = providerCode;
        this.capabilityName = capabilityName;
    }

    /**
     * 获取厂商代码
     *
     * @return 厂商代码
     */
    public String getProviderCode() {
        return providerCode;
    }

    /**
     * 获取缺失的能力名称
     *
     * @return 能力名称
     */
    public String getCapabilityName() {
        return capabilityName;
    }
}
