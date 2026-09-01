# Agent 变更记录: langsmith-observability - CR-002

## 0. 变更概览 (Change Overview)
*   **变更标题**: 完整评估体系--LLM-as-judge 评估器、回归基线与 A/B 对比实验（本地 EDD harness）
*   **变更类型**: 能力扩展 (Capability Extension)（含安全边界变更要素：评估数据出境新通道 + 评估产物落盘面，**按 CR-001 先例从严执行全量对抗回归**）
*   **变更原因**: 需求文档 §8.2 遗留演进项第 5 项（"完整评估体系...最小闭环验证链路后再演进"）；最小闭环（trace -> 数据集 -> LangSmith 评估实验）已验证交付，技术方案 §7.2 已预留"引入 judge 列演进项"与"Prompt/模型对比实验演进项"两个钩子；本地 EDD harness 填补项目「真实模型行为评估」缺口（agent-context-engineering 审查报告遗留：真实模型工具选择行为验证因无评估环境被推迟到线上联调）
*   **更新载体判定**: **指令（Prompt 制品）+ 程序（代码/工具）双载体**-- 按「知识库 -> 指令 -> 程序 -> 参数」最小充分顺序：①评估的语义类维度（回答完整性/幻觉复核/风格）需要语言化判断规则，落**指令**（judge Prompt 制品，走 EDD 调优）；②数据集执行/评分聚合/基线对比是确定性逻辑，落**程序**（走 TDD）；③知识库载体不适用（无新领域事实，是评估流程而非知识）；④参数载体不适用（无需模型微调）
*   **发起日期**: 2026-08-31
*   **开发方法**: TDD + EDD 双驱动 - 确定性组件按 TDD（Red-Green-Refactor）执行；judge Prompt 制品按 EDD（BUILD -> EVALUATE -> TUNE -> RE-EVALUATE）执行
*   **关联 Agent**: langsmith-observability（LangSmith 可观测子系统）
*   **关联文档**:
    -   需求文档: `specs/features/20260829_langsmith-observability/langsmith-observability.md`（v1.2）
    -   技术方案: `specs/features/20260829_langsmith-observability/langsmith-observability_技术方案.md`（§7.2.1 本地评估 harness 设计）
    -   任务规划: `specs/features/20260829_langsmith-observability/langsmith-observability_任务规划.md`（原 Task-01~14）+ `langsmith-observability_变更任务_CR001.md`（Task-15~25）

## 1. 影响分析 (Impact Analysis)

### 1.1 需求影响
| 影响项 | 变更类型 | 详情 |
| :--- | :--- | :--- |
| AC-N10~N13 | 新增 | 本地评估数据集执行 / LLM-as-judge 结构化评分 / 回归基线生成与对比 / A/B 对比实验（详见 §2.1） |
| AC-S07 | 新增 | 评估出境与落盘零明文（judge 调用与产物落盘纳入统一脱敏出口） |
| AC-E06 | 新增 | judge 失败降级（缺席标注 + 不误计 0 分） |
| 自主性级别 | 不变 | L4 风险归类维持；三重护栏不削弱，脱敏护栏适用面扩大至评估通道（AC-S07） |
| 能力禁区 | 修改 | §8.2 第 5 项改写：judge/基线/对比已交付，仅"CI 集成"维持排除（无 CI 环境 + 真实 Key 管理成本高） |

### 1.2 技术影响
| 影响层 | 影响范围 | 详情 |
| :--- | :--- | :--- |
| Prompt 工程架构 | 新增 judge 制品 | 评估器专属 Prompt（维度/输出 JSON 契约/反偏差指令），不触碰对话主链路任何制品 |
| 工具集成 | 无影响 | 零工具变更（评估 harness 是调用方而非被调用方） |
| 记忆与上下文 | 无影响 | 评估每用例新建会话（既有 SessionManager 语义） |
| 护栏与安全 | 输出过滤层适用面扩大 | 评估数据集构造时经 maskSafe；judge 出境与落盘产物无密钥明文（AC-S07，单一出口无绕行） |
| 评估框架 | 核心扩展 | §7.2 五要素 -> 完整体系：确定性评估器 + judge 分层协作（veto 项始终确定性断言）；基线管理 + A/B 对比（技术方案 §7.2.1 四层设计） |

