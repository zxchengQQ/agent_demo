# 技术设计文档: 多 LLM 提供商支持（阿里百炼）

## 0. 设计概要 (Design Summary)

- **功能描述**：通过配置级切换（`llm.provider`）支持阿里百炼作为 LLM 提供商，保持火山引擎原有配置零改动，实现阿里百炼的同步对话、流式对话、Embedding 模型的创建与缓存复用。

- **影响范围**：
  - `agent-demo-llm`（核心实现：新增 `LlmProperties`、`BailianProperties`、`LlmProvider` 枚举；修改 `LlmConfig`、`ModelFactory`）
  - `agent-demo-common`（扩展 `ModelConstants`：新增阿里百炼模型常量）
  - `agent-demo-bootstrap`（配置项：新增 `llm.provider` + `bailian.*` 配置段）

- **技术难点**：
  - **提供商路由**：`ModelFactory` 需根据当前激活的提供商，在创建模型时路由到不同的配置源（ArkProperties / BailianProperties），同时保持缓存结构一致
  - **API Key 隔离校验**：切换提供商后只校验当前提供商的 API Key，不校验另一个
  - **向后兼容**：`llm.provider` 默认值为 `ark`，现有配置不受任何影响

- **依赖关系**：
  - 复用 `langchain4j-open-ai` 适配器（阿里百炼 OpenAI 兼容协议）
  - 复用 `ModelFactory` 的缓存机制（`ConcurrentHashMap`）
  - 复用 `ArkProperties` 的配置绑定模式（`@ConfigurationProperties`）
  - 无需新增任何第三方依赖

## 1. 架构概览 (Architecture Overview)

### 1.1 模块交互关系

```
agent-demo-llm (LLM 接入层)
    │
    ├── config/
    │   ├── LlmProperties          [NEW]  llm.provider 提供商选择配置
    │   ├── LlmProvider            [NEW]  提供商枚举 (ARK / BAILIAN)
    │   ├── ArkProperties          [保持] 火山引擎配置（零改动）
    │   ├── BailianProperties      [NEW]  阿里百炼配置
    │   └── LlmConfig              [修改] 注册新增的配置属性绑定
    │
    └── factory/
        └── ModelFactory           [修改] 根据 LlmProvider 路由创建逻辑
                │
                ├── LlmProvider.ARK    → ArkProperties → 创建模型（现有逻辑）
                └── LlmProvider.BAILIAN → BailianProperties → 创建模型（新增逻辑）

agent-demo-common (公共组件)
    └── constant/
        └── ModelConstants         [修改] 新增阿里百炼模型常量

agent-demo-bootstrap (启动模块)
    └── resources/
        └── application.yml        [修改] 新增 llm.provider + bailian.* 配置段
```

### 1.2 数据流向

**模型获取流程（运行时）：**

```mermaid
sequenceDiagram
    participant Agent as Agent 层
    participant MF as ModelFactory
    participant LP as LlmProperties
    participant AP as ArkProperties
    participant BP as BailianProperties
    participant Cache as 模型缓存

    Agent->>MF: getChatModel(scene)
    MF->>LP: getProvider()
    alt provider == ARK
        MF->>AP: getModelName(scene)
        AP-->>MF: modelName
        MF->>Cache: computeIfAbsent(modelName, createChatModel)
        MF->>AP: getBaseUrl / getApiKey / getTimeout...
        MF->>MF: OpenAiChatModel.builder()...build()
        Cache-->>MF: 缓存实例
    else provider == BAILIAN
        MF->>BP: getModelName(scene)
        BP-->>MF: modelName
        MF->>Cache: computeIfAbsent(modelName, createBailianChatModel)
        MF->>BP: getBaseUrl / getApiKey / getTimeout...
        MF->>MF: OpenAiChatModel.builder()...build()
        Cache-->>MF: 缓存实例
    end
    MF-->>Agent: ChatModel 实例
```

**配置切换流程：**

```mermaid
flowchart TD
    A[修改 application.yml] --> B{llm.provider?}
    B -->|ark| C[加载 ArkProperties]
    B -->|bailian| D[加载 BailianProperties]
    C --> E[重启应用]
    D --> E
    E --> F[ModelFactory 初始化]
    F --> G{provider}
    G -->|ark| H[validateArkApiKey]
    G -->|bailian| I[validateBailianApiKey]
    H --> J[所有 LLM 调用使用火山引擎]
    I --> K[所有 LLM 调用使用阿里百炼]
```

## 2. 详细设计 (Detailed Design)

### 2.1 新增类设计

#### 2.1.1 `LlmProvider` 枚举

**包路径**：`com.agentdemo.llm.config.LlmProvider`

```java
package com.agentdemo.llm.config;

/**
 * LLM 提供商枚举
 * <p>
 * 业务含义：通过配置项 llm.provider 选择当前使用的 LLM 提供商，
 * 支持 ark（火山引擎方舟）和 bailian（阿里百炼）两种取值。
 * 默认值为 ark（向后兼容）。
 * </p>
 */
public enum LlmProvider {
    ARK,
    BAILIAN
}
```

#### 2.1.2 `LlmProperties` 配置类

**包路径**：`com.agentdemo.llm.config.LlmProperties`

```java
package com.agentdemo.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 提供商选择配置
 * <p>
 * 业务含义：绑定 application.yml 中 llm.provider 配置项，
 * 用于选择当前使用的 LLM 提供商。
 * 默认值为 ark（火山引擎方舟），以保持向后兼容。
 * </p>
 * <p>
 * 配置示例：
 * <pre>
 * llm:
 *   provider: ark    # ark | bailian
 * </pre>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /**
     * LLM 提供商
     * 业务含义：指定当前使用的 LLM 提供商，支持 ark（火山引擎方舟）和 bailian（阿里百炼）
     * 默认值 ARK 确保不配置时不影响现有功能
     */
    private LlmProvider provider = LlmProvider.ARK;
}
```

#### 2.1.3 `BailianProperties` 配置类

**包路径**：`com.agentdemo.llm.config.BailianProperties`

