# RAG 知识库问答 - CR-003 完成报告

## 功能信息
- **功能名称**: RAG 知识库问答
- **变更编号**: CR-003（知识库动态 Tool 注册）
- **执行日期**: 2026-07-31
- **执行方式**: TDD（Red-Green-Refactor）

## 变更概述

原有单 Tool + 参数化调用模式下，LLM 需指定知识库名称作为参数，存在 LLM 幻觉生成未知知识库名称的风险。本次变更为每个知识库独立注册为 Tool，由 LLM 通过 Function Calling 选择具体 Tool，消除知识库名称传递风险。

核心技术决策：使用 ByteBuddy（而非原方案的 CGLIB）在运行时动态生成带 `@Tool` 注解的知识库工具类，因为 CGLIB/JDK 动态代理生成的方法不会继承 `@Tool` 注解（`@Tool` 无 `@Inherited`），LangChain4j 的 `ToolSpecifications.toolSpecificationsFrom(Object)` 无法识别代理方法。

## 已完成任务清单

| 任务 | 标题 | 状态 | 测试数 |
| :--- | :--- | :--- | :--- |
| Task-1 | 改造 ToolRegistry 支持动态注册/注销 | ✅ | 8 |
| Task-2 | 重构 KnowledgeRetrieverTool 为核心检索逻辑 | ✅ | 14 |
| Task-3 | 新增 KnowledgeBaseToolFactory 动态工具工厂 | ✅ | 6 |
| Task-4 | 新增 KnowledgeBaseToolRegistrar 生命周期管理 | ✅ | 7 |
| Task-5 | 改造 KnowledgeBaseService 联动 Tool 生命周期 | ✅ | 6 |
| Task-6 | 验证 SimpleAgent 懒加载绑定动态 Tool | ✅ | 3 |
| Task-7 | 验证系统启动批量注册流程 | ✅ | 7（与 Task-4 合并） |
| Task-8 | 更新文档与最终验证 | ✅ | 全量回归 |

## TDD 循环记录

### Task-1: 改造 ToolRegistry 支持动态注册/注销

#### RED 阶段
- 创建 `ToolRegistryTest`，编写 8 个测试用例：动态注册、注销、不存在工具名、同名多工具移除、按类名获取等

#### GREEN 阶段
- `ToolRegistry.java`：新增 `unregisterTool(String toolName)` 方法（按方法名匹配移除）
- 新增 `getToolCount()` 别名方法
- 提取公共 `findToolMethods(Class<?>)` 方法

#### 结果
- 8 个测试全部通过

### Task-2: 重构 KnowledgeRetrieverTool 为核心检索逻辑

#### RED 阶段
- 新增 `searchByKbId` 系列测试（正常检索、知识库不存在、空知识库、无匹配、服务异常）
- 新增 `searchKnowledge` 不再标注 `@Tool` 注解的测试
- 原有 `searchKnowledge` 测试补充 `findById` stub

#### GREEN 阶段
- 移除 `searchKnowledge` 的 `@Tool` 注解，标记 `@Deprecated`
- 新增 `searchByKbId(String kbId, String query)` 方法
- `searchKnowledge` 内部委托给 `searchByKbId`

#### 结果
- 14 个测试全部通过（含 4 个新增 `searchByKbId` 测试 + 1 个 `@Tool` 注解移除验证）

### Task-3: 新增 KnowledgeBaseToolFactory 动态工具工厂

#### RED 阶段
- 创建 `KnowledgeBaseToolFactoryTest`，编写 6 个测试用例：@Tool 注解验证、方法委托、命名规则、LangChain4j 兼容性

#### GREEN 阶段
- 创建 `KnowledgeBaseToolFactory.java`，使用 ByteBuddy 动态生成带 `@Tool` 注解的类
- `SearchInterceptor` 拦截方法调用，委托给 `KnowledgeRetrieverTool.searchByKbId`
- 新增 ByteBuddy 依赖（BOM + RAG pom.xml）

#### 遇到的问题
- **问题**: 原方案使用 CGLIB，但反编译 LangChain4j `ToolSpecifications` 确认其扫描 `Method.isAnnotationPresent(Tool.class)`，CGLIB 代理方法不继承 `@Tool` 注解
- **解决**: 改用 ByteBuddy，在 `defineMethod` 后通过 `annotateMethod` 直接写入 `@Tool` 注解