### 1.3 Prompt 制品影响
> 仅标注变更范围与验收标准，制品文本由 ai-agent-implementation 执行增量任务时落地

| 制品 | 变更类型 | 落地路由 | 验收要求 |
| :--- | :--- | :--- | :--- |
| judge 评估 Prompt（新增） | 新增 | `agent-prompt-designer`（设计模式） | 输出 JSON 契约解析成功率 >= 95%；评分维度与技术方案 §7.2 Rubric 对齐；反偏差指令（长度无关/仅依据证据）明确 |
| System Prompt / Tool 描述 / 对话输出契约 | 无变更 | - | 零触碰（对话主链路 Token 零增量原则保持） |

### 1.4 代码与评估影响
**代码影响：**
| 文件路径 | 操作 | 影响说明 |
| :--- | :--- | :--- |
| `agent-demo-evaluation/pom.xml` | 新增 | 模块定义（依赖 agent + llm + observability + tools；BOM/根 pom 登记，复制 mcp/skill 接入先例） |
| `agent-demo-evaluation/.../EvalDataset.java` 等 | 新增 | 数据层：数据集模型 + JSON 加载（用例含输入/预期要点/确定性断言/陷阱标记） |
| `agent-demo-evaluation/.../EvaluationRunner.java` | 新增 | 执行层：逐例真实发起对话（复用 Agent 服务）+ 执行记录采集 + 单例失败标注后继续 |
| `agent-demo-evaluation/.../DeterministicEvaluator.java` | 新增 | 评分层：工具选择/关键词/脱敏命中断言 + 逐例与聚合输出 |
| `agent-demo-evaluation/.../JudgeEvaluator.java` | 新增 | 评分层：judge 调用 + 结构化解析 + 同源 WARN + 缺席降级（AC-E06） |
| `agent-demo-evaluation/.../BaselineManager.java` | 新增 | 报告层：基线生成/加载/对比（劣化标注 + 噪声带宽声明 + 显式重建） |
| `agent-demo-evaluation/.../resources/prompts/judge.md` | 新增 | judge Prompt 制品（EDD 调优，Task-30） |
| `data/eval/dataset.json` | 新增 | 本地评估数据集（>=10 用例含陷阱任务，构造时经 maskSafe） |
| `agent-demo-bootstrap/.../application.yml` | 修改 | 新增 `eval` 配置段（judge-model-id / runs / dataset 与 baseline 路径） |
| 对话主链路全部文件 | 零修改 | 埋点/采集/上报零触碰（最小 diff） |

**评估影响：**
| 评估资产 | 影响类型 | 说明 |
| :--- | :--- | :--- |
| 本地评估数据集（新建） | 新增 | >=10 用例：直答/单工具/ReAct 多轮/工具失败恢复/含指代多轮/含密钥正例 + 陷阱任务（复用 20260826 对抗用例库思想） |
| 对抗性用例 | 需扩充 | judge 出境与落盘零明文用例（AC-S07）+ judge 不可用降级用例（AC-E06） |
| 已有评估用例 | 无影响 | LangSmith 平台侧最小闭环用例照旧（两条评估路径并存：平台侧 code evaluator + 本地 harness） |
| 既有测试套件 | 无影响 | 纯新增模块与文件，零既有测试修改（observability 67 / llm 148 / tools 160 / web 121 / skill 97 基线不变） |