```java
package com.agentdemo.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 阿里百炼 OpenAI 兼容协议配置
 * <p>
 * 业务含义：绑定 application.yml 中 bailian.* 配置项，
 * 提供阿里百炼 LLM 接入所需的 Base URL、API Key、模型列表等参数。
 * 阿里百炼通过 OpenAI 兼容协议（/compatible-mode/v1）提供服务。
 * </p>
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * bailian:
 *   base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *   api-key: ${BAILIAN_API_KEY}
 *   default-model: deepseek-v4-flash
 *   models:
 *     chat: deepseek-v4-flash
 *     code: deepseek-v4-flash
 *     lite: deepseek-v4-flash
 *   timeout: 60s
 *   max-retries: 3
 *   temperature: 0.7
 *   embedding-model: text-embedding-v4
 * </pre>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "bailian")
public class BailianProperties {

    /**
     * 阿里百炼 OpenAI 兼容协议 Base URL
     * 业务含义：使用 /compatible-mode/v1 路径，兼容 OpenAI 协议格式，
     * 可通过 LangChain4j openai4j 适配器直接接入
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * API Key（从环境变量 BAILIAN_API_KEY 注入，禁止硬编码）
     */
    private String apiKey;

    /**
     * 默认模型名称（当 scene 未命中 models 时回退使用）
     */
    private String defaultModel = "deepseek-v4-flash";

    /**
     * 按场景配置的模型映射
     * key: 场景标识（chat/code/lite 等）
     * value: 模型名称（如 deepseek-v4-flash）
     */
    private Map<String, String> models = new HashMap<>();

    /**
     * 调用超时时间
     */
    private Duration timeout = Duration.ofSeconds(60);

    /**
     * 最大重试次数（网络异常时自动重试）
     */
    private int maxRetries = 3;

    /**
     * 温度参数（0.0-1.0，值越高回复越发散，值越低越确定）
     */
    private double temperature = 0.7;

    /**
     * Embedding 模型名称
     * 业务含义：RAG 文档向量化使用的阿里百炼 Embedding 模型，
     * 独立于对话模型配置，便于单独指定
     */
    private String embeddingModel = "text-embedding-v4";

    /**
     * 根据场景获取模型名称
     * 业务含义：优先从 models Map 查找，未命中时回退到 defaultModel
     *
     * @param scene 场景标识（chat/code/lite 等）
     * @return 模型名称
     */
    public String getModelName(String scene) {
        if (scene == null || scene.isEmpty()) {
            return defaultModel;
        }
        return models.getOrDefault(scene, defaultModel);
    }
}
```

#### 2.1.4 `ThinkingStreamingChatModel` 接口（CR-001 新增）

**包路径**：`com.agentdemo.llm.thinking.ThinkingStreamingChatModel`

```java
package com.agentdemo.llm.registry;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 思考流式对话模型抽象接口
 * <p>
 * 业务含义：统一火山引擎方舟和阿里百炼的思考流式模型调用方式，
 * 为 Agent 层的深度思考模式和任务拆解功能提供与提供商无关的抽象。
 * </p>
 * <p>
 * 设计决策：将原 {@link ArkThinkingStreamingChatModel} 中对外暴露的流式调用方法抽象为接口，
 * 使 {@link ModelFactory#getThinkingStreamingChatModel()} 可以返回不同提供商的实现，
 * 解除返回类型对火山引擎具体类的硬编码依赖。
 * </p>
 */
public interface ThinkingStreamingChatModel {

  /**
   * 单轮思考流式对话（不带工具调用）
   *
   * @param messages 消息列表
   * @param handler  流式回调处理器
   */
  void stream(List<ChatMessage> messages, ThinkingStreamHandler handler);

  /**
   * ReAct 思考流式对话（带工具调用）
   *
   * @param messages  消息列表
   * @param toolsJson 工具 JSON Schema 描述
   * @param handler   流式回调处理器
   */
  void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler);
}
```

#### 2.1.5 `BailianThinkingStreamingChatModel` 类（CR-001 新增）

**包路径**：`com.agentdemo.llm.thinking.BailianThinkingStreamingChatModel`

**设计说明**：
- 与 `ArkThinkingStreamingChatModel` 采用相同的实现策略：**原生 HTTP 直连**阿里百炼 OpenAI 兼容端点，手动解析 SSE 流
- 原因：LangChain4j 的 `OpenAiStreamingChatModel` 适配器不会透传 `reasoning_content` 扩展字段，而阿里百炼 DeepSeek 系列模型通过 OpenAI 兼容协议会返回 `delta.reasoning_content`，必须手动解析
- 实现方式：复用 `ArkThinkingStreamingChatModel` 中成熟的 SSE 解析逻辑（`HttpURLConnection` + `ObjectMapper`），仅修改 Base URL、API Key、模型名称的来源为 `BailianProperties`
- 请求体构建：与方舟保持一致（`model`、`stream`、`stream_options.include_usage`、`messages`、`tools`），**不发送 `thinking.type=enabled`**（阿里百炼 DeepSeek 模型通过模型名称自身触发思考能力，无需额外字段）

```java
package com.agentdemo.llm.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;

import java.time.Duration;
import java.util.List;

/**
 * 阿里百炼思考流式对话模型实现
 * <p>
 * 业务含义：通过原生 HTTP 直连阿里百炼 OpenAI 兼容协议端点，
 * 解析 SSE 流中的 reasoning_content（推理内容）和 content（正式回复），
 * 为阿里百炼提供商提供与方舟一致的深度思考能力。
 * </p>
 */
public class BailianThinkingStreamingChatModel implements ThinkingStreamingChatModel {

  private final String baseUrl;
  private final String apiKey;
  private final String modelName;
  private final Duration timeout;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public BailianThinkingStreamingChatModel(String baseUrl, String apiKey,
                                           String modelName, Duration timeout) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.modelName = modelName;
    this.timeout = timeout;
  }

  @Override
  public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
    // 复用 ArkThinkingStreamingChatModel 的 SSE 解析逻辑
    // 仅变更请求 URL 和认证信息来源
  }

  @Override
  public void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler) {
    // 复用 ArkThinkingStreamingChatModel 的 ReAct SSE 解析逻辑
    // 仅变更请求 URL 和认证信息来源
  }
}
```

#### 2.1.6 能力矩阵接口设计（CR-002 新增）

**背景**：CR-001 完成后，`ModelFactory` 累积了 7 处 `if (provider == BAILIAN) {...} else {...}` 硬编码分支；`ArkThinkingStreamingChatModel` 与 `BailianThinkingStreamingChatModel` 代码重复度高达 95%。CR-002 引入能力矩阵 + 提供商策略 + 注册表模式，使新增厂商零核心改动。

