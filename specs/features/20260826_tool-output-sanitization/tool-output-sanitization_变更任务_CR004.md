# Agent 变更记录: tool-output-sanitization - CR-004

## 0. 变更概览 (Change Overview)
*   **变更标题**: 工具模块架构改造--清洗管道拦截器链化（SanitizeStage SPI）
*   **变更类型**: 架构重构 (Architecture Refactor)（局部：清洗管道内部编排；对外行为零变化；按安全边界变更级执行全量对抗回归）
*   **变更原因**: 路线图顺序调整为 CR-004 先行（架构先行）——CR-002 注入检测引擎与 CR-003 输出侧护栏后续以新 Stage 接入可插拔管道，无需再改编排器；同时收敛 CR-001 审查 Minor-3/4/5（per-call 编译/静默跳过规则）
*   **更新载体判定**: 程序（代码/工具）— 选择理由：本变更为纯代码重构（管道编排结构），知识库不适用（非事实经验）、指令不适用（Prompt 制品零变更：包裹声明文案/工具描述/系统提示词均不动）、参数不适用
*   **发起日期**: 2026-09-01
*   **开发方法**: TDD 驱动（本变更全部为确定性组件，无 Prompt 制品变更故无 EDD 任务；评估数据集原样重跑作为回归证据）
*   **关联 Agent**: tool-output-sanitization（工具产出安全清洗）
*   **关联文档**:
    -   需求文档: `specs/features/20260826_tool-output-sanitization/tool-output-sanitization.md`
    -   技术方案: `specs/features/20260826_tool-output-sanitization/tool-output-sanitization_技术方案.md`（v1.2）
    -   任务规划: `specs/features/20260826_tool-output-sanitization/tool-output-sanitization_任务规划.md`
    -   前序变更: `specs/features/20260826_tool-output-sanitization/tool-output-sanitization_变更任务_CR001.md`（Task-16~22 已完成）

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-T05 | 新增 | 清洗管道可插拔：新增 SanitizeStage 实现按 order 接入，编排器零修改 |
| AC-S12 | 新增 | 非法清洗规则启动期可观测：预编译校验 + RULE_SKIPPED WARN，合法规则正常生效 |
| AC-E06 | 新增 | 管道阶段异常隔离归一：任一 Stage 异常跳过该段继续，其余段（含终段③④）正常执行（与 AC-E05 对外语义等价） |
| 自主性级别 | 不变 | 沿用现有 ALLOW/ASK/DENY 三级权限裁决，本次为纯内部重构 |
| 范围标注 | 修改 | 8.1 新增架构改造条目；8.2 架构改造行标注已由 CR-004 承接 |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 无影响 | 包裹声明结构契约（§2.1）、工具描述、输出格式契约全部不变 |
| 工具集成 | 修改（内部） | 4 处注入点保留（注入点契约文档化，技术决策 11）；工具方法签名与返回形态不变 |
| 记忆与上下文 | 无影响 | 清洗时点性与回放语义（AC-M01/M02）不变 |
| 护栏与安全 | 修改（实现归一） | 变换段 ⓪①②②' 统一 SanitizeStage SPI；门控（invisible-chars/redact-secrets/htmlContent）移入各 Stage 的 appliesTo()；降级归一为编排器逐段 try/catch；终段③④固定（产物契约）；护栏强度不削弱（安全机制不可越界检查通过） |
| 评估框架 | 无影响（原样重跑） | 评估数据集与对抗库无扩充（无新行为分支）；cr001-declaration-dataset.json v4 原样重跑作为回归证据 |

### 1.3 Prompt 制品影响
> 本变更无 Prompt 制品变更，无 EDD 任务与制品落地路由。