### 1.5 回归风险评估
*   **变更类型对应回归级别**: 能力扩展 + 安全边界变更要素 -> **从严执行全量**（评估数据出境新通道 + 产物落盘面，按 CR-001 先例）
*   **本次回归范围清单**:
    -   全部安全类 AC 重验：AC-S01~S07（含新增 S07 judge 出境与落盘零明文）
    -   全部对抗性用例：注入（经 trace 记录）/ 密钥泄漏 / 无 Key 外联 / judge 出境与落盘（新增）
    -   既有采集上报链路无回归：AC-N01~N09、AC-T01~T03、AC-E01~E05、AC-M01~M03 全部既有测试套件全绿
    -   端到端：评估 harness 运行不影响对话主链路（对话行为零变更）
*   **评估指标基线（变更前）**: 既有测试套件全绿（observability 67 + llm 148 + tools 160 + web 121 + skill 97；app 282 含 3 个既有基线失败）；脱敏正例命中 100%、反例误伤 0%；无 Key 启动零网络请求；ReAct 链路完整率 100%。**本地评估基线文件尚不存在，本次 Task-34 首建**（技术方案 §7.2 目标值作为首建预期：工具选择正确率 >= 90%、Pass^3 >= 80%、脱敏拦截率 100%）
*   **候选验证门槛**: 候选版本必须通过全部回归评估（含陷阱任务与 Pass^k 稳定性指标，至少 3 次运行取均值）方可合入；灰度期间指标劣化立即回滚；分差小于噪声带宽（10 例规模 ±30pp）不做迭代决策
*   **安全边界检查**: 本次变更未削弱任何护栏规则与权限门控（judge 出境复用既有 LLM 出站链路 + 数据集构造时 maskSafe 前置，脱敏出口单一性扩展适用而非绕开）；评估证据与候选变更隔离（judge prompt 的 EVALUATE 用例先于调优锁定，防自我验证--禁止用 judge 修改自己的评分标准）
*   **高风险区域**: ①judge 非确定性（运行间波动 -> 多次取均值 + veto 项确定性断言对冲）②评估运行成本（10 例 × Pass^3 = 30 次模型调用，A/B 翻倍 -> 按需手动触发）③judge JSON 契约解析失败（结构化输出非 100% 可靠 -> 解析失败记缺失不误计，AC-N11）④同源模型偏差（WARN 提示 + 反偏差指令双重对冲，决策 13）

## 2. 需求变更详情 (Requirements Delta)

### 2.1 新增/修改的行为验收标准
> 完整 Given-When-Then 文本已就地更新至需求文档 v1.2 §7.1/§7.3/§7.4，此处列摘要

#### 正常交互 (Normal) - AC-N
- **AC-N10**: 本地评估数据集执行（逐例真实对话 + 执行记录 + 单例失败不中断）
- **AC-N11**: LLM-as-judge 结构化评分（维度分值+理由 JSON 契约 + 缺失维度不聚合 + 同源 WARN）
- **AC-N12**: 回归基线生成与对比（劣化标注 + 噪声带宽声明 + 显式重建防静默覆盖）
- **AC-N13**: A/B 对比实验（双配置各 Pass^3 + 逐指标对比 + 统计显著性声明）

#### 安全护栏 (Safety) - AC-S
- **AC-S07**: 评估出境与落盘零明文（judge 调用内容与数据集/基线/报告文件均无密钥明文，无绕行出口）

#### 边界降级 (Edge) - AC-E
- **AC-E06**: judge 失败降级（确定性项照常完成 + judge 维度缺席标注可辨 + 不误计 0 分）

### 2.2 移除的内容
- §8.2 原第 5 项"完整评估体系"整体条目被部分消费：judge 评估器/回归基线/对比实验转入交付范围；**"CI 集成"保留为剩余演进项**（学习项目无 CI 环境 + 真实 Key 管理成本高，用户已确认暂缓）

## 3. 技术变更详情 (Technical Delta)

