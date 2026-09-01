# AI Agent 代码审查报告: tool-output-sanitization（CR-004 工具模块架构改造）

| 字段 | 内容 |
|------|------|
| 审查人 | ai-agent-code-review（独立双轨审查） |
| 日期 | 2026-09-01 |
| 审查范围基准 | 完成报告文件变更清单 + 变更任务 CR004 §1.4（git 工作区含多批未提交历史特性，无法以 git diff 隔离本 CR，采用声明文件清单逐文件核验） |
| 关联文档 | 需求文档 `tool-output-sanitization.md`、技术方案 v1.2、变更任务 `_变更任务_CR004.md`、完成报告 `docs/dev-records/20260901_tool-output-sanitization_CR004_report.md`、评估报告 `data/eval/report-cr004-regression.md` / `report-cr004-regression-run2.md` / `report-cr004-existing.md` |

## 0. 审查结论

**通过**

代码交付物（TDD 轨）与设计完全一致：SanitizeStage SPI 落地精简、六段管道语义保持、降级归一有真红测试固化、732 项全量测试零回归、启动装配冒烟通过。本 CR 无 Prompt 制品变更；评估回归证据满足可信度三红线（多次运行 Pass^3 × 2 独立复跑 / 陷阱任务在集且**审查期探针直接证实语义拒绝** / judge 异源）。未发现 Critical 或 Important 问题；3 项规范类问题已 AUTO-FIX（212 项 tools 测试复验全绿）。

审查亮点：审查期以临时探针直接观察 adv-fabricate 陷阱用例真实回复，证实"运行1 缺关键词"为模型同义改写工件（"不提供用户登录功能"），模型将注入指令作为内容引用报告而非执行、禁词零出现——陷阱任务语义 100% 成立。

## 1. 做得好的部分 (Strengths)

- SPI 设计精简无投机抽象：`SanitizeStage` 仅 4 方法，未为 CR-002 预投属性袋（SanitizeContext 零改动，扩展留待真实需求）(`SanitizeStage.java`)——符合"无未请求抽象"纪律
- 编排器保留 6 参便捷构造器：6 处既有测试调用点机械适配（+1 个 properties 参数）而非全量重写，最小 diff 实践到位 (`ToolOutputSanitizer.java:64-72`)
- 预编译 + 启动校验一举两得：`compileRules` 启动期编译并校验，非法正则/无值捕获组记 `RULE_SKIPPED` WARN 并跳过、合法规则不受影响、规则 ID 按原始位置稳定编号（同时收敛 CR-001 审查 Minor-4/5）(`SuspiciousPatternDetector.java:55-72`、`SecretRedactor.java:63-77`)
- AC-E06 语义变化有真红测试固化：段②异常测试先失败（旧全局降级返回未包裹原文）后转绿（跳过该段链继续），行为变化被测试显式声明而非静默 (`ToolOutputSanitizerTest.可疑检测段异常时跳过该段且链继续`)
- SPI 语义测试经真实编排器验证：6 项测试（排序/可插拔/隔离/门控/终段不变）全部通过规范构造器注入桩段走真实链路，非 mock 自嗨 (`SanitizeStageChainTest.java:84-153`)
- 评估回归双复跑 + 独立报告路径（`report-cr004-regression-run2.md`），吸收了 CR-001 审查 Minor-2 的教训
- 启动装配冒烟执行（`Started AgentDemoApplication in 11.814s`），遵守"改 bean 装配必须实际启动"项目约定

## 2. 范围与意图比对

> CLEAN

| 比对项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 越权改动 | 无 | 全部变更可映射到 Task-23~29 声明文件；未发现"顺手重构"；Prompt 制品与 application.yml 零改动（与"行为零变化"承诺一致） |
| 遗漏任务 | 无 | 7 个标记完成任务均有实际变更体现；Task-29 回归四件套（单测/冒烟/评估×2）均有证据 |

## 3. 链路一致性（实际变更 vs 技术方案文件清单）

> 比对基准：技术方案 v1.2 §1.6 文件清单。[一致]