#### 结果
- 6 个测试全部通过，含 LangChain4j `ToolSpecifications.toolSpecificationsFrom()` 兼容性验证

### Task-4: 新增 KnowledgeBaseToolRegistrar 生命周期管理

#### RED 阶段
- 创建 `KnowledgeBaseToolRegistrarTest`，编写 7 个测试用例：空知识库、批量注册、异常隔离、单个注册/注销

#### GREEN 阶段
- 创建 `KnowledgeBaseToolRegistrar.java`，实现 `ApplicationRunner`
- `run()` 方法：启动时遍历 `findAll()` 批量注册
- `registerToolForKb()`：创建 Tool 并注册，异常不抛出
- `unregisterToolForKb()`：按 kbId 构造方法名注销
- 新增 `agent-demo-rag` 对 `agent-demo-tools` 的依赖

#### 遇到的问题
- **问题**: 测试 `runShouldContinueWhenSingleRegistrationFails` 中 `verify(toolRegistry, never()).register(any())` 失败
- **原因**: Mockito 的 `any()` 匹配器在 `thenThrow` 后仍可能匹配到其他调用
- **解决**: 改为 `verify(toolRegistry, times(1)).register(tool2)` 验证只有成功的 Tool 被注册

#### 结果
- 7 个测试全部通过

### Task-5: 改造 KnowledgeBaseService 联动 Tool 生命周期

#### RED 阶段
- 更新 `KnowledgeBaseServiceTest`：新增 `toolRegistrar` Mock，验证 `create()` 后调用 `registerToolForKb`，`delete()` 前调用 `unregisterToolForKb`

#### GREEN 阶段
- `KnowledgeBaseService`：构造函数新增 `KnowledgeBaseToolRegistrar` 参数
- `create()`：保存后调用 `toolRegistrar.registerToolForKb(saved)`
- `delete()`：校验存在性后、级联删除前调用 `toolRegistrar.unregisterToolForKb(id)`

#### 结果
- 6 个测试全部通过

### Task-6: 验证 SimpleAgent 懒加载绑定动态 Tool

#### RED 阶段
- 创建 `SimpleAgentTest`，编写 3 个测试用例：首次初始化、Tool 数量变化后重建、数量不变时复用

#### GREEN 阶段
- `SimpleAgent`：新增 `lastToolCount` 字段
- `getDelegate()`：检测 `toolRegistry.getToolCount()` 变化，变化时重建 delegate

#### 遇到的问题
- **问题**: 测试中通过匿名子类暴露 `toolRegistry` 字段，编译报"找不到符号"
- **解决**: 改为显式定义 `TestableSimpleAgent` 静态内部类，继承 `SimpleAgent` 并暴露 `getToolRegistry()`

#### 结果
- 3 个测试全部通过

## 文件变更清单

### 新增文件
| 文件 | 说明 |
| :--- | :--- |
| `agent-demo-rag/.../retriever/KnowledgeBaseToolFactory.java` | ByteBuddy 动态生成带 @Tool 注解的知识库工具类 |
| `agent-demo-rag/.../retriever/KnowledgeBaseToolRegistrar.java` | ApplicationRunner 启动批量注册 + 生命周期联动 |
| `agent-demo-rag/.../retriever/KnowledgeBaseToolFactoryTest.java` | 工厂测试（6 个用例） |
| `agent-demo-rag/.../retriever/KnowledgeBaseToolRegistrarTest.java` | 注册器测试（7 个用例） |
| `agent-demo-agent/.../single/SimpleAgentTest.java` | Agent 懒加载重建测试（3 个用例） |
| `agent-demo-tools/.../registry/ToolRegistryTest.java` | 动态注册/注销测试（8 个用例） |