**设计原则**：
- **接口隔离原则 (ISP)**：按能力拆分为多个独立接口，厂商按需实现，未实现的能力在运行时明确报错
- **开闭原则 (OCP)**：`ModelFactory` 对扩展开放（新增 Provider）、对修改关闭（零 if-else 分支）
- **批判性参考**：参考 `多LLM提供商设计模式.md` 中"能力矩阵 = 抽象基类"的建议，但本项目各厂商能力差异仅在配置值，抽象基类会退化为空壳，因此**仅对思考流式模型采用抽象基类**，其他能力采用接口 + 厂商直接实现

**能力接口清单**：

| 接口名 | 包路径 | 方法 | 说明 |
|:---|:---|:---|:---|
| `LlmProviderConfig` | `com.agentdemo.llm.registry` | `getBaseUrl/getApiKey/getTimeout/getMaxRetries/getTemperature/getModelName/getEmbeddingModel/getVisionModel` | 配置访问契约，`ArkProperties`/`BailianProperties` 实现 |
| `ChatModelProvider` | `com.agentdemo.llm.registry` | `ChatModel getChatModel(String scene)` | 同步对话能力 |
| `StreamingChatModelProvider` | `com.agentdemo.llm.registry` | `StreamingChatModel getStreamingChatModel(String scene)` | 流式对话能力 |
| `ThinkingStreamingChatModelProvider` | `com.agentdemo.llm.registry` | 继承 `ThinkingStreamingChatModel` | 思考流式能力（保持调用方零改动） |
| `EmbeddingModelProvider` | `com.agentdemo.llm.registry` | `EmbeddingModel getEmbeddingModel()` | 向量化能力 |
| `VisionChatModelProvider` | `com.agentdemo.llm.registry` | `ChatModel getVisionChatModel()` | 视觉对话能力 |
| `LlmServiceProvider` | `com.agentdemo.llm.registry` | `String getProviderCode()`；聚合上述 5 个能力接口 | 厂商策略聚合接口 |

**关键代码示意**：

```java
// 配置访问契约
public interface LlmProviderConfig {
    String getBaseUrl();
    String getApiKey();
    Duration getTimeout();
    int getMaxRetries();
    double getTemperature();
    String getModelName(String scene);
    String getEmbeddingModel();
    String getVisionModel();
}

// 厂商策略聚合接口（聚合所有能力，便于 Spring 一次注入 List<LlmServiceProvider>）
public interface LlmServiceProvider extends ChatModelProvider, StreamingChatModelProvider,
        ThinkingStreamingChatModelProvider, EmbeddingModelProvider, VisionChatModelProvider {
    String getProviderCode();  // 如 "ark"、"bailian"，与 LlmProvider.code 匹配
}
```

> **设计决策（ISP 落地）**：`LlmServiceProvider` 聚合所有能力接口。厂商按需实现：若某厂商不支持视觉能力，可不实现 `VisionChatModelProvider`，但 `LlmServiceProvider` 聚合接口要求全部实现。**取舍**：当前两家厂商（火山引擎、阿里百炼）均支持全部能力，因此实现聚合接口；未来若有厂商能力不全，可让该厂商不实现 `LlmServiceProvider`，而实现所需的能力子接口，由 `ModelFactory` 通过 `instanceof` 检测并降级处理（详见 2.2.4 节）。

#### 2.1.7 AbstractThinkingStreamingChatModel 抽象基类（CR-002 新增）

**包路径**：`com.agentdemo.llm.thinking.AbstractThinkingStreamingChatModel`

**设计说明**：
- 采用**模板方法模式**，上提 `ArkThinkingStreamingChatModel` 与 `BailianThinkingStreamingChatModel` 中重复度 95% 的通用逻辑（HTTP 调用、SSE 解析、回调分发、`ObjectMapper` 初始化等）
- 子类仅需实现 `buildRequestBody(List<ChatMessage> messages, String toolsJson)` 差异化方法
- 火山引擎子类发送 `thinking.type=enabled` 字段；阿里百炼子类不发送（通过模型名称触发思考能力）

**关键代码示意**：

```java
public abstract class AbstractThinkingStreamingChatModel implements ThinkingStreamingChatModelProvider {
    protected final String baseUrl;
    protected final String apiKey;
    protected final String modelName;
    protected final Duration timeout;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractThinkingStreamingChatModel(String baseUrl, String apiKey,
                                                  String modelName, Duration timeout) { /* 赋值 */ }

    // 模板方法：通用的流式调用流程
    @Override
    public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
        String body = buildRequestBody(messages, null);
        executeStream(body, handler);
    }

    @Override
    public void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler) {
        String body = buildRequestBody(messages, toolsJson);
        executeStream(body, handler);
    }

    // 子类差异化实现：请求体构建（如是否包含 thinking.type=enabled）
    protected abstract String buildRequestBody(List<ChatMessage> messages, String toolsJson);

    // 通用实现：HTTP 调用 + SSE 解析 + 回调分发（从原 ArkThinkingStreamingChatModel 上提）
    private void executeStream(String body, ThinkingStreamHandler handler) { /* ... */ }

    // 通用实现：SSE 行解析（从原 ArkThinkingStreamingChatModel 上提）
    protected void parseSseLine(String line, ThinkingStreamHandler handler,
                                 StringBuilder fullResponse,
                                 Map<Integer, ToolCall> toolCallAccumulator) { /* ... */ }
}
```

> **预期效果**：`ArkThinkingStreamingChatModel` 和 `BailianThinkingStreamingChatModel` 各自约从 460/454 行降至 ≤ 180 行，行级重复率从 95% 降至 ≤ 30%（对应 AC-020）。

#### 2.1.8 ArkLlmServiceProvider / BailianLlmServiceProvider 厂商策略实现（CR-002 新增）

**包路径**：`com.agentdemo.llm.provider.ArkLlmServiceProvider` / `BailianLlmServiceProvider`

**设计说明**：
- 将原 `ModelFactory` 中的 `createArkXxx` / `createBailianXxx` 方法迁移到对应的 Provider 实现类
- 每个 Provider 持有对应的 `LlmProviderConfig`（即 `ArkProperties` / `BailianProperties`）和**内部缓存 Map**
- 标注 `@Component`，Spring 自动注入到 `ModelFactory` 的 `List<LlmServiceProvider>` 中
- `getProviderCode()` 返回厂商代码（`"ark"` / `"bailian"`），与 `LlmProvider.code` 匹配

**缓存迁移说明**：原 `ModelFactory` 中的 `chatModelCache`、`streamingModelCache`、`thinkingStreamingModelCache`、`visionModelCache` 迁移到各 Provider 内部。Provider 为 Spring 单例，缓存语义不变（对应 AC-022）。