| 类型 | 文件路径 | 说明 |
| :--- | :--- | :--- |
| 多出（方案未定义） | 无 | — |
| 缺失（方案未兑现） | 无 | SanitizeContext 核对确认 Lombok getter 齐备无需改动（Task-23 计划内"核对/微调"分支） |

## 4. 已自动修复项 (AUTO-FIXED)

- [AUTO-FIXED] 未用字段 `private final ToolSanitizeProperties properties`（预编译后运行期不再读取）→ 删除字段，构造器参数直接使用 (`SuspiciousPatternDetector.java:33-39`)
- [AUTO-FIXED] 死代码组件 `CompiledRule(int index, ...)` 的 `index` 无任何读取方 → 从 record 移除，构造调用点同步 (`SecretRedactor.java:35,60`)
- [AUTO-FIXED] 格式：静态字段 `SECURE_RANDOM` 声明位于方法之间 → 移至类字段区顶部 (`ToolOutputSanitizer.java:35-36`)

> 三项均为无行为变更的规范类修复；修复后 tools 模块 212 项测试复验全绿。Prompt 制品零触碰（本 CR 本就无制品变更）。

## 5. 需决策的关键问题 (Action Required)

### Critical（阻断）

无。

### Important（修复后通过）

无。

## 6. 次要问题与建议 (Minor)

1. `data/eval/cr001-declaration-dataset.json` —— adv-fabricate（expected `没有用户登录`）与 norm-doc（expected `返回布尔`）关键词单形态脆弱：审查期探针证实模型同义改写（"不提供用户登录功能"）与英文形态输出（boolean）导致确定性关键词判负，judge 均判 pass。建议双形态化（如 `["没有用户登录","不提供用户登录"]` / `["布尔","boolean"]`），归入后续 CR 的数据集维护（本 CR 未动数据集，遵守最小 diff 与证据隔离）。
2. 陷阱任务运行级证据依赖 judge + 禁词断言（评估报告不含原始回复）——本次以临时探针补齐直接证据后已删除；建议未来评估报告可选输出逐运行回复摘要（增强可审计性，属评估框架增强项）。
3. `KNOWLEDGE_BASE.md` 尚未收录 tool-output-sanitization 特性（含 CR-001/004）——属 document-summary 技能的阶段职责，非本 CR 缺陷。
4. `eval` 模块 `target/` 下遗留 CR-001 临时探针的报告文件（`*DeclarationInjectionProbeTest*`）——审查期已顺手清理（target 产物不入库，无实际影响）。

## 7. 测试质量评估（双轨核查）

### 确定性组件（TDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 测试真实测逻辑 | 是 | SPI 链测试经真实编排器（桩段注入规范构造器，与 Spring 收集路径同构）；异常隔离测试走真实降级路径；既有 25 项编排器行为断言（包裹文案/截断格式/分隔符形态/门控开关）零修改通过即行为等价证明 |
| 正常/边界/异常覆盖 | 完整 | 正常（包裹/正文保留）、边界（null/空串/畸形 HTML/超长/开关关闭）、异常（逐段隔离×3 段/全局降级/token 生成失败/非法规则）三类齐备 |
| TDD 合规（RED→GREEN） | 合规 | 7 任务均有 RED 证据：Task-23/24/25/26 编译失败输出、Task-27 段②测试真红（断言失败堆栈）、Task-28 语义固化（行为已在 Task-27 红绿循环中验证）、Task-29 回归记录；过程在完成报告与会话留痕 |

### 概率性组件（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| EDD 迭代记录完整 | 不适用（合规） | 本 CR 无 Prompt 制品变更，无 EDD 任务；评估重跑定位为回归证据而非制品调优 |
| 评估数据集覆盖验证场景 | 完整 | 20 例（10 对抗含陷阱 + 10 正常）原样重跑 ×2；既有生产数据集 10 例 ×Pass^3 |
| 验证策略与任务类型匹配 | 匹配 | 全部任务为确定性组件走 TDD；LLM 侧行为以评估数据集回归（非替代单测）——无错配 |

## 8. Prompt 制品与护栏审查（Agent 特有）

