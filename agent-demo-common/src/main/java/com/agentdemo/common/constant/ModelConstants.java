package com.agentdemo.common.constant;

/**
 * 模型相关常量
 * <p>
 * 业务含义：集中管理火山引擎方舟模型的名称与 Base URL，禁止在调用方硬编码模型名。
 * 设计原则：所有模型名、Base URL 必须通过本常量类引用，便于全局替换。
 * </p>
 */
public final class ModelConstants {

    private ModelConstants() {
        // 工具类禁止实例化
    }

    /**
     * 火山引擎方舟 Coding Plan Base URL（OpenAI 兼容协议，按次计费）
     * 业务含义：使用 Coding Plan 专用地址而非标准 /api/v3，以消耗套餐额度而非按 Token 计费
     */
    public static final String ARK_CODING_PLAN_BASE_URL = "https://ark.cn-beijing.volces.com/api/coding/v3";

    /**
     * 火山引擎方舟标准 API Base URL（OpenAI 兼容协议，按 Token 计费）
     */
    public static final String ARK_STANDARD_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    /**
     * 模型场景 Key（用于 application.yml 中 ark.coding-plan.models 的配置项）
     */
    public static final String SCENE_CHAT = "chat";
    public static final String SCENE_CODE = "code";
    public static final String SCENE_LITE = "lite";

    /**
     * 火山引擎支持的模型名称
     */
    public static final String MODEL_DOUBAO_SEED_2_CODE = "doubao-seed-2.0-code";
    public static final String MODEL_DOUBAO_SEED_2_PRO = "doubao-seed-2.0-pro";
    public static final String MODEL_DOUBAO_SEED_2_LITE = "doubao-seed-2.0-lite";
    public static final String MODEL_MINIMAX_M27 = "minimax-m2.7";
    public static final String MODEL_GLM_52 = "glm-5.2";
    public static final String MODEL_KIMI_K27_CODE = "kimi-k2.7-code";
    public static final String MODEL_DEEPSEEK_V4_PRO = "deepseek-v4-pro";

    /**
     * 自动模式（由控制台智能选择模型）
     */
    public static final String MODEL_ARK_CODE_LATEST = "ark-code-latest";

    /**
     * 豆包 Embedding 模型（用于 RAG 与长期记忆向量化）
     */
    public static final String MODEL_DOUBAO_EMBEDDING = "doubao-embedding-large-text-240915";

    /**
     * 默认对话模型
     */
    public static final String DEFAULT_CHAT_MODEL = MODEL_DOUBAO_SEED_2_PRO;

    /**
     * 默认编程模型
     */
    public static final String DEFAULT_CODE_MODEL = MODEL_DOUBAO_SEED_2_CODE;
}