### 2.2 修改类设计

#### 2.2.1 `LlmConfig` 修改

```java
@Configuration
@EnableConfigurationProperties({ArkProperties.class, LlmProperties.class, BailianProperties.class})
public class LlmConfig {
}
```

- 新增注册 `LlmProperties` 和 `BailianProperties` 配置属性绑定
- `ArkProperties` 保持不变

#### 2.2.2 `ModelFactory` 修改

**核心改动**：

1. 注入 `LlmProperties` 和 `BailianProperties`
2. 每个创建方法增加阿里百炼分支
3. 新增 `validateBailianApiKey()` 方法
4. 新增阿里百炼专用的创建方法（`createBailianChatModel`、`createBailianStreamingChatModel`、`createBailianEmbeddingModel`、`createBailianThinkingStreamingChatModel`）
5. ~~思考流式模型在阿里百炼模式下抛出 `UnsupportedOperationException`~~（已于 CR-001 改造为返回 `ThinkingStreamingChatModel` 接口并根据提供商路由）

**关键代码结构**：

```java
@Component
public class ModelFactory {

    private final ArkProperties arkProperties;
    private final LlmProperties llmProperties;
    private final BailianProperties bailianProperties;

    // 缓存结构保持不变（按 modelName 缓存，与提供商无关）
    private final ConcurrentHashMap<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>();
    // CR-001: 缓存类型改为接口，支持不同提供商的实现实例
    private final ConcurrentHashMap<String, ThinkingStreamingChatModel> thinkingStreamingModelCache = new ConcurrentHashMap<>();
    private volatile EmbeddingModel embeddingModel;

    public ModelFactory(ArkProperties arkProperties, LlmProperties llmProperties,
                        BailianProperties bailianProperties) {
        this.arkProperties = arkProperties;
        this.llmProperties = llmProperties;
        this.bailianProperties = bailianProperties;
    }

    // ========== 对外公开方法 ==========

    public ChatModel getChatModel(String scene) {
        String modelName = getModelName(scene);
        return chatModelCache.computeIfAbsent(modelName, this::createChatModel);
    }

    public ChatModel getDefaultChatModel() {
        return getChatModel(null);
    }

    public StreamingChatModel getStreamingChatModel(String scene) {
        String modelName = getModelName(scene);
        return streamingModelCache.computeIfAbsent(modelName, this::createStreamingChatModel);
    }

    public StreamingChatModel getDefaultStreamingChatModel() {
        return getStreamingChatModel(null);
    }

    public ThinkingStreamingChatModel getThinkingStreamingChatModel() {
        // CR-001: 根据当前提供商路由到对应的思考流式模型实现
        String modelName = getModelName(null);
        return thinkingStreamingModelCache.computeIfAbsent(modelName, this::createThinkingStreamingChatModel);
    }

    public EmbeddingModel getEmbeddingModel() {
        if (embeddingModel == null) {
            synchronized (this) {
                if (embeddingModel == null) {
                    if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
                        embeddingModel = createBailianEmbeddingModel();
                    } else {
                        embeddingModel = createArkEmbeddingModel();
                    }
                }
            }
        }
        return embeddingModel;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 根据当前激活的提供商获取模型名称
     * 业务含义：根据 llm.provider 从对应的配置对象中获取模型名称，
     * 优先从 models Map 查找，未命中时回退到 defaultModel
     *
     * @param scene 场景标识
     * @return 模型名称
     */
    private String getModelName(String scene) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return bailianProperties.getModelName(scene);
        }
        return arkProperties.getModelName(scene);
    }

    // ========== 模型创建方法 ==========

    private ChatModel createChatModel(String modelName) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return createBailianChatModel(modelName);
        }
        return createArkChatModel(modelName);
    }

    private ChatModel createArkChatModel(String modelName) {
        validateArkApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .apiKey(arkProperties.getApiKey())
                .modelName(modelName)
                .temperature(arkProperties.getTemperature())
                .timeout(arkProperties.getTimeout())
                .maxRetries(arkProperties.getMaxRetries())
                .build();
    }

    private ChatModel createBailianChatModel(String modelName) {
        validateBailianApiKey();
        return OpenAiChatModel.builder()
                .baseUrl(bailianProperties.getBaseUrl())
                .apiKey(bailianProperties.getApiKey())
                .modelName(modelName)
                .temperature(bailianProperties.getTemperature())
                .timeout(bailianProperties.getTimeout())
                .maxRetries(bailianProperties.getMaxRetries())
                .build();
    }

    private ThinkingStreamingChatModel createThinkingStreamingChatModel(String modelName) {
        if (llmProperties.getProvider() == LlmProvider.BAILIAN) {
            return createBailianThinkingStreamingChatModel(modelName);
        }
        return createArkThinkingStreamingChatModel(modelName);
    }

    private ThinkingStreamingChatModel createArkThinkingStreamingChatModel(String modelName) {
        validateArkApiKey();
        return new ArkThinkingStreamingChatModel(
                arkProperties.getBaseUrl(),
                arkProperties.getApiKey(),
                modelName,
                arkProperties.getTimeout());
    }

    private ThinkingStreamingChatModel createBailianThinkingStreamingChatModel(String modelName) {
        validateBailianApiKey();
        return new BailianThinkingStreamingChatModel(
                bailianProperties.getBaseUrl(),
                bailianProperties.getApiKey(),
                modelName,
                bailianProperties.getTimeout());
    }

    // 流式模型、Embedding 模型同理...
    // 各创建方法先校验当前提供商的 API Key，再构建模型

    private void validateArkApiKey() {
        if (arkProperties.getApiKey() == null || arkProperties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "ARK_API_KEY 未配置，请通过环境变量注入");
        }
    }

    private void validateBailianApiKey() {
        if (bailianProperties.getApiKey() == null || bailianProperties.getApiKey().isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_API_KEY_INVALID,
                    "BAILIAN_API_KEY 未配置，请通过环境变量注入");
        }
    }
}
```

**设计说明**：
- 通过 `getModelName(scene)` 私有方法统一路由，对外部方法隐藏提供商差异
- 各创建方法（`createChatModel`、`createStreamingChatModel` 等）内部根据 `llmProperties.getProvider()` 再次路由到具体实现
- `createArkXxx` 保留原有完整逻辑，零改动；`createBailianXxx` 使用阿里百炼的配置参数

