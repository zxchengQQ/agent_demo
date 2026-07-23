# 阶段六：Agent 核心 任务执行计划

| 项 | 说明 |
|---|---|
| 阶段编号 | Phase-06 |
| 优先级 | P0（核心） |
| 状态 | 待执行 |
| 前置依赖 | 阶段一（工程骨架）、阶段二（common）、阶段三（llm）、阶段四（tools）、阶段五（memory） |
| 被依赖 | 阶段七（web）、阶段八（bootstrap，间接） |
| 验证粒度 | 阶段级（mvn compile + 单元测试） |

---

## 一、任务概述

实现 `agent-demo-agent` 模块，构建 Agent 核心能力，包括 Agent 抽象接口、单 Agent 实现（基于 LangChain4j AiServices 的 ReAct 循环）、场景化 Agent 定义。这是整个项目的核心模块，BaseAgent 接口是全链路关键抽象。

## 二、依赖关系

### 2.1 前置依赖
- **阶段一**：agent 模块的 pom.xml 与包结构
- **阶段二**：使用 `Result`、`ErrorCode`、`BusinessException`、`AgentType` 枚举
- **阶段三**：使用 `ModelFactory` 获取 ChatModel/StreamingChatModel
- **阶段四**：使用 `ToolRegistry` 获取工具列表
- **阶段五**：使用 `ChatMemoryManager` 获取会话记忆

### 2.2 被依赖（下游影响）
- **阶段七（web）**：web 层 Controller 调用 BaseAgent 接口
- **阶段八（bootstrap）**：启动时初始化 Agent Bean
- **后续多 Agent、工作流模块**：继承 BaseAgent 抽象
- **BaseAgent 接口签名变更属重大变更**，需同步评估 web 层、app 层所有调用方

## 三、任务清单

| 任务 ID | 任务 | 子任务数 | 产出物 |
|---|---|---|---|
| 6.1 | Agent 抽象接口 | 1 | BaseAgent.java |
| 6.2 | 单 Agent 实现 | 1 | SimpleAgent.java |
| 6.3 | ReAct 循环配置 | 1 | AgentConfig.java |
| 6.4 | 场景化 Agent | 2 | CodeAssistantAgent、GeneralAssistantAgent |
| 6.5 | 验证 | 1 | SimpleAgentTest.java |

## 四、子任务详情

### 4.1 任务 6.1：Agent 抽象接口 BaseAgent

- **目标**：定义 Agent 的统一抽象，所有 Agent 实现此接口
- **接口设计**：
  ```java
  public interface BaseAgent {
      // 同步对话
      String chat(String sessionId, String message);
      // 流式对话
      Flux<String> chatStream(String sessionId, String message);
      // 获取 Agent 类型
      AgentType getType();
      // 获取 Agent 名称
      String getName();
  }
  ```
- **设计原则**：
  - 接口最小化，仅包含必要方法
  - sessionId 贯穿所有方法，支持会话隔离
  - 同步与流式分离，调用方按需选择
- **调用方枚举**（接口变更全链路评估）：
  - web 层 `AgentController`：调用 chat / chatStream
  - app 层 `SceneService`：调用 chat（场景化编排）
- **变更影响**：新增/删除参数需同步 web 层 Controller、app 层 Service 的方法签名

### 4.2 任务 6.2：单 Agent 实现 SimpleAgent

- **目标**：基于 LangChain4j AiServices 实现单 Agent，具备 ReAct 循环、工具调用、记忆能力
- **实现要点**：
  - `@Service` + `@Component`
  - 注入 `ModelFactory`、`ToolRegistry`、`ChatMemoryManager`
  - 使用 `AiServices.builder(BaseAgent.class)` 构建代理实现
  - 绑定：
    - `chatLanguageModel(modelFactory.getDefaultChatModel())`
    - `tools(toolRegistry.listTools())`
    - `chatMemoryProvider(sessionId -> memoryManager.getMemory(sessionId))`
  - **核心逻辑注释**：
    - 注明 ReAct 循环的工作原理（思考-行动-观察）
    - 注明工具绑定策略（全量绑定 vs 按场景绑定）
    - 注明记忆隔离机制（sessionId 作为 memoryId）
- **示例代码框架**：
  ```java
  @Service
  public class SimpleAgent implements BaseAgent {
      private final BaseAgent agentDelegate;

      public SimpleAgent(ModelFactory modelFactory,
                        ToolRegistry toolRegistry,
                        ChatMemoryManager memoryManager) {
          // 业务含义：通过 AiServices 代理构建 Agent，LangChain4j 自动实现 ReAct 循环
          // 模型作为"大脑"，工具作为"手脚"，记忆保持上下文
          this.agentDelegate = AiServices.builder(BaseAgent.class)
              .chatLanguageModel(modelFactory.getDefaultChatModel())
              .streamingChatLanguageModel(modelFactory.getStreamingChatModel("default"))
              .tools(toolRegistry.listTools().toArray())
              .chatMemoryProvider(sessionId -> memoryManager.getMemory(sessionId))
              .build();
      }

      @Override
      public String chat(String sessionId, String message) {
          return agentDelegate.chat(sessionId, message);
      }
      // ... 其他方法
  }
  ```

### 4.3 任务 6.3：ReAct 循环配置 AgentConfig

- **目标**：集中配置 Agent 行为参数
- **配置项**：
  ```java
  @ConfigurationProperties(prefix = "agent")
  public class AgentConfig {
      private int maxIterations = 10;        // 最大 ReAct 循环次数
      private int chatMemoryWindowSize = 20; // 记忆窗口大小
      private String defaultSystemPrompt;    // 默认系统提示词
      private boolean enableLogging = true;  // 是否记录调用日志
  }
  ```