| 制品 | 变更类型 | 落地路由 | 验收要求 |
| :--- | :--- | :--- | :--- |
| （无） | 无 | 无 | — |

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/SanitizeStage.java` | 新增 | 阶段 SPI：order()/name()/appliesTo(ctx)/process(text, ctx) |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/ToolOutputSanitizer.java` | 修改 | 编排器链化：Spring 收集 List\<SanitizeStage\> 按 order 排序遍历 + 逐段 try/catch；终段③④保留；静态 SecureRandom |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/InvisibleCharCleaner.java` | 修改 | 实现 SanitizeStage（order=100），签名 (text, toolName) -> (text, SanitizeContext) |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/HtmlContentCleaner.java` | 修改 | 实现 SanitizeStage（order=200，appliesTo=htmlContent），签名统一 |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/SuspiciousPatternDetector.java` | 修改 | 实现 SanitizeStage（order=300），规则启动预编译，签名统一（Detection 富结果保留为内部入口） |
| `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/SecretRedactor.java` | 修改 | 实现 SanitizeStage（order=400，appliesTo=redact-secrets），规则预编译 + 非法规则 WARN |
| `agent-demo-tools/src/test/java/com/agentdemo/tools/sanitize/*Test.java` | 修改 | 4 个组件测试适配 ctx 签名；ToolOutputSanitizerTest 断言不变（行为等价证明） |
| `agent-demo-tools/src/test/java/com/agentdemo/tools/sanitize/SanitizeStageChainTest.java` | 新增 | SPI 语义固化：插拔/排序/逐段隔离/门控 |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| `data/eval/cr001-declaration-dataset.json`（v4，20 例） | 原样重跑 | 行为零变化，作为回归证据（非扩充） |
| `data/eval/dataset.json`（既有生产回归集，10 例） | 原样重跑 | 指标不低于基线减噪声带宽 |
| 注入 payload 用例库（InjectionPayloads） | 无影响 | 无新行为分支，对抗库全量断言照常执行 |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 架构重构=全量（并按路线图既定约定升级为安全边界变更级：护栏承载组件重构）
*   **本次回归范围清单**:
    - 全部单元测试（tools 195 / mcp 167 / rag 90 / agent 200 / evaluation 63，共 652+ 项，评估模块含 CR-001 审查加固新增的 forbidden-keywords 测试）
    - AC-S01~S12 全部安全类 AC 重验（对抗用例库全量断言）
    - AC-H01 重验（权限兜底回归，既有权限测试集）
    - 评估数据集重跑：`cr001-declaration-dataset.json` v4 Pass^3 + `dataset.json` Pass^3
*   **评估指标基线（变更前）**: 对抗用例 10/10 通过 × 3 次运行（forbidden-keywords 拒绝断言生效）/ 陷阱拦截率 100% / 脱敏拦截率 100% / cr001 数据集 Pass^3=95% / 既有数据集 Pass^3=70%（噪声带宽 ±30pp）
*   **候选验证门槛**: 候选版本必须通过全部回归评估（含陷阱任务与 Pass^3 稳定性指标）方可进入候选发布；灰度期间指标劣化立即回滚（版本回滚，SPI 为纯内部重构，开关语义不变）
*   **安全边界检查**: 本次变更不触碰护栏规则与权限门控（护栏强度不削弱，六段管道语义保持）；评估证据与候选变更已隔离——评估数据集沿用 CR-001 定稿的 v4 版本，未用候选版本生成/修改评估标准
*   **高风险区域**: ① 六段管道执行顺序与门控行为漂移（⓪①②②' order 排序 + appliesTo 门控）——由既有 ToolOutputSanitizerTest 全量断言 + SanitizeStageChainTest 守护；② 组件签名变更波及测试编译——机械适配；③ 规则预编译改变非法规则的生效时点（运行期静默 -> 启动期 WARN 跳过）——仅可观测性增强，合法规则行为不变

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增的行为验收标准
> 已就地写入需求文档对应分组（7.2 工具调用 / 7.3 安全护栏 / 7.4 边界降级），此处仅登记摘要。

- **AC-T05**: 清洗管道可插拔（CR-004）——新增 SanitizeStage 实现按 order 接入管道执行，编排器代码零修改，既有各段顺序与结果不变
- **AC-S12**: 非法清洗规则启动期可观测（CR-004）——非法正则/无值捕获组规则启动期 WARN（RULE_SKIPPED）并跳过，合法规则正常生效；不应运行期静默忽略或因单条非法规则阻断清洗服务
- **AC-E06**: 管道阶段异常隔离归一（CR-004）——任一 Stage 异常被编排器捕获、记 WARN（含段名与工具名）并跳过该段，其余阶段继续执行；不应因单个阶段缺陷导致整条管道失效

### 2.2 移除的内容（如有）
- 无（8.2 中"工具模块架构改造"Out of Scope 行标注已承接，非移除）

## 3. 技术变更详情 (Technical Delta)

### 3.1 Prompt 架构变更
- 无（包裹声明结构契约 §2.1 不变）

### 3.2 工具空间变更
| 操作 | 工具 | 说明 | 契约设计 |
| :--- | :--- | :--- | :--- |
| 无新增/移除 | — | 4 处注入点保留并契约化（§3.2 注入点契约 5 条） | 无需 tool-design 介入（工具对外契约零变化） |

### 3.3 护栏变更
- 无强度变化：六段管道语义保持（段序/门控/降级/产物形态全部不变）；实现归一（SPI 链 + 逐段隔离 + 启动期规则校验）仅提升可维护性与可观测性
- CR-002/CR-003 接入预留：新 SanitizeStage bean（CR-002，order 落位 ② 邻近）/ 输出侧独立管道实例（CR-003，复用 SPI）

### 3.4 兼容性与回滚
*   **向前兼容**: 完全兼容——对外 API（sanitize 入口/SanitizeContext 构造/配置项/包裹声明产物）零变化；Spring 装配经启动验证（PROJECT_HABITS：改 bean 装配必须实际启动）
*   **Prompt 版本回滚**: 不涉及（无制品变更）
*   **代码回滚方案**: 版本回滚（SPI 为纯内部重构，无新增开关）；`agent.tool.sanitize.enabled=false` 秒级直通兜底依然有效

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号承接 CR-001（Task-16~22），从 Task-23 起；全部为确定性组件，无 Prompt 制品任务

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) — EDD
> 无 Prompt 制品变更，跳过此阶段。

### 阶段二：工具与代码变更 (Tool & Code Delta) — TDD
> 按 RED → GREEN → REFACTOR 循环执行。

- [x] **Task-23**: SanitizeStage SPI 接口与 SanitizeContext 适配
    *   **说明**: 新增 `SanitizeStage` 接口（order()/name()/appliesTo(ctx)/process(text, ctx)）；确认 `SanitizeContext` 提供 getToolName()/getSourceDesc()/isHtmlContent() 供各 Stage 使用（缺则补齐 getter）
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 给清洗管道定义统一的"工序接口"，每道工序自带序号、开关判断和处理方法
    *   **涉及文件**: `agent-demo-tools/src/main/java/com/agentdemo/tools/sanitize/SanitizeStage.java`（新增）、`SanitizeContext.java`（核对/微调）
    *   **测试文件**: `agent-demo-tools/src/test/java/com/agentdemo/tools/sanitize/SanitizeStageChainTest.java`（本任务先落接口编译桩，语义测试在 Task-28 完整固化）
    *   **参考**: 技术方案 v1.2 §1.6 / §3.1 SPI 定义
    *   **对应AC**: AC-T05
    *   **预估工时**: 30m
    *   **依赖**: 无
    *   **验证标准**:
        - [ ] 接口含 order/name/appliesTo（default true）/process 四方法，`mvn compile -pl agent-demo-tools -am` 通过
        - [ ] default appliesTo 语义：未覆写时无条件执行

- [x] **Task-24**: InvisibleCharCleaner 与 HtmlContentCleaner Stage 化
    *   **说明**: 两组件实现 SanitizeStage（order=100/200）；签名从 (text, toolName) 统一为 (text, SanitizeContext)；HtmlContentCleaner.appliesTo 返回 ctx.isHtmlContent()；InvisibleCharCleaner.appliesTo 返回 properties.isInvisibleChars()（组件需注入 ToolSanitizeProperties）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 把"隐形字符清理"和"HTML 脚本剥离"两道工序改挂到统一工序接口上，开关判断随工序自带
    *   **涉及文件**: `InvisibleCharCleaner.java`、`HtmlContentCleaner.java`
    *   **测试文件**: `InvisibleCharCleanerTest.java`、`HtmlContentCleanerTest.java`（适配 ctx 签名 + appliesTo 门控断言）
    *   **参考**: 技术方案 v1.2 §3.1 段表
    *   **对应AC**: AC-T05/R-5（门控等价）
    *   **预估工时**: 40m
    *   **依赖**: Task-23
    *   **验证标准**:
        - [ ] 既有清洗行为断言全量保持（零宽剥离/HTML 剥离正反例）
        - [ ] 门控断言：invisible-chars=false 时 Stage 跳过、htmlContent=false 时 ① 跳过
        - [ ] WARN 日志语义不变（INVISIBLE_STRIPPED / HTML_STRIPPED）

- [x] **Task-25**: SuspiciousPatternDetector Stage 化与规则预编译
    *   **说明**: 实现 SanitizeStage（order=300，无条件 appliesTo）；`suspiciousPatterns`/`highRiskPatterns` 启动期预编译（构造/@PostConstruct 编译为 List\<Pattern\>）；非法正则记 WARN（RULE_SKIPPED）并跳过；`Detection` 富结果保留为内部 detect 入口供测试断言 hits
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 可疑指令检测的规则改为启动时一次性编译校验，配错的规则启动时就告警，不再运行时悄悄忽略
    *   **涉及文件**: `SuspiciousPatternDetector.java`
    *   **测试文件**: `SuspiciousPatternDetectorTest.java`（适配 + 新增启动校验/规则跳过断言）
    *   **参考**: 技术方案 v1.2 §3.1 / §6.5 / 决策 13
    *   **对应AC**: AC-S12
    *   **预估工时**: 50m
    *   **依赖**: Task-23
    *   **验证标准**:
        - [ ] 既有分级处置断言全量保持（一般标记/高危移除/反例不误杀）
        - [ ] 非法规则（坏正则）启动 WARN + 跳过，合法规则不受影响
        - [ ] process 返回值语义与改造前一致

- [x] **Task-26**: SecretRedactor Stage 化与规则预编译
    *   **说明**: 实现 SanitizeStage（order=400，appliesTo=properties.isRedactSecrets()）；`secretPatterns` 启动期预编译 + 非法规则（坏正则/无值捕获组）WARN（RULE_SKIPPED）跳过，替代运行期 `matcher.groupCount() < 1` 静默忽略
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 秘密脱敏规则同样改为启动时校验，配错的规则会明确告警而不是悄悄失效
    *   **涉及文件**: `SecretRedactor.java`
    *   **测试文件**: `SecretRedactorTest.java`（适配 ctx 签名 + 启动校验断言；既有 14 例正反例保持）
    *   **参考**: 技术方案 v1.2 §3.1 / §6.5 / 决策 13
    *   **对应AC**: AC-S12/AC-S09
    *   **预估工时**: 40m
    *   **依赖**: Task-23
    *   **验证标准**:
        - [ ] 既有 14 项脱敏断言全量保持（多形态脱敏/反例不误杀）
        - [ ] 非法规则（无捕获组）启动 WARN + 跳过（替换静默忽略，收敛审查 Minor-5）
        - [ ] 门控断言：redact-secrets=false 时 Stage 跳过

- [x] **Task-27**: ToolOutputSanitizer 编排器链化
    *   **说明**: 构造器注入 `List<SanitizeStage>` 按 order 排序遍历（替代六段硬编码调用）；逐段 try/catch（异常 -> `[tool-sanitize]` WARN 含段名与工具名 + 跳过该段继续，AC-E06）；终段③④保留在编排器内（含各自专属降级）；`generateDelimiterToken()` 改用静态 SecureRandom 单例；`disabled()` 直通语义保持（enabled=false 直接返回原文）
    *   **变更类型**: 修改
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 清洗流水线从"写死六步"改为"按序号自动组装的工序链"，任何一道工序出错只跳过该道、不影响其余
    *   **涉及文件**: `ToolOutputSanitizer.java`
    *   **测试文件**: `ToolOutputSanitizerTest.java`（既有 24+ 例全量保持为行为等价证明；新增逐段隔离断言）
    *   **参考**: 技术方案 v1.2 §3.1 / 决策 12/13
    *   **对应AC**: AC-E06/AC-E05/AC-E02
    *   **预估工时**: 60m
    *   **依赖**: Task-24/25/26
    *   **验证标准**:
        - [ ] 既有管道行为断言全量保持（段序产物/截断/包裹/随机分隔符/总开关直通）
        - [ ] 任一 Stage 抛异常：WARN 记录 + 该段跳过 + 后续段与终段正常执行
        - [ ] 静态 SecureRandom 替换后随机性测试（两次调用 token 不同）保持通过
        - [ ] Spring 装配启动验证（`mvn -pl agent-demo-bootstrap spring-boot:run` 冒烟，PROJECT_HABITS 约定）

- [x] **Task-28**: SanitizeStageChainTest SPI 语义固化
    *   **说明**: 注册测试桩 Stage 验证 SPI 核心语义：① order 排序执行（乱序注册按 order 输出）；② 可插拔（新增桩 Stage 零编排器改动接入，AC-T05 核心断言）；③ 逐段异常隔离（某段抛异常其余段继续，AC-E06）；④ 门控 appliesTo 生效（返回 false 的段跳过）；⑤ 终段③④不受链变化影响
    *   **变更类型**: 新增
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（SPI 语义测试）
    *   **通俗解释**: 用一套专门的测试证明"工序链"可以随插随用、坏一道工序不拖垮整条线
    *   **涉及文件**: `agent-demo-tools/src/test/java/com/agentdemo/tools/sanitize/SanitizeStageChainTest.java`
    *   **测试文件**: 同左
    *   **参考**: 技术方案 v1.2 §7.2（SanitizeStageChainTest 行）/ §9 AC-T05/E06
    *   **对应AC**: AC-T05/AC-E06
    *   **预估工时**: 40m
    *   **依赖**: Task-27
    *   **验证标准**:
        - [ ] 桩 Stage 按声明 order 顺序执行且结果串联正确
        - [ ] 新增桩 Stage 后编排器零改动即接入（编译期+运行期双重证明）
        - [ ] 一段抛异常：其余段全部执行、产物完整、WARN 语义正确
        - [ ] appliesTo=false 段被跳过且不影响链

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)
> 无新增行为分支，评估数据集不扩充（原样重跑作为回归证据），跳过此阶段。

### 阶段四：回归验证 (Regression Verification)

- [x] **Task-29**: 全量回归验证（安全边界变更级）
    *   **说明**: 按第 1.5 节回归范围清单执行：① 全模块单元测试（`mvn install -pl agent-demo-common -DskipTests` 后逐模块 `mvn test`：tools/mcp/rag/agent/evaluation）；② AC-S01~S12 全部重验（对抗用例库全量断言随单元测试执行）；③ AC-H01 权限兜底回归（既有权限测试集）；④ 评估数据集重跑：`cr001-declaration-dataset.json` v4 Pass^3（forbidden-keywords 拒绝断言生效）+ `dataset.json` Pass^3（基线对比）；⑤ 产出回归记录
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（评估数据集 + 对抗性测试，全量）
    *   **涉及文件**: 全部测试模块 + `data/eval/cr001-declaration-dataset.json` + `data/eval/dataset.json`
    *   **对应AC**: 所有受影响的 AC（AC-N/T/S/E/M/H 全系 + 新增 T05/S12/E06）
    *   **预估工时**: 90m（含评估重跑约 4 分钟/轮 × 2 数据集）
    *   **依赖**: Task-28
    *   **验证标准**:
        - [ ] 全部单元测试通过（基线 652 项 + 本变更新增，零回归）
        - [ ] 对抗用例库全量断言通过：正例 100% 处置 / 反例 0 误杀
        - [ ] AC-S01~S12 + AC-H01 重验通过
        - [ ] cr001 数据集（v4）Pass^3 >= 基线 95% 减噪声带宽（对抗 10/10 含 forbidden-keywords 断言全过、陷阱拦截 100%）
        - [ ] 既有数据集 Pass^3 >= 70% - 30pp（既有基线：passRate 0.7 / toolSelectionRate 1.0 / maskInterceptRate 1.0 / trapInterceptRate 0.5 带内）
        - [ ] 灰度方案就绪：`agent.tool.sanitize.enabled=false` 直通兜底 + 版本回滚路径（SPI 纯内部重构无新增开关）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-T05 | 清洗管道可插拔（新增 Stage 零编排器改动按 order 接入） | 已完成 | Task-23/27/28 | 新增 |
| AC-S12 | 非法清洗规则启动期可观测（RULE_SKIPPED WARN + 跳过，合法规则正常） | 已完成 | Task-25/26 | 新增 |
| AC-E06 | 管道阶段异常隔离归一（一段异常跳过继续，与 AC-E05 对外语义等价） | 已完成 | Task-27/28 | 新增 |
| AC-S01~S11/H01 | 既有安全类与人机协作 AC 全量重验（安全边界变更级回归） | 已重验通过 | Task-29 | 重验 |
| AC-N01/N02/T01~T04/E01~E05/M01/M02 | 既有正常/工具/边界/记忆 AC 回归守护（既有测试全量通过） | 已重验通过 | Task-29 | 重验 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 7 个（TDD 6 个 / EDD 0 个 / 行为测试 1 个）
*   **预计总工时**: 350 分钟（约 5.8 小时）
*   **风险等级**: 中
*   **风险说明**: 链化重构的行为漂移风险（段序/门控/降级）——由 652 项既有测试全量守护 + SanitizeStageChainTest 固化 SPI 语义 + 评估数据集 Pass^3 重跑三重缓解；组件签名变更波及约 5 个测试类为机械适配；规则预编译仅改变非法规则的可观测性时点，合法规则行为不变
*   **测试影响**: 需适配约 5 个既有测试类（ctx 签名机械适配），新增 1 个测试类（SanitizeStageChainTest），评估用例零扩充
*   **评估基线变化**: 预期零变化——对抗 10/10（forbidden-keywords 断言全过）/ 陷阱拦截 100% / cr001 数据集 Pass^3 = 95% / 既有数据集 Pass^3 = 70% 带内（低于基线减带宽即回滚）
*   **预期效果**: 清洗管道成为可插拔拦截器链——CR-002 注入检测引擎与 CR-003 输出侧护栏以新 Stage/独立管道接入时编排器零修改；非法规则启动期可观测；对外行为与 CR-001 完全一致