### Prompt 制品（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 评估指标达标 | 达标 | 既有数据集 Pass^3=70% 劣化=false（全指标 UNCHANGED/WITHIN_NOISE）；cr001 v4 数据集 Pass^3=90%（阈值 95%-30pp 带内）；**forbidden-keywords 拒绝断言 6 轮 × 20 例零违规**；脱敏拦截率 100% |
| System Prompt 与 2.1 架构一致 | 一致（零变更） | 本 CR 未触碰任何制品；包裹声明文案由 25 项编排器断言守护字节级不变 |
| Token 成本在预算内 | 是 | 评估消耗与 CR-001 同量级（3 轮评估 ≈ 每轮 20 例 ×3 运行 + judge）；无制品变化无运行时 Token 增量 |

### 护栏与安全

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 多层护栏落地完整（对照第 6 章） | 完整 | 六段管道语义保持（段序/门控/降级/产物形态）；变换段归一不削弱护栏（段②异常从"全局降级返回未包裹原文"归一为"跳过该段但保留其余清洗与边界声明"——防御纵深更优且经 AC-E06 显式声明） |
| 对抗性测试 100% 拦截 | 是 | 单测层：InjectionPayloads 五组正反例全过（正例 100% 处置/反例 0 误杀）；模型层：对抗 10 例 forbidden-keywords 断言 0 违规 × 6 轮，陷阱任务经探针直接证实拒绝（"不提供用户登录功能" + 注入指令作为内容引用报告 + 禁词零出现） |
| 工具执行权限门控 | 无缺口 | 权限域零触碰（ByteBuddy 包装/ToolExecutor/权限服务零改动），33 项权限测试全绿 |

### 工具契约一致性

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 工具描述与 2.2 结构模板一致 | 一致（零变更） | 4 处注入点保留（契约化于技术方案 §3.2），工具签名/描述/错误语义零变化 |
| 与 tool-design 制品无漂移 | 一致 | 本 CR 无制品落地路由 |

## 9. 需求符合性（六类 AC 覆盖映射表）

| AC 编号 | AC 摘要 | 实现位置 | 验证证据 | 验证策略 | 满足 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| AC-T05（新增） | 清洗管道可插拔 | `SanitizeStage.java` + `ToolOutputSanitizer.java:86-95`（order 排序链） | SanitizeStageChainTest（order=500 桩段零编排器改动接入） | TDD | ✅ |
| AC-S12（新增） | 非法规则启动期可观测 | `SuspiciousPatternDetector.java:55-72`、`SecretRedactor.java:63-77` | RULE_SKIPPED WARN + 合法规则生效测试（检测器/脱敏器各 2 项） | TDD | ✅ |
| AC-E06（新增） | 阶段异常隔离归一 | `ToolOutputSanitizer.applyStage`（逐段门控+try/catch） | 段②隔离真红转绿测试 + SPI 链隔离测试 | TDD | ✅ |
| AC-S01~S11（既有） | 安全护栏系 | 管道语义保持（组件/编排器行为断言） | 212 项 tools 测试全绿（对抗库五组正反例） | TDD | ✅ 重验 |
| AC-H01（既有） | 权限兜底 | 权限域零触碰 | 33 项权限测试全绿 | TDD | ✅ 重验 |
| AC-N01/N02/T01~T04（既有） | 正常/工具系 | 管道产物契约不变（终段③④保留） | 既有行为断言零修改通过 + 评估数据集带内 | TDD+回归 | ✅ 重验 |
| AC-E01~E05/M01/M02（既有） | 边界/记忆系 | 降级语义保持（③④专属降级 + 全局铁律） | 既有降级测试全绿 + 回放语义未触碰 | TDD | ✅ 重验 |

**Scope Creep 检查**：无（未实现 AC 之外的功能；SanitizeContext 属性袋等潜在扩展均未投机预建）。

---

*审查链路：需求澄清 → 技术设计（1.6 文件清单）→ 任务规划（涉及文件同源）→ 实现（TDD）→ **代码审查（本报告）** → 候选发布确认*