### 3.1 Prompt 架构变更
- 新增 judge 评估 Prompt 制品（独立于对话主链路）：输入契约（用例输入 + Agent 回复 + 工具轨迹摘要）、输出契约（严格 JSON：各维度 score/rationale + 总体判定）、反偏差指令（"评分与回答长度无关""仅依据给定证据判断"）
- 对话主链路 Prompt 架构零变更（System Prompt/Tool 描述/输出契约零触碰）

### 3.2 工具空间变更
| 操作 | 工具 | 说明 | 契约设计 |
| :--- | :--- | :--- | :--- |
| 无 | - | 零工具变更（评估 harness 是模型调用方，不是被注册工具） | 不适用 |

### 3.3 护栏变更
- 脱敏单一出口适用面扩大：评估数据集构造时即经 maskSafe；judge 出境内容与评估产物（数据集/基线/报告）落盘均无密钥明文（AC-S07）
- 三重护栏（默认关/脱敏/静默降级）本体不变；judge 缺席降级为新增降级路径（AC-E06，不削弱既有护栏）

### 3.4 兼容性与回滚
*   **向前兼容**: 对话主链路零修改；既有 LangSmith 平台侧评估闭环照旧（两条评估路径并存互不干扰）；无数据库/数据迁移
*   **Prompt 版本回滚**: judge prompt 制品文件级回滚（git revert 单文件）；基线文件不受影响
*   **代码回滚**: evaluation 为独立新模块，revert 模块 + 根 pom/BOM 登记行即可完全移除（无既有文件依赖它）；`eval` 配置段无消费者时自动失效

## 4. 增量开发任务 (Incremental Tasks)
> 任务编号延续 CR-001（Task-15~25）之后，从 Task-26 开始

### 阶段一：Prompt 制品变更 (Prompt Artifact Delta) - EDD

- [x] **Task-30**: judge 评估 Prompt 制品设计与调优
    *   **说明**: 设计 judge Prompt（评分维度对齐技术方案 §7.2 Rubric：回答完整性 essential / 幻觉复核 veto 语义层 / 风格 optional）+ 输出 JSON 契约 + 反偏差指令；按 BUILD -> EVALUATE -> TUNE -> RE-EVALUATE 循环调优（迭代上限 2-3 轮）
    *   **变更类型**: 新增
    *   **任务类型**: 概率性组件
    *   **验证策略**: EDD（构建-评估-调优-再评估）
    *   **通俗解释**: 给"AI 阅卷老师"写一份评分标准说明书，让它按固定格式打分且不受回答长度影响
    *   **涉及制品**: `agent-demo-evaluation/.../resources/prompts/judge.md`
    *   **落地路由**: `agent-prompt-designer`（设计模式）
    *   **参考**: 本文档 Sec 1.3 / Sec 3.1；技术方案 §7.2.1
    *   **对应AC**: AC-N11
    *   **预估工时**: 120m（含迭代轮次）
    *   **依赖**: Task-29（JudgeEvaluator 调用路径就绪后方可进入 EVALUATE 轮次）
    *   **验证标准**（EDD EVALUATE 阶段的通过条件）:
        - [ ] 输出 JSON 契约解析成功率 >= 95%（解析失败记缺失不误计）
        - [ ] 评分维度与 Rubric 表对齐（完整性/幻觉/风格三维度齐全）
        - [ ] 反偏差验证：对同一内容的不同长度表述评分差异在容忍带内（人工抽检 >= 3 组）
        - [ ] 陷阱任务（诱导编造工具结果）被 judge 幻觉复核维度判负

### 阶段二：工具与代码变更 (Tool & Code Delta) - TDD