#### 2.2.3 `ModelConstants` 修改

```java
public final class ModelConstants {

    // ... 现有火山引擎常量保持不变 ...

    // ========== 阿里百炼模型常量 ==========

    /** 阿里百炼 DeepSeek V4 Flash 模型（默认对话模型） */
    public static final String MODEL_BAILIAN_DEEPSEEK_V4_FLASH = "deepseek-v4-flash";

    /** 阿里百炼 Embedding 模型 */
    public static final String MODEL_BAILIAN_EMBEDDING = "text-embedding-v4";
}
```

#### 2.2.4 `ModelFactory` 注册表重构（CR-002 新增）

**背景**：CR-001 完成后，`ModelFactory` 内累积了 7 处 `if (provider == BAILIAN) {...} else {...}` 硬编码分支（`getModelName`、`createChatModel`、`createStreamingChatModel`、`createEmbeddingModel`、`createThinkingStreamingChatModel`、`getVisionModelName`、`createVisionChatModel`），新增厂商需修改全部 7 处，违反开闭原则。

**重构核心**：
1. 注入 `List<LlmServiceProvider>`，Spring 自动收集所有标注 `@Component` 的厂商策略实现
2. 启动时构建 `Map<String, LlmServiceProvider>` 注册表（key = providerCode）
3. 运行时通过 `llmProperties.getProvider().getCode()` 查找对应 Provider，委托调用
4. 缓存（`chatModelCache` 等）迁移到各 Provider 内部，`ModelFactory` 不再持有任何缓存
5. 能力检测：通过 `instanceof` 判断 Provider 是否实现某能力接口，未实现时抛 `UnsupportedCapabilityException`

**关键代码结构**：

```java
@Component
public class ModelFactory {
    private final LlmProperties llmProperties;
    private final Map<String, LlmServiceProvider> providerRegistry;  // 按 providerCode 索引

    public ModelFactory(LlmProperties llmProperties, List<LlmServiceProvider> providers) {
        this.llmProperties = llmProperties;
        this.providerRegistry = providers.stream()
            .collect(Collectors.toUnmodifiableMap(
                LlmServiceProvider::getProviderCode, Function.identity()));
    }

    // ========== 对外公开方法（签名全部保持不变）==========

    public ChatModel getChatModel(String scene) {
        return getProvider().getChatModel(scene);
    }

    public ChatModel getDefaultChatModel() {
        return getChatModel(null);
    }

    public StreamingChatModel getStreamingChatModel(String scene) {
        return getProvider().getStreamingChatModel(scene);
    }

    public StreamingChatModel getDefaultStreamingChatModel() {
        return getStreamingChatModel(null);
    }

    public ThinkingStreamingChatModel getThinkingStreamingChatModel() {
        return getProvider();  // Provider 即 ThinkingStreamingChatModel 实例
    }

    public EmbeddingModel getEmbeddingModel() {
        return getProvider().getEmbeddingModel();
    }

    public ChatModel getVisionChatModel() {
        LlmServiceProvider provider = getProvider();
        // ISP 检测：厂商未实现 VisionChatModelProvider 时抛出明确异常（对应 AC-021）
        if (!(provider instanceof VisionChatModelProvider)) {
            throw new UnsupportedCapabilityException(provider.getProviderCode(), "vision");
        }
        return ((VisionChatModelProvider) provider).getVisionChatModel();
    }

    // ========== 私有辅助方法 ==========

    /**
     * 根据当前激活的提供商 code 从注册表查找 Provider
     * 业务含义：替代原 if-else 路由，新增厂商时仅需新增 Provider 实现并标注 @Component
     */
    private LlmServiceProvider getProvider() {
        String code = llmProperties.getProvider().getCode();
        LlmServiceProvider provider = providerRegistry.get(code);
        if (provider == null) {
            throw new BusinessException(ErrorCode.LLM_PROVIDER_NOT_FOUND,
                "未找到 LLM 提供商: " + code + "，已注册: " + providerRegistry.keySet());
        }
        return provider;
    }
}
```

**重构前后对比**：

| 维度 | 重构前（CR-001 后） | 重构后（CR-002） |
|:---|:---|:---|
| 文件行数 | 约 410 行 | 约 80 行 |
| 厂商硬编码分支 | 7 处 `if (provider == BAILIAN) {...} else {...}` | 0 处 |
| 新增厂商改动点 | 修改 7 处分支 + 新增 `createXxx` 方法 | 新增 1 个 `LlmServiceProvider` 实现类，零核心修改 |
| 缓存持有者 | `ModelFactory` 内部 4 个 `ConcurrentHashMap` | 各 Provider 内部，对外行为不变 |
| 构造器参数 | `(ArkProperties, LlmProperties, BailianProperties)` | `(LlmProperties, List<LlmServiceProvider>)` |
| 公开方法签名 | — | 全部保持不变（向前兼容） |

**风险与缓解**：
- **构造器签名变更**：影响所有直接 new 的位置。**缓解**：项目内全部通过 Spring 注入，无直接 new；`ModelFactoryTest` 需重写（详见 CR-002 任务计划 Task-24）。
- **缓存语义漂移**：缓存迁移到 Provider 后，若 Provider 非单例则缓存失效。**缓解**：Provider 标注 `@Component`，Spring 默认单例；Task-20/21/24 中通过多次调用断言验证。

### 2.3 配置设计

#### application.yml 配置变更

```yaml
# ========== 新增：LLM 提供商选择 ==========
llm:
  provider: ark    # ark（火山引擎）| bailian（阿里百炼），默认 ark

# ========== 火山引擎（保持不变） ==========
ark:
  coding-plan:
    base-url: https://ark.cn-beijing.volces.com/api/coding/v3
    api-key: ${ARK_API_KEY}
    default-model: doubao-seed-2.0-code
    models:
      chat: doubao-seed-2.0-pro
      code: doubao-seed-2.0-code
      lite: doubao-seed-2.0-lite
    timeout: 60s
    max-retries: 3
    temperature: 0.7

# ========== 新增：阿里百炼配置 ==========
bailian:
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  api-key: ${BAILIAN_API_KEY}
  default-model: deepseek-v4-flash
  models:
    chat: deepseek-v4-flash
    code: deepseek-v4-flash
    lite: deepseek-v4-flash
  timeout: 60s
  max-retries: 3
  temperature: 0.7
  embedding-model: text-embedding-v4
```

### 2.4 异常处理设计