### 修改文件
| 文件 | 变更说明 |
| :--- | :--- |
| `agent-demo-tools/.../registry/ToolRegistry.java` | 新增 `unregisterTool`、`getToolCount`、`findToolMethods` |
| `agent-demo-rag/.../retriever/KnowledgeRetrieverTool.java` | 移除 `@Tool`，新增 `searchByKbId`，`searchKnowledge` 标记 `@Deprecated` |
| `agent-demo-rag/.../retriever/KnowledgeRetrieverToolTest.java` | 新增 `searchByKbId` 测试，适配 `findById` stub |
| `agent-demo-rag/.../service/KnowledgeBaseService.java` | 注入 `KnowledgeBaseToolRegistrar`，`create`/`delete` 联动注册/注销 |
| `agent-demo-rag/.../service/KnowledgeBaseServiceTest.java` | 新增 `toolRegistrar` Mock 和验证 |
| `agent-demo-agent/.../single/SimpleAgent.java` | 新增 `lastToolCount` 检测，Tool 变化后重建 delegate |
| `agent-demo-rag/pom.xml` | 新增 `byte-buddy` 和 `agent-demo-tools` 依赖 |
| `agent-demo-bom/pom.xml` | 新增 `bytebuddy.version` 版本管理 |
| `KNOWLEDGE_BASE.md` | 更新 RAG 模块描述、技术栈、变更日志 |
| `RAG知识库问答_技术方案.md` | CGLIB -> ByteBuddy，更新代码示例、技术决策、时序图 |

## 测试结果

| 测试类 | 测试数 | 结果 |
| :--- | :--- | :--- |
| `ToolRegistryTest` | 8 | ✅ 全部通过 |
| `KnowledgeRetrieverToolTest` | 14 | ✅ 全部通过 |
| `KnowledgeBaseToolFactoryTest` | 6 | ✅ 全部通过 |
| `KnowledgeBaseToolRegistrarTest` | 7 | ✅ 全部通过 |
| `KnowledgeBaseServiceTest` | 6 | ✅ 全部通过 |
| `SimpleAgentTest` | 3 | ✅ 全部通过 |
| `SimpleAgentStreamingTest` | 2 | ✅ 全部通过（回归） |
| **合计** | **46** | **✅ 全部通过** |

> 注：日志中的 ERROR 输出为测试用例预期的异常场景（如"向量数据库连接失败"测试），非真实错误。

## 验收标准检查

| AC | 描述 | 状态 | 验证方式 |
| :--- | :--- | :--- | :--- |
| AC-033 | 创建知识库时自动注册 Tool | ✅ | `KnowledgeBaseServiceTest.createShouldReturnKnowledgeBase` 验证 `registerToolForKb` 被调用 |
| AC-034 | 删除知识库时自动注销 Tool | ✅ | `KnowledgeBaseServiceTest.deleteShouldCascadeRemoveDocumentsAndVectors` 验证 `unregisterToolForKb` 被调用 |
| AC-035 | 系统启动时批量注册 Tool | ✅ | `KnowledgeBaseToolRegistrarTest.runShouldRegisterToolsForAllExistingKnowledgeBases` 验证批量注册 |

## 遇到的问题和解决方案

1. **CGLIB 代理无法被 LangChain4j 识别**：反编译确认 `ToolSpecifications` 扫描 `Method.isAnnotationPresent(Tool.class)`，CGLIB 代理方法不继承注解。改为 ByteBuddy 直接在生成的方法上写入 `@Tool` 注解。

2. **Mockito `never()` 验证失败**：`thenThrow` 场景下 `any()` 匹配器行为与预期不符。改为验证成功 Tool 的注册次数 `times(1)`。

3. **JDK 版本问题**：系统默认 JDK 1.8，项目需要 JDK 17。需通过 `$env:JAVA_HOME` 指定 JDK 17 路径运行 Maven。

4. **模块依赖缺失**：`agent-demo-rag` 缺少对 `agent-demo-tools` 的依赖，导致 `KnowledgeBaseToolRegistrar` 无法引用 `ToolRegistry`。在 pom.xml 中新增依赖。

5. **测试子类访问权限**：匿名子类重写方法编译报错，改为显式定义 `TestableSimpleAgent` 静态内部类。

## 下一步建议

1. **集成测试**：编写端到端集成测试，验证创建知识库 -> 上传文档 -> Agent 对话检索完整流程
2. **性能测试**：大量知识库（>50）场景下 ByteBuddy 类生成和 Tool 列表遍历的性能评估
3. **Tool 去重**：当前 `ToolRegistry.register` 为追加模式，重复注册同一知识库会产生重复 Tool，后续可考虑幂等处理
4. **前端适配**：前端知识库选择器逻辑可简化，因 LLM 不再需要知识库名称参数，选择器仅影响提示词注入