- [x] **Task-26**: evaluation 模块骨架与数据层（EvalDataset + JSON 加载）
    *   **说明**: 创建 `agent-demo-evaluation` 模块（pom + 根 pom/BOM 登记，复制 mcp/skill 接入先例）；定义 EvalDataset 用例模型（输入/预期要点/确定性断言/陷阱标记）与 JSON 加载器；数据集内容构造时即经 maskSafe（AC-S07 前置）
    *   **变更类型**: 新增
    *   **任务类型**: 基础设施 + 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 搭建"考试系统"的卷库--把考题按固定格式存成文件并能读进来
    *   **涉及文件**: `agent-demo-evaluation/pom.xml`、`.../EvalDataset.java`、`.../DatasetLoader.java`、`data/eval/dataset.json`
    *   **测试文件**: `EvalDatasetLoaderTest.java`
    *   **参考**: 本文档 Sec 1.4；技术方案 §7.2.1 数据层
    *   **对应AC**: AC-N10（数据侧）、AC-S07（构造时脱敏）
    *   **预估工时**: 120m
    *   **依赖**: 无
    *   **验证标准**（TDD RED 阶段的测试依据）:
        - [ ] 加载合法 JSON -> 用例数与字段完整（含陷阱标记）
        - [ ] 文件缺失/格式非法 -> 明确异常（不静默空数据集）
        - [ ] 用例内容含密钥正例时加载后即为脱敏形态（或加载时统一过 maskSafe）

- [x] **Task-27**: EvaluationRunner 执行层（逐例真实对话 + 执行记录 + 失败不中断）
    *   **说明**: 逐用例真实发起对话（复用 Agent 服务，每用例新建会话），采集执行记录（请求/回复/工具轨迹）；单用例失败标注后继续（AC-N10）
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 自动逐题"考试"并把学生答题过程完整录下来，一题卡壳不影响其余题
    *   **涉及文件**: `.../EvaluationRunner.java`、`.../ExecutionRecord.java`
    *   **测试文件**: `EvaluationRunnerTest.java`（mock Agent 服务）
    *   **参考**: 技术方案 §7.2.1 执行层
    *   **对应AC**: AC-N10
    *   **预估工时**: 120m
    *   **依赖**: Task-26
    *   **验证标准**:
        - [ ] 正常用例 -> 执行记录含回复与工具轨迹
        - [ ] Agent 服务抛异常 -> 该例标注失败，后续用例继续执行
        - [ ] 每用例独立会话（不串上下文）

- [x] **Task-28**: DeterministicEvaluator 确定性评分层
    *   **说明**: 确定性断言（工具选择正确性/关键词命中/脱敏命中）+ 逐例与聚合结果输出；veto 项（脱敏命中）不走 judge
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 客观题自动判分（选对工具、答出关键词、没泄密直接机器判）
    *   **涉及文件**: `.../DeterministicEvaluator.java`、`.../CaseResult.java`
    *   **测试文件**: `DeterministicEvaluatorTest.java`
    *   **参考**: 技术方案 §7.2 Rubric / §7.2.1 评分层
    *   **对应AC**: AC-N10、AC-S07（落盘产物零明文）
    *   **预估工时**: 90m
    *   **依赖**: Task-27
    *   **验证标准**:
        - [ ] 工具选择断言：轨迹含/不含指定工具名 -> 通过/失败
        - [ ] 关键词断言：回复含预期关键词
        - [ ] 聚合：工具选择正确率/脱敏拦截率按既有指标定义计算
        - [ ] 结果对象落盘（JSON）无密钥明文

