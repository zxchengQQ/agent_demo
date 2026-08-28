# AI Agent 代码审查报告: Agent 上下文工程优化 (agent-context-engineering)

| 字段 | 内容 |
|------|------|
| 审查人 | ai-agent-code-review（代码审查技能） |
| 日期 | 2026-08-28 |
| 审查范围基准 | 完成报告文件变更清单（11 产品文件 + 3 Prompt 模板 + 2 新增类） |
| 关联文档 | `specs/features/20260828_agent-context-engineering/`（需求/技术方案/任务规划）、`docs/dev-records/20260828_agent-context-engineering_dev_report.md` |

## 0. 审查结论

**修复后通过（Fix-Required-Passed）**

代码交付物整体可靠：冻结契约、附件记忆流、滚动压缩、状态栏注入四项核心机制实现正确且测试充分（新增 9 测试类全绿，全模块编译通过，实际启动验证成功）。审查发现 1 个 **Critical（启动装配失败）已在审查前修复并验证**；1 个 **Important（EDD 评估方式缺口）需决策**；若干 Minor 建议。

> 启动异常（`No default constructor found`）为 Task-09 引入，已在审查前修复（见 §4），修复后 `AgentDemoApplication` 实际启动成功（9.5s，Tomcat 8080）。

## 1. 做得好的部分 (Strengths)

- **冻结契约的纯函数实现**（`SessionToolResolver.resolveSessionBaseTools` / `UnifiedChatStream.buildHitlMessages`）：用"基础工具集确定性重算"替代缓存组件，无新增状态与失效逻辑，符合结构最小化；指纹日志（SHA-256）提供线上可观测验证点。
- **附件 emit-once 单点写入**（`SkillLoadTool.writeInstructionAttachment`）：流式拦截与同步直执行共用激活入口，一处代码覆盖双路径；失败降级不阻断主流程（仅损失跨轮持久性）。
- **滚动摘要完整语义**（`CompressingChatMemory`）：附件/System/摘要三态保护、滞回触发、FIFO 降级铁律、`hasAttachmentType` 幂等检测——AC-E01/M01 行为由 5 个测试类交叉覆盖。
- **输入层标记转义**（`AgentController.stripFrameworkMarkers`）：框架附件/摘要/状态标记的用户伪造路径被关闭（AC-S03），对抗性测试 2 例 100% 拦截。
- **末轮收尾状态消息**（`HITLReActStream.buildWrapUpStatusMessage`）：读数 + 操作策略成对，`<agent_status>` 标签显式标识框架来源（AC-H02），纯框架代码维护（AC-S01 可信源）。
- **安全边界未为精简让步**：权限门控、清洗管道、HITL 确认机制全部原样保留，未做任何删除。

## 2. 范围与意图比对

> [CLEAN]

| 比对项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 越权改动 | 无 | 全部变更对应任务规划 Task-01~18，无顺手重构 |
| 遗漏任务 | 无 | 13 开发任务 + 5 行为测试 + 2 验证全部落地并有测试证据 |

## 3. 链路一致性（实际变更 vs 技术方案文件清单）

> 比对基准：技术方案 1.6 文件清单。 [存在轻微偏差，均合理]

| 类型 | 文件路径 | 说明 |
| :--- | :--- | :--- |
| 多出（方案未定义） | `agent-demo-memory/.../MemoryCompressionProperties.java` | 压缩开关配置类（技术方案 §4.2 有语义但 1.6 清单未列）。**合理**：对齐既有 `ToolSanitizeProperties` 模式，回退通道必需 → Important 级别的文档偏差 |
| 多出（方案未定义） | `CompressingChatMemoryIT.java` 等 9 个测试类 | 行为测试/集成测试（技术方案 7.2 评估设计的落地，属测试资产，可接受） |
| 缺失（方案未兑现） | `SkillToolInterceptorImpl.java`（清单列"修改-测试锚点"） | 无逻辑变更，热刷新追加语义由 `CacheStabilityBehaviorTest` 断言覆盖（等价兑现）→ 可接受 |

## 4. 已修复项 (FIXED + AUTO-FIXED)

- [FIXED-CRITICAL] `AgentController` 多构造器未标注 `@Autowired` → 加 `@org.springframework.beans.factory.annotation.Autowired`（`agent-demo-web/.../AgentController.java:81`）——**启动失败根因**，已实际启动验证通过
- [FIXED-CRITICAL] `ChatMemoryManager` 多构造器未标注 `@Autowired` → 加注（`agent-demo-memory/.../ChatMemoryManager.java`）——否则 Spring 选无参构造导致**压缩静默退化 FIFO**（未启动失败但功能失效）
- [FIXED-CRITICAL] `SkillLoadTool` 多构造器未标注 `@Autowired` → 加注（`agent-demo-skill/.../SkillLoadTool.java`）——否则启动失败（同 AgentController）