- **应用方式**：
  - maxIterations：通过 AiServices 的 `.build()` 配置（LangChain4j 支持）
  - chatMemoryWindowSize：传给 ChatMemoryManager
  - defaultSystemPrompt：加载自 `resources/prompts/default.txt`
- **核心逻辑注释**：注明 maxIterations 的业务含义（防止 Agent 无限循环消耗 Token）

### 4.4 任务 6.4：场景化 Agent

#### 子任务 6.4.1：CodeAssistantAgent
- **目标**：编程助手场景 Agent，使用 doubao-seed-2.0-code 模型
- **实现要点**：
  - 继承 SimpleAgent 或直接实现 BaseAgent
  - 模型：`modelFactory.getChatModel("code")`
  - 系统提示词：`resources/prompts/code-assistant.txt`（"你是一位资深 Java 工程师..."）
  - 工具：绑定 CalculatorTool、FileReadTool（编程相关工具）
- **调用方**：web 层按场景路由

#### 子任务 6.4.2：GeneralAssistantAgent
- **目标**：通用助手场景 Agent，使用 doubao-seed-2.0-pro 模型
- **实现要点**：
  - 模型：`modelFactory.getChatModel("chat")`
  - 系统提示词：`resources/prompts/general-assistant.txt`
  - 工具：绑定全部工具（TimeTool、HttpTool、CalculatorTool 等）
- **调用方**：web 层默认路由

### 4.5 任务 6.5：验证

- **目标**：验证 Agent 核心功能
- **测试内容**：
  - `SimpleAgentTest`：
    - Mock LLM 响应，验证 chat 方法返回非空
    - 验证工具调用：发送"计算 2+3"，验证触发 CalculatorTool
    - 验证多轮记忆：连续两轮对话，第二轮能引用第一轮内容
  - `AgentConfigTest`：验证配置绑定
- **前置条件**：
  - 火山引擎 API Key 已配置（或使用 Mock）
  - tools 模块与 memory 模块功能正常
- **验证命令**：`mvn test -pl agent-demo-agent`

## 五、关键接口设计

### 5.1 BaseAgent 接口签名（全链路核心，影响 web/app 层）
```java
public interface BaseAgent {
    String chat(String sessionId, String message);
    Flux<String> chatStream(String sessionId, String message);
    AgentType getType();
    String getName();
}
```
**调用方枚举**：
- web 层 `AgentController.chat(ChatRequest)` -> 调用 `agent.chat(sessionId, message)`
- web 层 `AgentController.stream(ChatRequest)` -> 调用 `agent.chatStream(sessionId, message)`
- app 层 `SceneService.execute(scene, message)` -> 按场景路由调用不同 Agent

**变更影响评估**：
- 新增参数：需同步 web 层 ChatRequest DTO、app 层 SceneService
- 删除 chatStream：影响 web 层 SSE 接口
- 返回类型变更：影响 web 层 ChatResponse DTO

### 5.2 AiServices 构建参数（内部实现，不影响外部）
```java
AiServices.builder(BaseAgent.class)
    .chatLanguageModel(...)
    .streamingChatLanguageModel(...)
    .tools(...)
    .chatMemoryProvider(...)
    .systemMessageProvider(...)
    .build();
```

## 六、验证标准

| 验证项 | 验证方式 | 通过标准 |
|---|---|---|
| 编译 | `mvn clean compile -pl agent-demo-agent` | BUILD SUCCESS |
| 接口定义 | 代码审查 | BaseAgent 接口符合设计 |
| 同步对话 | Mock 单元测试 | chat 返回非空字符串 |
| 流式对话 | Mock 单元测试 | chatStream 返回 Flux 可订阅 |
| 工具调用 | Mock 单元测试 | LLM 决策调用工具，工具执行成功 |
| 多轮记忆 | Mock 单元测试 | 第二轮可引用第一轮内容 |
| 场景化 Agent | 单元测试 | CodeAssistant 与 GeneralAssistant 模型不同 |
| 配置绑定 | 单元测试 | AgentConfig 字段正确加载 |

## 七、风险与注意事项

1. **BaseAgent 接口稳定性**：这是全链路核心抽象，接口一旦确定应避免频繁变更，变更需同步评估 web 层、app 层所有调用方
2. **AiServices 代理机制**：LangChain4j 的 AiServices 通过动态代理实现 BaseAgent 接口，需理解代理机制，避免在实现类中直接调用 this 导致绕过代理
3. **工具调用无限循环**：必须配置 maxIterations（默认 10），防止 Agent 反复调用工具不收敛消耗 Token
4. **记忆与代理的绑定**：chatMemoryProvider 必须按 sessionId 返回独立的 ChatMemory，否则会话间串扰
5. **流式与同步的模型差异**：流式使用 StreamingChatLanguageModel，同步使用 ChatLanguageModel，两者需分别配置
6. **系统提示词加载**：提示词文件放在 resources/prompts/ 下，通过 SystemMessageProvider 加载，避免硬编码在 Java 代码中
7. **场景化路由**：不同场景 Agent 绑定不同工具集，避免单个 Agent 工具过多影响 LLM 决策准确率
8. **异常透传**：Agent 内部异常（LLM 调用失败、工具执行失败）应抛出 BusinessException，由 web 层全局异常处理器统一处理

## 八、执行顺序

```
6.1 Agent 抽象接口 BaseAgent（通知 web/app 层调用方）
   ↓
6.3 ReAct 循环配置 AgentConfig
   ↓
6.2 单 Agent 实现 SimpleAgent
   ↓
6.4.1 CodeAssistantAgent
   ↓
6.4.2 GeneralAssistantAgent
   ↓
6.5 验证
```