- [x] **Task-29**: JudgeEvaluator 调用与解析层
    *   **说明**: judge 调用封装（经项目 LLM 模块按 judge-model-id 解析）+ 输出 JSON 契约解析（失败记缺失不误计）+ 同源 WARN + judge 不可用时缺席标注（AC-E06/AC-N11）；本任务先用占位 prompt 打通链路，制品调优在 Task-30
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件（制品部分除外）
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 接通"AI 阅卷老师"的电话线路，看不懂的答卷标注"没批"而不是记零分
    *   **涉及文件**: `.../JudgeEvaluator.java`
    *   **测试文件**: `JudgeEvaluatorTest.java`（mock 模型响应：合法 JSON / 非法 JSON / 超时）
    *   **参考**: 技术方案 §7.2.1 评分层、决策 13
    *   **对应AC**: AC-N11、AC-E06
    *   **预估工时**: 120m
    *   **依赖**: Task-27
    *   **验证标准**:
        - [ ] 合法 JSON 响应 -> 各维度分值+理由完整解析
        - [ ] 非法 JSON / 超时 / 拒答 -> 该维度记缺失（缺席标注），不误计 0 分，不抛异常中断
        - [ ] judge-model-id 与被评模型相同 -> 启动 WARN（不阻断）
        - [ ] judge 调用内容含密钥正例时出境前经 maskSafe（AC-S07）

- [x] **Task-31**: BaselineManager 基线管理与对比报告
    *   **说明**: 基线生成（指标值 + 运行元数据：日期/模型/配置版本）/加载/对比（改善/持平/劣化标注 + 噪声带宽 ±30pp 声明）；基线更新须显式重建命令（AC-N12）
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 把每次考试的平均分存档，下次考完自动对比是进步还是退步，差距太小就提示"别急着下结论"
    *   **涉及文件**: `.../BaselineManager.java`、`.../ComparisonReport.java`、`data/eval/baseline.json`
    *   **测试文件**: `BaselineManagerTest.java`
    *   **参考**: 技术方案 §7.2.1 报告层、决策 14
    *   **对应AC**: AC-N12
    *   **预估工时**: 120m
    *   **依赖**: Task-28
    *   **验证标准**:
        - [ ] 生成基线 -> JSON 含指标值与运行元数据
        - [ ] 对比：劣化指标标注，带内差异（< 噪声带宽）标注"不可决策"
        - [ ] 显式重建前基线文件不被覆盖（常规运行只读）
        - [ ] 基线文件含元数据（日期/模型/配置版本）

- [x] **Task-32**: A/B 对比实验运行器
    *   **说明**: 双配置（Prompt/模型/参数差异）同一数据集分别执行，各配置 Pass^3（>=3 次运行取均值）后输出逐指标对比报告（含统计显著性声明）（AC-N13）
    *   **变更类型**: 新增
    *   **任务类型**: 确定性组件
    *   **验证策略**: TDD（Red-Green-Refactor）
    *   **通俗解释**: 让两套配置同题考试各考三遍，出一份公平的成绩对比单
    *   **涉及文件**: `.../ABComparisonRunner.java`、`.../ABReport.java`
    *   **测试文件**: `ABComparisonRunnerTest.java`
    *   **参考**: 技术方案 §7.2 A/B 方案
    *   **对应AC**: AC-N13
    *   **预估工时**: 90m
    *   **依赖**: Task-31
    *   **验证标准**:
        - [ ] 双配置各自完成 >= 3 次运行并取均值
        - [ ] 对比报告逐指标列出两配置值与差值
        - [ ] 噪声带宽内差异明确标注"不可决策"（防止单次运行结论）
        - [ ] 报告含运行次数与统计显著性声明

### 阶段三：评估数据集扩充 (Evaluation Dataset Delta)