> 说明：以上三处为 Task-09/03/05 引入同类缺陷（PROJECT_HABITS 已有"主构造器必须加 @Autowired"经验），审查前由启动异常驱动修复。**AUTO-FIX 红线核查**：三处均不改变运行行为（仅修正 Spring 装配选择），属规范修复。

## 5. 需决策的关键问题 (Action Required)

### Critical（阻断——未解决则审查不通过）

> 已全部修复并验证，无遗留 Critical。

### Important（修复后通过）

1. **EDD 轨评估方式缺口（Task-11 模板措辞）**
   - 位置：`hitl.txt` / `hitl-guidance.txt` / `task-execute.txt`（Prompt 制品）
   - 问题：Task-11 标注 EDD（概率性组件），但验收采用**确定性静态扫描**（工具名 ⊆ 注册表、措辞文本断言）+ 既有测试回归，未进行真实模型下的"工具选择行为"评估（本环境无方舟 API Key，无法跑 LLM 行为评估）。
   - 为什么重要：措辞调整（"以工具协议 tools 参数为权威"）对真实模型的工具调用行为影响未被概率性验证；`queryOrder/deleteFile` 幻觉引用已消除（确定性），但语义遵循度未知。
   - 修复方案：A) 接受本轮静态扫描验收，真实模型行为验证列入线上联调（推荐，演示项目无 LLM 评估环境）；B) 补真实模型工具选择行为评估（需方舟 API Key + 评估脚本）。
   - 处理：[x] A) 同意并接受（真实行为验证留待线上联调） / [ ] B) 补充真实评估

2. **链路文档偏差（MemoryCompressionProperties 未入 1.6 清单）**
   - 位置：`agent-demo-memory/.../MemoryCompressionProperties.java`
   - 问题：技术方案 1.6 文件清单未列该新增类（§4.2 有语义）。
   - 为什么重要：文件清单是任务规划"涉及文件"的唯一来源，漏列导致追溯断点。
   - 修复方案：A) 回技术方案 §1.6 补充该文件（推荐）；B) 忽略。
   - 处理：[x] A) 同意并修复（已补入 §1.6 文件清单） / [ ] B) 忽略

## 6. 次要问题与建议 (Minor)

1. `AgentController.java` 使用全限定类名 `com.agentdemo.memory.shortterm.CompressingChatMemory` / `com.agentdemo.skill.prompt.SkillPromptComposer`（约 8 处）→ 建议加 import 提升可读性
2. `SkillLoadTool.java` 同样使用全限定 `CompressingChatMemory.AttachmentType` → 建议 import
3. `TaskBreakdownStreamExecutionTest.java` 新增测试用全限定 `com.agentdemo.agent.single.SessionToolResolver` → 建议 import
4. `AgentControllerUnifiedTest` 的 judge mock 用全限定 `org.mockito.ArgumentMatchers.anyList()` → 可统一 import
5. `MemoryCompressionProperties` 字段仅 `enabled` 一项——符合"无投机配置"，但可评估未来窗口大小是否需要可配（当前硬编码 20，现状一致）

## 7. 测试质量评估（双轨核查）

### 确定性组件（TDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 测试真实测逻辑 | 是 | CompressingChatMemory 断言压缩后摘要/附件/降级真实行为；CacheStability 断言字节一致；无 mock 自嗨 |
| 正常/边界/异常覆盖 | 完整 | 各组件均含正常（压缩触发/附件写入/历史注入）+ 边界（空历史/全附件/追问上限）+ 异常（摘要失败 FIFO/模型异常降级） |
| TDD 合规（RED→GREEN） | 合规 | 13 个 TDD 任务均有 RED（编译失败/断言失败）→ GREEN 记录 |

### 概率性组件（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| EDD 迭代记录完整 | 合规（简化） | Task-11 1 轮 BUILD→EVALUATE 即达标，无 Tune 需记录；但评估为静态扫描（见 §5-1） |
| 评估数据集覆盖验证场景 | 部分缺口 | few-shot 真实性/措辞文本确定性覆盖；真实模型工具选择行为未覆盖（环境限制） |
| 验证策略与任务类型匹配 | 部分错配 | Task-11 标 EDD 用静态扫描验收（见 §5-1） |

## 8. Prompt 制品与护栏审查（Agent 特有）