| 异常场景 | 对应 AC | 触发条件 | 处理方式 | 错误码 |
|---------|---------|---------|---------|--------|
| BAILIAN_API_KEY 未配置 | AC-006 | `provider=bailian` 且 `apiKey` 为空 | `validateBailianApiKey()` 抛出 BusinessException | 5004 (LLM_API_KEY_INVALID) |
| 阿里百炼 API Key 无效 | AC-007 | 阿里百炼返回 401 | LangChain4j 自动抛出异常，GlobalExceptionHandler 捕获 | 5001 (LLM_CALL_FAILED) |
| 阿里百炼服务不可用 | AC-008 | 连接超时/网络不可达 | LangChain4j 超时异常，全局异常处理 | 5001/5002 |
| 不支持的提供商值 | AC-009 | `llm.provider` 配置为非法值 | Spring Boot 绑定失败，启动时报错 | 系统启动失败 |
| base-url 未配置 | AC-010 | `bailian.base-url` 未配置 | 使用默认值，不报错 | 无 |
| 切换后不校验 ARK | AC-011 | `provider=bailian` 且 `ARK_API_KEY` 未设置 | 不校验，正常调用阿里百炼 | 无 |

### 2.5 缓存策略

- 缓存架构不变：`ConcurrentHashMap<String, ChatModel>` 按 modelName 缓存
- 阿里百炼的模型实例与火山引擎共用同一缓存池，但由于 modelName 不同（如 `deepseek-v4-flash` vs `doubao-seed-2.0-code`），不会冲突
- 切换提供商后，因 modelName 不同，缓存自动隔离，无需清空
- 思考流式模型缓存类型改为 `ThinkingStreamingChatModel` 接口（CR-001），按 modelName 缓存不同提供商的实现实例，缓存隔离机制与上述一致

## 3. 技术决策说明

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 提供商路由方式 | ~~ModelFactory 内 if-else 分支~~（v1.0）→ ~~抽象为 `ThinkingStreamingChatModel` 接口~~（CR-001）→ **注册表 + Provider 策略模式**（CR-002） | v1.0 仅 2 家厂商时简单；CR-001 后厂商扩展性低（7 处硬编码分支）；CR-002 引入注册表，新增厂商零核心改动（对应 AC-018/AC-019） |
| API Key 校验 | 各自独立校验方法 | 每个创建方法只校验当前提供商，符合 BR-LLM-012（CR-002 后校验逻辑迁移到 Provider 内部） |
| 阿里百炼配置前缀 | `bailian.*` | 独立前缀，与现有 `ark.coding-plan.*` 无冲突，扩展性好 |
| 思考模式处理 | ~~阿里百炼模式抛出 UnsupportedOperationException~~（v1.0）→ 抽象为 `ThinkingStreamingChatModel` 接口，百炼新增原生 HTTP 实现（CR-001）→ **抽取 `AbstractThinkingStreamingChatModel` 抽象基类，模板方法复用 SSE 解析逻辑**（CR-002） | CR-001 解决能力抽象问题；CR-002 解决代码重复问题（重复率从 95% 降至 ≤ 30%，对应 AC-020） |
| 能力接口设计 | **按 ISP 拆分为 6 个独立接口**（CR-002） | 厂商按需实现，未实现的能力在运行时通过 `instanceof` 检测并抛 `UnsupportedCapabilityException`（对应 AC-021）；批判性参考设计模式文档"能力矩阵 = 抽象基类"，但本项目能力差异仅在配置值，抽象基类会退化为空壳 |
| 环境变量 | `BAILIAN_API_KEY` | 与 `ARK_API_KEY` 命名一致，符合项目规范 |
| 无需新增依赖 | 复用 `langchain4j-open-ai` | 阿里百炼提供 OpenAI 兼容协议，无需引入新依赖 |

## 4. 验收标准映射 (AC Mapping)

| 验收标准 | 实现方式 | 验证方法 |
|---------|---------|---------|
| **AC-001**: 阿里百炼同步对话正常 | `ModelFactory.getChatModel()` → `createBailianChatModel()` | 集成测试：配置 `llm.provider=bailian`，调用同步对话 API |
| **AC-002**: 阿里百炼流式对话正常 | `ModelFactory.getStreamingChatModel()` → `createBailianStreamingChatModel()` | 集成测试：配置 `llm.provider=bailian`，调用流式对话 API |
| **AC-003**: 阿里百炼 Embedding 正常 | `ModelFactory.getEmbeddingModel()` → `createBailianEmbeddingModel()` | 集成测试：配置 `llm.provider=bailian`，调用 Embedding |
| **AC-004**: 回切火山引擎不受影响 | 默认 `llm.provider=ark`，走原有逻辑 | 回归测试：确认火山引擎原有功能正常 |
| **AC-005**: 阿里百炼场景路由正常 | `BailianProperties.getModelName(scene)` 按 Map 查找 | 单元测试：配置场景模型，验证路由结果 |
| **AC-006**: BAILIAN_API_KEY 未配置 | `validateBailianApiKey()` 抛出异常 | 单元测试：apiKey 为 null 时调用验证 |
| **AC-007**: API Key 无效 | 阿里百炼返回 401，LangChain4j 异常处理 | 集成测试：使用无效 Key 调用 |
| **AC-008**: 服务不可用 | 超时/连接异常，全局异常处理 | 集成测试：使用无效 baseUrl 调用 |
| **AC-009**: 不支持的提供商值 | Spring Boot 配置绑定失败 | 启动测试：配置非法值观察启动行为 |
| **AC-010**: base-url 未配置 | `BailianProperties.baseUrl` 有默认值 | 单元测试：验证默认值 |
| **AC-011**: 切换后不校验 ARK | `validateBailianApiKey()` 不检查 `ARK_API_KEY` | 集成测试：无 ARK_API_KEY 时使用 bailian |
| **AC-012**: API Key 环境变量注入 | `@Value("${BAILIAN_API_KEY}")` 或 `application.yml` 中 `${BAILIAN_API_KEY}` | 代码审查 |
| **AC-013**: 模型名常量化 | `ModelConstants` 中定义常量 | 代码审查 |
| **AC-014**: 缓存复用 | `ConcurrentHashMap.computeIfAbsent()` 复用 | 单元测试：多次调用返回同一实例 |
| **AC-015**: 阿里百炼深度思考正常 | `ModelFactory.getThinkingStreamingChatModel()` → `createBailianThinkingStreamingChatModel()` → `BailianThinkingStreamingChatModel.stream()` | 集成测试：配置 `llm.provider=bailian`，启用 `enableThinking=true`，验证 SSE 流式输出包含 reasoning_content 和 content |
| **AC-016**: 阿里百炼任务拆解完整可用 | `PlanAgent.chatTaskBreakdownStream()` → `TaskBreakdownStream` 三阶段均使用 `getThinkingStreamingChatModel()` 接口 | 集成测试：配置 `llm.provider=bailian`，启用 `enableTaskBreakdown=true`，验证 task_plan/task_start/task_complete 全流程 |
| **AC-017**: 阿里百炼深度思考 ReAct 工具调用正常 | `BailianThinkingStreamingChatModel.stream(messages, toolsJson, handler)` 解析 tool_calls 并执行 ReAct 循环 | 集成测试：百炼模式下发送需要调用工具的消息，验证 tool_calls 解析、工具执行、结果回填完整 |
| **AC-018**: 新增厂商零核心改动（CR-002） | `ModelFactory` 注入 `List<LlmServiceProvider>`，按 `providerCode` 路由，新增厂商仅需新增 `@Component` 实现 | 扩展性测试：新增 `MockLlmServiceProvider`，配置 `llm.provider=mock`，验证 `ModelFactory.java` 无修改 |
| **AC-019**: ModelFactory 无厂商硬编码分支（CR-002） | 移除全部 7 处 `if (provider == BAILIAN) {...} else {...}` 分支 | 静态扫描：`ModelFactory.java` 中 `if.*provider.*==.*BAILIAN` 模式匹配数为 0 |
| **AC-020**: 思考流式模型代码重复率 ≤ 30%（CR-002） | `AbstractThinkingStreamingChatModel` 上提通用逻辑，子类仅保留 `buildRequestBody` 差异 | 代码扫描：行级重复行检测，重复率 ≤ 30% |
| **AC-021**: 能力缺失时明确报错（CR-002） | `ModelFactory.getVisionChatModel()` 通过 `instanceof VisionChatModelProvider` 检测，未实现抛 `UnsupportedCapabilityException` | 单元测试：Mock Provider 未实现某能力接口，验证异常抛出 |
| **AC-022**: 缓存复用语义保持不变（CR-002） | 缓存迁移到 Provider 内部（Spring 单例），对外行为不变 | 单元测试：同一 provider + 同一 modelName 多次调用返回同一实例 |