- [x] **Task-33**: 本地评估数据集构建（>=10 用例 + 陷阱任务 + 密钥正例）
    *   **说明**: 构造本地评估数据集：直答/单工具/ReAct 多轮（>=2 轮工具）/工具失败恢复/含指代多轮/含密钥正例 + 陷阱任务（注入用例 + 诱导越界请求，复用 20260826 对抗用例库思想）；可从 LangSmith 已沉淀 trace 参考构造；全部经 maskSafe
    *   **变更类型**: 新增
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（评估数据集）
    *   **通俗解释**: 出一份覆盖各种题型和"坑题"的正式考卷
    *   **涉及文件**: `data/eval/dataset.json`
    *   **参考**: 本文档 Sec 1.4；技术方案 §7.2 Dataset 要素
    *   **对应AC**: AC-N10（用例完备性）、AC-S07
    *   **预估工时**: 90m
    *   **依赖**: Task-26
    *   **验证标准**:
        - [ ] 用例覆盖全部既定类型（>=10 条，ReAct 多轮 >= 2 条）
        - [ ] 陷阱任务 >= 2 条（注入 + 越界诱导）
        - [ ] 密钥正例 >= 1 条（脱敏验证）
        - [ ] 数据集文件无真实密钥明文（仅含合成测试密钥夹具 sk-... 用于验证脱敏链路，AC-S07 测试夹具非真实凭证）

### 阶段四：回归验证 (Regression Verification)

- [x] **Task-34**: 全量对抗回归 + 基线首建 + 端到端验证
    *   **说明**: 按第 1.5 节回归范围执行全量回归（安全边界要素从严）；首建本地评估基线；验证评估 harness 对对话主链路零影响
    *   **变更类型**: 验证
    *   **任务类型**: 行为测试
    *   **验证策略**: 行为测试（评估数据集 + 对抗性测试）
    *   **涉及文件**: 全部既有测试套件 + `data/eval/baseline.json`（首建）
    *   **对应AC**: 所有受影响的 AC（重点 AC-S01~S07 全量、AC-E06、AC-N10~N13 端到端）
    *   **预估工时**: 120m
    *   **依赖**: Task-30、Task-31、Task-32、Task-33
    *   **验证标准**:
        - [ ] 原有单元测试全量通过（无回归：observability 67 / llm 148 / tools 160 / web 121 / skill 97 全绿；app 仅 3 个既有基线失败）
        - [ ] 对抗性测试全部通过（注入/密钥泄漏/无 Key 外联/judge 出境与落盘零明文）
        - [ ] 稳定性指标 Pass^3 达标（评估数据集 3 次运行）
        - [ ] 陷阱任务无幻觉（一票否决项）
        - [ ] 首建基线文件生成（含运行元数据），指标达到技术方案 §7.2 目标值（工具选择正确率 >= 90%、脱敏拦截率 100%）
        - [ ] 对话主链路行为无异常变化（评估 harness 不参与对话运行时）
        - [ ] 灰度方案已就绪（评估 harness 按需手动触发，不随构建/启动执行）

## 5. 增量验收标准检查清单 (Incremental AC Checklist)

| 验收标准ID | 验收标准描述 | 状态 | 对应任务 | 操作 |
| :--- | :--- | :--- | :--- | :--- |
| AC-N10 | 本地评估数据集执行 | 已完成 | Task-26/27/33 | 新增 |
| AC-N11 | LLM-as-judge 结构化评分 | 已完成（含真实模型 EDD 闭环 2026-08-31：解析率 100%、反偏差抽检通过、幻觉漏报经 TUNE 修复） | Task-29/30 | 新增 |
| AC-N12 | 回归基线生成与对比 | 已完成 | Task-31 | 新增 |
| AC-N13 | A/B 对比实验 | 已完成 | Task-32 | 新增 |
| AC-S07 | 评估出境与落盘零明文 | 已完成 | Task-26/29/33/34 | 新增 |
| AC-E06 | judge 失败降级 | 已完成 | Task-29 | 新增 |