### Prompt 制品（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 评估指标达标 | 部分达标 | 确定性指标 100%（工具名真实性/虚构引用=0/措辞声明）；概率性指标待真实模型评估 |
| System Prompt 与 2.1 架构一致 | 一致 | 三模板措辞与"工具协议为权威"的冻结契约一致；few-shot 真实工具化 |
| Token 成本在预算内 | 是 | 措辞调整不增加 token（同级替换）；附件/压缩均控量 |

### 护栏与安全

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 多层护栏落地完整（对照第 6 章） | 完整 | 输入层（标记转义）+ Prompt 层（工具协议措辞/护栏段）+ 输出层（清洗管道保留）+ 工具层（权限门控/HITL 保留）+ 记忆附件层（框架专属通道）五层齐全 |
| 对抗性测试 100% 拦截 | 是 | 附件伪造 2/2、状态投毒 2/2、few-shot 虚构引用 0/0，注入零回归 |
| 工具执行权限门控 | 无缺口 | 三级权限/tool_confirm/@HumanCheckpoint 原样保留，HITL 测试套件全绿 |

### 工具契约一致性

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 工具描述与 2.2 结构模板一致 | 一致 | 工具描述未改动（适用/不适用场景结构保留） |
| 与 tool-design 制品无漂移 | 一致 | 本次无工具描述文本变更 |

## 9. 需求符合性（六类 AC 覆盖映射表）

| AC 编号 | AC 摘要 | 实现位置 | 验证证据 | 验证策略 | 满足 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| AC-N01 | 系统提示词会话内缓存稳定 | `UnifiedChatStream.buildHitlMessages` | CacheStabilityBehaviorTest（字节一致 100%）| TDD/行为 | ✅ |
| AC-N02 | 末轮收尾状态注入 | `HITLReActStream.runReActLoop` | HITLReActStreamTest / StatusBarBehaviorTest | TDD/行为 | ✅ |
| AC-N03 | 规划判断携带会话历史 | `TaskPlanJudge.judge(+history)` | TaskPlanJudgeTest / PlanHistoryBehaviorTest | TDD/行为 | ✅ |
| AC-T01 | 技能指令单通道注入 | `SkillLoadTool.writeInstructionAttachment` | UnifiedChatStreamSkillTest / SkillLoadToolTest | TDD/行为 | ✅ |
| AC-T02 | 工具清单会话内冻结 | `SessionToolResolver.resolveSessionBaseTools` | SessionToolResolverSkillTest / CacheStabilityBehaviorTest | TDD/行为 | ✅ |
| AC-T03 | 工具热刷新只追加 | `SkillToolInterceptorImpl`（语义保留） | CacheStabilityBehaviorTest（追加断言） | 行为 | ✅ |
| AC-S01 | 状态栏可信源防护 | `ChatMemoryManager.addAttachment`（框架专属） | AttachmentForgeBehaviorTest / ChatMemoryManagerTest | 对抗性 | ✅ |
| AC-S02 | few-shot 示例真实性 | `hitl.txt` 示例真实化 | PromptTemplateLoaderTest（静态扫描 100%）| EDD/静态 | ✅ |
| AC-S03 | 注入防护零回归 | `AgentController.stripFrameworkMarkers` | AttachmentForgeBehaviorTest / AgentControllerUnifiedTest | 对抗性 | ✅ |
| AC-E01 | 记忆压缩与降级 | `CompressingChatMemory.compactIfNeeded` | CompressingChatMemoryTest / ChatMemoryManagerTest / MemoryCompressionBehaviorTest / IT | TDD/行为 | ✅ |
| AC-E02 | 末轮工具调用兜底 | `HITLReActStream`（收尾消息 + 现状兜底） | StatusBarBehaviorTest | 行为 | ✅ |
| AC-M01 | 压缩后指代保持 | `CompressingChatMemory`（摘要保实体） | MemoryCompressionBehaviorTest / CompressingChatMemoryIT | 行为 | ✅ |
| AC-M02 | HITL 恢复路径一致性 | `UnifiedChatStream` 组装唯一化 | AgentControllerUnifiedTest / 既有 HITL 套件 | TDD/行为 | ✅ |
| AC-H01 | HITL 机制零回归 | 确认机制零变更 | HitlZeroRegressionTest + 既有 HITL 套件全绿 | 行为 | ✅ |
| AC-H02 | 状态消息不冒充用户指令 | `buildWrapUpStatusMessage`（<agent_status> 标签） | HITLReActStreamTest / StatusBarBehaviorTest | TDD/行为 | ✅ |

**Scope Creep 检查**：无（全部 AC 均有对应实现，无 AC 之外的功能）

---

*审查链路：需求澄清 → 技术设计（1.6 文件清单）→ 任务规划（涉及文件同源）→ 实现（TDD+EDD）→ **代码审查（本报告）** → 后续流程*