## 5. 文件变更清单

| 操作 | 文件路径 | 说明 |
|------|---------|------|
| 新增 | `agent-demo-llm/.../config/LlmProvider.java` | 提供商枚举 |
| 新增 | `agent-demo-llm/.../config/LlmProperties.java` | 提供商选择配置 |
| 新增 | `agent-demo-llm/.../config/BailianProperties.java` | 阿里百炼配置属性 |
| 修改 | `agent-demo-llm/.../config/LlmConfig.java` | 注册新增配置属性绑定 |
| 修改 | `agent-demo-llm/.../factory/ModelFactory.java` | 新增阿里百炼路由逻辑 |
| 修改 | `agent-demo-common/.../constant/ModelConstants.java` | 新增阿里百炼模型常量 |
| 修改 | `agent-demo-bootstrap/.../application.yml` | 新增 `llm.provider` + `bailian.*` 配置段 |
| 新增（CR-001） | `agent-demo-llm/.../factory/ThinkingStreamingChatModel.java` | 思考流式模型抽象接口 |
| 新增（CR-001） | `agent-demo-llm/.../factory/BailianThinkingStreamingChatModel.java` | 阿里百炼思考流式模型实现（原生 HTTP 直连 + SSE 解析） |
| 修改（CR-001） | `agent-demo-llm/.../factory/ModelFactory.java` | `getThinkingStreamingChatModel()` 返回类型改为接口；新增百炼思考模型创建逻辑；缓存类型改为接口 |
| 修改（CR-001） | `agent-demo-llm/.../factory/ArkThinkingStreamingChatModel.java` | 实现 `ThinkingStreamingChatModel` 接口 |
| 修改（CR-001） | `agent-demo-agent/.../core/TaskBreakdownStream.java` | 局部变量类型从 `ArkThinkingStreamingChatModel` 改为 `ThinkingStreamingChatModel`（3 处） |
| 新增（CR-002） | `agent-demo-llm/.../factory/LlmProviderConfig.java` | 配置访问契约接口 |
| 新增（CR-002） | `agent-demo-llm/.../factory/ChatModelProvider.java` | 同步对话能力接口 |
| 新增（CR-002） | `agent-demo-llm/.../factory/StreamingChatModelProvider.java` | 流式对话能力接口 |
| 新增（CR-002） | `agent-demo-llm/.../factory/ThinkingStreamingChatModelProvider.java` | 思考流式能力接口（继承 `ThinkingStreamingChatModel`） |
| 新增（CR-002） | `agent-demo-llm/.../factory/EmbeddingModelProvider.java` | 向量化能力接口 |
| 新增（CR-002） | `agent-demo-llm/.../factory/VisionChatModelProvider.java` | 视觉对话能力接口 |
| 新增（CR-002） | `agent-demo-llm/.../factory/LlmServiceProvider.java` | 厂商策略聚合接口 |
| 新增（CR-002） | `agent-demo-llm/.../factory/AbstractThinkingStreamingChatModel.java` | 思考流式模型抽象基类（模板方法） |
| 新增（CR-002） | `agent-demo-llm/.../factory/ArkLlmServiceProvider.java` | 火山引擎厂商策略实现 |
| 新增（CR-002） | `agent-demo-llm/.../factory/BailianLlmServiceProvider.java` | 阿里百炼厂商策略实现 |
| 修改（CR-002） | `agent-demo-llm/.../config/LlmProvider.java` | 新增 `code` 字段（如 `ARK("ark")`、`BAILIAN("bailian")`） |
| 修改（CR-002） | `agent-demo-llm/.../config/LlmProperties.java` | 新增 `getProviderCode()` 派生方法 |
| 修改（CR-002） | `agent-demo-llm/.../config/ArkProperties.java` | 实现 `LlmProviderConfig` 接口；新增 `getEmbeddingModel()` 方法 |
| 修改（CR-002） | `agent-demo-llm/.../config/BailianProperties.java` | 实现 `LlmProviderConfig` 接口 |
| 修改（CR-002） | `agent-demo-llm/.../factory/ModelFactory.java` | 重构为注册表路由；移除 7 处硬编码分支；构造器改为 `(LlmProperties, List<LlmServiceProvider>)` |
| 修改（CR-002） | `agent-demo-llm/.../factory/ArkThinkingStreamingChatModel.java` | 改为继承 `AbstractThinkingStreamingChatModel`，仅保留 `buildRequestBody` |
| 修改（CR-002） | `agent-demo-llm/.../factory/BailianThinkingStreamingChatModel.java` | 改为继承 `AbstractThinkingStreamingChatModel`，仅保留 `buildRequestBody` |
| 新增（CR-002） | `agent-demo-llm/.../exception/UnsupportedCapabilityException.java` | 能力未支持异常（对应 AC-021） |
| 修改（CR-002） | `agent-demo-common/.../exception/ErrorCode.java` | 新增 `LLM_PROVIDER_NOT_FOUND`、`LLM_CAPABILITY_NOT_SUPPORTED` 错误码 |