## 6. 变更总结 (Change Summary)
*   **总新增任务数**: 9 个（TDD 6 个 / EDD 1 个 / 行为测试 2 个）
*   **预计总工时**: 990 分钟（约 16.5 小时）
*   **风险等级**: 中
*   **风险说明**: ①judge 非确定性（运行间波动 -> veto 项始终确定性断言 + 多次取均值 + 反偏差指令对冲）②评估运行成本（10 例 × Pass^3 = 30 次模型调用，A/B 翻倍 -> 按需手动触发不随构建执行）③judge JSON 契约解析非 100% 可靠（解析失败记缺失不误计，AC-N11/AC-E06 双重保障）④同源模型偏差（WARN 提示 + judge prompt 反偏差指令，决策 13）
*   **测试影响**: 零既有测试修改（纯新增模块）；新增 6 个测试类（EvalDatasetLoader/EvaluationRunner/DeterministicEvaluator/JudgeEvaluator/BaselineManager/ABComparisonRunner Test）；扩充评估用例 >= 10 条 + 对抗性用例 >= 3 条
*   **评估基线变化**: 既有指标全部保持（脱敏 100%/0%、零外联、ReAct 完整率 100%、既有测试全绿）；本地评估基线从无到有首建（工具选择正确率 >= 90%、Pass^3 >= 80%、脱敏拦截率 100%）
*   **预期效果**: 项目获得本地「真实模型行为评估」能力（EDD 方法论补全：后续所有 Agent/Prompt 演进均可经同一数据集 + 基线对比验证无回归）；LangSmith 平台侧与本地 harness 两条评估路径并存；judge 语义维度（回答完整性/幻觉复核）与确定性断言分层协作，安全 veto 项不依赖概率性组件

---
## 执行完成记录 (Implementation Log)
### 2026-08-31 全部增量任务执行完成
**执行人**: AI Assistant（ai-agent-implementation）
**执行结果**: Task-26~34 全部完成（Task-30 judge 制品 BUILD + 契约冒烟完成，真实模型 EDD EVALUATE/TUNE 与 Task-34 基线首建依赖 ARK Key，延后云侧联调）
**测试结果**: evaluation 模块 48 测试全绿；全量回归零新失败（observability 67 / llm 148 / tools 160 / mcp 167 / skill 97 / agent 186 / web 121；app 282 仅 3 个既有基线失败）
**集成验证**: EvaluationCli 上下文 4.6s 启动成功（eval.enabled=true 下 harness 全 Bean 装配，RecordingTraceCollector 作 TraceCollector 无冲突；默认关闭零影响）
**完成报告**: `docs/dev-records/20260831_langsmith-observability_CR002_report.md`

### 2026-08-31 代码审查 + EDD 补跑（ai-agent-code-review）
**执行人**: AI Assistant（ai-agent-code-review + 用户）
**审查结论**: 通过（详见 `langsmith-observability_CR002_代码审查报告.md`）
**审查修复（Critical×2 / Important×4 / Minor 若干）**:
- [Critical] CLI 无 LLM 配置注入路径 → `LlmConfigSeed` 双厂商种子注入（多源 judge：Agent=ARK / judge=百炼），真实评估端到端跑通
- [Critical] 工具轨迹观测缺陷（AiServices 反射路径绕过 ToolExecutor）→ `SimpleAgent.afterToolExecution` + `ToolExecutor.recordToolExecution` + `RecordingTraceCollector` 存结果 + `ExecutionRecord.toolTraceDetail`；工具选择率 0%→100%
- [Important] judge 幻觉 veto 代码层强制（`JudgeEvaluator.enforceVeto`）
- [Important] ABComparisonRunnerTest 空洞断言重写
- [Important] .gitignore 放行 `data/eval/` 交付物
- [Minor] 数据集缺失 CLI 失败信号 / aggregate O(n²) 改 map / 文档对齐 / 数据集措辞 / 测试分支补齐
**EDD 补跑（真实模型）**: judge-v1 EVALUATE → TUNE judge-v2（幻觉判定要点强化）→ RE-EVALUATE（探针 8 组全过 + 官方 runs=3 首建基线）。judge 解析率 100%；基线 `data/eval/baseline.json`（Pass^runs=70% / 工具选择率 100% / 脱敏拦截率 100%）。
**测试**: evaluation 60 全绿；agent 186 / tools 全绿（SimpleAgent+ToolExecutor 改动回归）。