---

## 变更日志

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v1.0 | 2026-07-30 | 初始版本 — 多 LLM 提供商支持（阿里百炼）技术方案设计 |

### CR-001: 阿里百炼深度思考与任务拆解支持 (2026-07-31)
**影响范围**: 接口抽象 / ModelFactory 路由 / TaskBreakdownStream 适配 / 新增实现类
**变更内容摘要**:
- [新增] `ThinkingStreamingChatModel` 接口：抽象 `stream(messages, handler)` 和 `stream(messages, toolsJson, handler)` 两个方法
- [新增] `BailianThinkingStreamingChatModel` 类：原生 HTTP 直连阿里百炼 OpenAI 兼容端点，复用方舟 SSE 解析逻辑，手动解析 `delta.reasoning_content` 和 `delta.content`
- [修改] `ModelFactory.getThinkingStreamingChatModel()`: 返回类型从 `ArkThinkingStreamingChatModel` 改为 `ThinkingStreamingChatModel`；移除 BAILIAN 模式异常拦截；新增 `createBailianThinkingStreamingChatModel()` 方法
- [修改] `ModelFactory.thinkingStreamingModelCache`: 泛型类型从 `ArkThinkingStreamingChatModel` 改为 `ThinkingStreamingChatModel`
- [修改] `TaskBreakdownStream`: `executeSubTaskWithReAct()`、`streamResponse()` 等方法的局部变量类型改为接口
- [修改] `ArkThinkingStreamingChatModel`: 实现 `ThinkingStreamingChatModel` 接口

### CR-002: agent-demo-llm 模块重构 —— 能力矩阵 + 提供商策略 + 注册表 (2026-08-04)
**影响范围**: 契约层（能力接口）/ 抽象基类层（思考模型模板方法）/ 厂商实现层（Provider 策略）/ 编排层（ModelFactory 注册表重构）
**变更原因**: CR-001 完成后，`ModelFactory` 累积 7 处 `if (provider == BAILIAN) {...} else {...}` 硬编码分支，新增厂商扩展性低；`ArkThinkingStreamingChatModel` 与 `BailianThinkingStreamingChatModel` 代码重复度高达 95%，维护成本高。
**变更内容摘要**:
- [新增] `LlmProviderConfig` 接口：统一 baseUrl/apiKey/timeout/maxRetries/temperature/getModelName 等配置访问方式（对应 2.1.6 节）
- [新增] 能力接口（ISP 拆分）：`ChatModelProvider`、`StreamingChatModelProvider`、`ThinkingStreamingChatModelProvider`（继承 `ThinkingStreamingChatModel`）、`EmbeddingModelProvider`、`VisionChatModelProvider`
- [新增] `LlmServiceProvider` 聚合接口：聚合上述 5 个能力接口 + `getProviderCode()`，便于 Spring 一次注入 `List<LlmServiceProvider>`
- [新增] `AbstractThinkingStreamingChatModel` 抽象基类：模板方法模式，上提 HTTP 调用/SSE 解析/回调分发等通用逻辑，子类仅实现 `buildRequestBody` 差异（对应 2.1.7 节）
- [新增] `ArkLlmServiceProvider`、`BailianLlmServiceProvider` 厂商策略实现：迁移原 `ModelFactory.createArkXxx` / `createBailianXxx` 逻辑，标注 `@Component` 自动注入（对应 2.1.8 节）
- [新增] `UnsupportedCapabilityException`：能力未支持异常（对应 AC-021）
- [修改] `LlmProvider` 枚举：新增 `code` 字段（如 `ARK("ark")`、`BAILIAN("bailian")`），用于与 `LlmServiceProvider.getProviderCode()` 匹配
- [修改] `LlmProperties`：新增 `getProviderCode()` 派生方法
- [修改] `ArkProperties`、`BailianProperties`：实现 `LlmProviderConfig` 接口（方法已存在，仅声明 implements）
- [修改] `ModelFactory`：构造器改为 `(LlmProperties, List<LlmServiceProvider>)`；注入注册表 `Map<String, LlmServiceProvider>`；移除全部 7 处硬编码分支；缓存迁移到 Provider 内部；新增 `instanceof` 能力检测（对应 2.2.4 节）
- [修改] `ArkThinkingStreamingChatModel`、`BailianThinkingStreamingChatModel`：改为继承 `AbstractThinkingStreamingChatModel`，仅保留 `buildRequestBody` 差异化实现，代码重复率从 95% 降至 ≤ 30%（对应 AC-020）
- [新增] 错误码 `LLM_PROVIDER_NOT_FOUND`、`LLM_CAPABILITY_NOT_SUPPORTED`

**新增验收标准**: AC-018（新增厂商零核心改动）、AC-019（无厂商硬编码分支）、AC-020（思考模型重复率 ≤ 30%）、AC-021（能力缺失明确报错）、AC-022（缓存复用语义不变）

**关联任务计划**: [多LLM提供商支持-阿里百炼_变更任务_CR-002.md](多LLM提供商支持-阿里百炼_变更任务_CR-002.md)（Task-17 ~ Task-25，预计 380 分钟）

**批判性参考说明**：本次设计参考 `多LLM提供商设计模式.md`，但根据本项目实际（进程内调用、2 家厂商、能力差异仅在配置值）做了以下裁剪：
1. 五层架构裁剪为三层（契约/厂商实现/编排），不采纳独立部署与凭证加解密
2. "能力矩阵 = 抽象基类"调整为"能力矩阵 = 接口（ISP）"，仅对思考流式模型采用抽象基类
3. 注册表复用 Spring `List<LlmServiceProvider>` 自动注入，不自研注册表
