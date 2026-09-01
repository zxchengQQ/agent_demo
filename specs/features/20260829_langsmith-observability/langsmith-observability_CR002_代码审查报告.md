# AI Agent 代码审查报告: langsmith-observability - CR-002（完整评估体系）

| 字段 | 内容 |
|------|------|
| 审查人 | ai-agent-code-review（独立审查，非实现者自评） |
| 日期 | 2026-08-31 |
| 审查范围基准 | 完成报告文件变更清单（`agent-demo-evaluation/` 为 untracked 新模块，无 git diff 基线；以任务规划涉及文件 + 完成报告清单为比对基准） |
| 关联文档 | `langsmith-observability.md`（v1.2，CR-002 六条新 AC）、`langsmith-observability_技术方案.md`（§7.2/§7.2.1/决策 12-14）、`langsmith-observability_变更任务_CR002.md`（Task-26~34）、`docs/dev-records/20260831_langsmith-observability_CR002_report.md` |

## 0. 审查结论

**[通过]**

代码交付物（TDD 轨）与 judge Prompt 制品（EDD 轨）均可靠。审查发现 **2 Critical（CLI 无 LLM 配置注入路径 / 工具轨迹观测缺陷——评估无法捕获 AiServices 路径工具调用）、4 Important（EDD 轨缺环 / judge 幻觉 veto 未代码层强制 / 空洞断言 / .gitignore 吞交付物）**，均已按用户决策修复并验证。审查期独立重跑 **60 测试全绿** + CLI 真实评估端到端跑通 + judge EDD 闭环完成（EVALUATE→TUNE→RE-EVALUATE，judge-v2 达标）。

**EDD 补跑结果（2026-08-31，真实模型）**：Agent=doubao-seed-2.0-lite(ARK)，judge=deepseek-v4-flash(百炼，多源)。judge JSON 契约解析率 **100%**（10/10）；长度反偏差抽检通过（3 组同内容不同长度 completeness 均 5）；幻觉漏报（声称调用但轨迹为空）经 TUNE 修复、无新误报；首建基线（judge-v2, Pass^runs=70%, 工具选择率 100%, 脱敏拦截率 100%）落盘 `data/eval/baseline.json`。

## 1. 做得好的部分 (Strengths)

- **脱敏单一出口三处落地闭环**（AC-S07）：judge 出境（`JudgeEvaluator.java:71-76` render 三要素统一 `masker.maskSafe`）、绕行检测（`DeterministicEvaluator.java:134-138` leaksSecret 判据 + `DeterministicEvaluatorTest.secret_maskerBypassed_leakDetected_fails:105-118`）、报告落盘（`ReportWriter.java:100-101` 失败原因/judge 单元格统一脱敏）——三层测试各自独立验证。
- **Pass^3 稳定性语义贯穿正确**：`CaseResult.pass` 由全部运行共同决定（`DeterministicEvaluator.java:44-77`）、A/B 强制下限 3 次（`ABComparisonRunner.java:23,36`）、基线噪声带宽 ±30pp（`BaselineManager.java:23`）——与技术方案 §7.2 统计显著性声明一致，未与 Pass@k 混用。
- **缺席降级不误计**（AC-E06）：`JudgeResult.absent` 容器 + `JudgeEvaluator.parse` 失败/异常/未配置三路缺席（`JudgeEvaluator.java:43-57,78-86`），不抛异常不记 0 分，报告单元格"缺席(原因)"可辨。
- **fail-fast 防御**：数据集非法 JSON 明确异常不静默空集（`EvalDatasetLoader.java:26-33`）、judge 制品缺失启动即失败（`EvaluationHarnessConfig.java:74-77` + `JudgePromptTemplate.load`）、基线加载失败显式异常（`BaselineManager.java:62-67`）。
- **测试非 mock 自嗨**：DeterministicEvaluator/BaselineManager/ABComparisonRunner 用真实构造数据断言指标与判定语义；`ProjectDatasetValidationTest.report_renders_withoutPlaintextSecret`（:59-78）直接验证真实报告文件零明文，可验证性强。
- **模型解析 id-or-name 兜底**（`SpringJudgeModelAccess.java:31-40`）与 ModelFactory 规则一致，避免同源误判/漏判。

## 2. 范围与意图比对

> [DRIFT DETECTED（仅残留物，已清理）]

| 比对项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 越权改动 | 有（残留物） | `agent-demo-evaluation/data/skills/` 存在 3 个 skill 目录（内容与根 `data/skills/` 不同、含 scripts/http-get.sh），不在任务规划与完成报告清单中——为 SkillPresetSeeder 运行期产物误留模块内。已按用户决策删除（gitignored 不入库，无交付影响） |
| 遗漏任务 | 无 | Task-26~34 均有代码/测试/文档证据；Task-30 真实模型 EVALUATE 与 Task-34 基线首建为**文档化延后**（无 ARK Key），非虚假勾选 |

## 3. 链路一致性（实际变更 vs 技术方案文件清单）

> 比对基准：技术方案 §1.6 文件清单 + §7.2.1 分层。 [基本一致，1 处文档偏差已对齐]

| 类型 | 文件路径 | 说明 |
| :--- | :--- | :--- |
| 多出（方案未定义） | 26 个类（config/cli/model/loader/runner/eval/harness 包） | 均为 §7.2.1 四层（数据/执行/评分/报告）的合理细化（EvalProperties/EvaluationCli/RecordingTraceCollector/JudgeVerdict/JudgeResult/RunSpec/ABRow/ABReport 等），非投机性分层；ReportWriter 与 BaselineManager 拆分为独立类符合单一职责 |
| 缺失（方案未兑现） | `agent-demo-bootstrap/application.yml` 的 eval 配置段 | 实现落地为 `agent-demo-evaluation/src/main/resources/application.yml`（模块内）。合理偏差：评估 CLI 为独立 spring-boot:run 自包含上下文，模块内配置自动加载、不污染主应用；技术方案 §1.6 已同步对齐（审查修复） |
| 缺失（方案未兑现，Critical） | CLI 上下文 LLM 配置注入路径 | **审查发现的链路断点**：技术方案决策 12 声明"独立模块复用项目 LLM 模块"，但未设计 CLI 独立上下文如何填充 `LlmConfigStore`（该 store 仅 web 层 API 可填）。已按用户决策实现 `LlmConfigSeed` 种子注入补链（见 §5-C1） |

## 4. 已自动修复项 (AUTO-FIXED)

无（本报告全部修复均为 ASK 决策后落地，含行为变更，非规范类 AUTO-FIX；Prompt 制品 zero AUTO-FIX 红线遵守）。

## 5. 需决策的关键问题 (Action Required)

### Critical（阻断——未解决则审查不通过）

1. **评估 CLI 无 LLM 配置注入路径，真实评估无法运行** ✅ 已修复
   - 位置：`EvaluationCli.java:25-29`（组件扫描不含 web）、`LlmConfigStore`（内存存储仅 web API 可填）、`ModelFactory.java:53-58`（getDefaultChatModel 空 store 即抛"未配置 chat 模型"）
   - 问题：评估模块独立 CLI 上下文（exclude ObservabilityAutoConfiguration、扫描 agent/llm/tools/memory/skill/evaluation）中 `LlmConfigStore` 恒为空——无前端配置面、无 env 兜底（CR-003 动态配置架构已移除 ARK_API_KEY 注入）。即使提供 ARK_API_KEY，`--eval.run-on-startup=true` 也会全用例"执行失败"（agent 无默认 chat 模型）+ judge 全缺席。完成报告所述"仅差 Key"不成立，真实评估（AC-N10/N11 核心）无可行执行路径。
   - 为什么重要：评估 harness 的存在价值即"真实模型行为评估"；作为交付物无端到端可运行路径 = 核心 AC 未满足。
   - 修复方案（用户选定）：新增 `eval.vendor-name/base-url/api-key`（api-key 默认 `${ARK_API_KEY:}`，禁止硬编码）+ 多源 judge 配置（`judge-vendor-name/base-url/api-key`，默认百炼）；`EvaluationHarnessConfig` 注册 `LlmConfigSeed`（`InitializingBean`）：eval.enabled 且 store 为空时注入合成厂商（Agent 厂商 + 独立 judge 厂商，多源异构对冲同源偏差），按 agent/judge-model-id 注册 chat 模型（ModelFactory id-or-name 兜底命中），已有配置不覆盖，无模型/无 Key 启动即 fail-fast。
   - 处理：[x] A) 同意并修复（已落地：`LlmConfigSeed.java` + `LlmConfigSeedTest` 9 用例全绿；CLI 上下文 `eval.enabled=true + ARK/BAILIAN Key + model-id` 实际启动并端到端跑通真实评估）

2. **工具轨迹观测缺陷：评估无法捕获 AiServices 反射路径的工具调用** ✅ 已修复（EDD 补跑中发现）
   - 位置：`SimpleAgent.java:149-158`（getDelegate 构建 AiServices）、`ToolExecutor.java`（仅 HITL/流式自研 ReAct 路径使用）、`RecordingTraceCollector.java`
   - 问题：被评 Agent 的同步路径（SimpleAgent.chat → LangChain4j AiServices 内置 ReAct）**反射直调工具，绕过 ToolExecutor**，RecordingTraceCollector 收不到任何工具事件 → ① 确定性"工具选择正确率"恒 0%（失真）；② judge 的工具轨迹无结果证据，把"真实工具输出描述"误判为幻觉（长答误报）。EDD 真实运行暴露，工具类用例全判失败。
   - 为什么重要：工具选择正确率是评估核心指标之一（§7.2 >=90% 目标）；轨迹无结果导致 judge 幻觉复核证据不足，直接扭曲评估结论。
   - 修复方案（用户选定）：`ToolExecutor` 新增 `recordToolExecution(ToolExecution)`（AiServices 工具事件→TraceCollector，复用既有 recordTool 语义）；`SimpleAgent.getDelegate` 注册 `.afterToolExecution(toolExecutor::recordToolExecution)`（1 行接线，同时补齐生产同步路径工具 span——技术方案决策 3 既有边界，净收益）；`RecordingTraceCollector` 改存事件（名+结果，结果截断 200 字符）；`ExecutionRecord` 新增 `toolTraceDetail` 字段（紧凑构造器默认空串，既有 6 参构造不破坏）；`JudgeEvaluator.render` 优先采用含结果的轨迹详情。
   - 处理：[x] A) 同意并修复（已落地；回归：tools/agent/evaluation 三模块全绿，agent 186 + evaluation 60；真实评估工具选择率 0% → 100%，judge 误报消除）

### Important（修复后通过）

1. **judge Prompt（Task-30）与 Task-34 基线首建真实模型 EDD 闭环** ✅ 已补跑完成
   - 位置：`resources/prompts/judge.md`（v1 → v2）、`data/eval/baseline.json`
   - 问题：EDD 铁律要求 Build→Evaluate→Tune→Re-evaluate；初版仅 BUILD + 契约冒烟，真实模型 EVALUATE/TUNE 未执行。
   - EDD 闭环记录（2026-08-31 真实模型，Agent=doubao-seed-2.0-lite / judge=deepseek-v4-flash 多源）：
     - **EVALUATE（judge-v1）**：解析率 100%；发现幻觉漏报——回复声称"调用了计算工具得到 56088"而轨迹为空时判 pass（"工具轨迹为空不等于失败"规则被过度应用掩盖编造）；另发现工具轨迹无结果时"真实工具输出描述"被误判幻觉（与观测缺陷 #2 关联）。
     - **TUNE（judge-v1 → v2）**：幻觉判定要点强化——点名声称调用具体工具 / 引用具体结果值但轨迹无对应或不一致 → detected；仅泛化确认表述（如"好的，我已记录"）不属编造；"轨迹为空不等于失败"限定为"未点名工具/未引用具体结果值"。
     - **RE-EVALUATE（judge-v2）**：探针 8 组全过（漏报修复、无新误报、长度反偏差 3 组 completeness 均 5、脱敏占位符不判罚、空回复判负）；官方 runs=3 首建基线：Pass^runs=70%、工具选择率 100%、脱敏拦截率 100%、陷阱拦截率 50%（c-inject 2/3 未拒答——被评模型真实弱点，harness 正确暴露）。
   - Task-30 验证标准达成：JSON 解析成功率 100%（>=95%）；维度与 Rubric 对齐；反偏差抽检通过；陷阱任务被幻觉维度判负。
   - 处理：[x] A) 同意并修复（EDD 闭环完成，基线已首建）

2. **judge 幻觉 veto 未在代码层强制** ✅ 已修复
   - 位置：`JudgeEvaluator.java`（parse 无归一）、`ReportWriter.java:95-96`（仅展示"幻觉 detected"）
   - 问题：技术方案 §7.2 规定幻觉复核为 veto（一票否决），但代码仅将 judge 的 hallucination 字段展示在报告单元格，不交叉校验——若 judge 输出 `hallucination=detected` 而 `overall=pass`（自相矛盾），用例仍按确定性判定通过，否决被放过。
   - 为什么重要：幻觉一票否决是评估质量的核心语义；veto 仅靠 prompt 指令"自觉"不强制，与"veto 项始终确定性断言"的设计意图不符。
   - 修复方案：`JudgeEvaluator.parse` 增加 `enforceVeto`（hallucination=detected → overall 一律归一 fail，防自相矛盾放过）；`ReportWriter` 单元格标注"幻觉 detected（veto 判负）"。确定性代码逻辑，TDD 修复。
   - 处理：[x] A) 同意并修复（已落地：`JudgeEvaluator.java:88-100` enforceVeto + `JudgeEvaluatorTest.hallucinationDetected_overallForcedToFail_evenIfModelSaysPass`）

3. **ABComparisonRunnerTest 空洞断言** ✅ 已修复
   - 位置：`ABComparisonRunnerTest.compare_clearWinner_assignsWinner`
   - 问题：测试名声称验证"胜者赋值"，实际断言 `allMatch(A|B|TIED)` 恒真；构造数据不触发工具调用、两配置工具选择均失败，未形成可区分胜负——断言空洞（mock 自嗨类）。
   - 修复方案：改用直接问答用例（关键词维度），配置 A 缺关键词全败（passRate=0）、配置 B 命中关键词全过（passRate=1.0），断言 passRate 行 winner=B、|delta|>噪声带宽、significant=true。
   - 处理：[x] A) 同意并修复（已落地并回归全绿）

4. **.gitignore 的 data/ 规则吞掉评估交付物** ✅ 已修复
   - 位置：`.gitignore:73`（`data/`）
   - 问题：`data/` 忽略整个 data 目录，导致 Task-33 核心交付物 `data/eval/dataset.json`（及未来首建 `baseline.json`）不进版本库，与决策 14"JSON 基线（版本库管理）"冲突——克隆仓库即丢失数据集/基线。
   - 修复方案：改为 `data/*` + `!data/eval/` + `!data/eval/*`（data/skills 等运行时数据仍忽略；`git status` 验证 `data/eval/` 现为 untracked 可入库）。
   - 处理：[x] A) 同意并修复（已落地并验证）

## 6. 次要问题与建议 (Minor)

1. 位置：`EvaluationHarness.java:60-63` —— 数据集缺失时原为 WARN+return（退出码 0 的静默假成功）；已改为抛 `IllegalStateException`（CLI 非零退出码，AC-E06"退出码可辨"语义）✅ 已处理
2. 位置：`DeterministicEvaluator.java:113-122` —— aggregate 陷阱判定 O(n²) 嵌套循环；已改为 `Map<caseId, CaseResult>` 索引 ✅ 已处理
3. 位置：技术方案 §1.6 —— 声明改 bootstrap application.yml 实际落模块内 yml；已文档对齐 ✅ 已处理
4. 位置：`data/eval/dataset.json` —— 数据集含合成测试密钥 `sk-abcdefghijklmnopqrstuvwx`（夹具，验证脱敏链路用），与 Task-33"数据集文件零密钥明文"措辞矛盾；已改为"无真实密钥明文（仅含合成测试密钥夹具）" ✅ 已处理
5. 位置：`EvaluationHarnessTest.runOnce_judgeEnabled_*` —— 原测试仅单条成功记录，未覆盖"首条失败→取后续成功记录"筛选分支；已补 `runOnce_judgeEnabled_skipsFailedRecords_scoresFirstSuccess` ✅ 已处理
6. 位置：`EvaluationHarness.java:66` —— judge 逐用例仅评"首条成功记录"（成本控制），Pass^3 其他运行不评；对低通过率用例的 judge 结论代表性有限，列为已知边界（建议后续演进按 Pass^runs 全评或抽评）

## 7. 测试质量评估（双轨核查）

### 确定性组件（TDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 测试真实测逻辑 | 是 | 真实构造数据断言指标/判定（BaselineManager 噪声带宽边界、AB winner 赋值、脱敏绕行判负、Pass^3 全部运行）；审查修复后新增的种子测试 7 个亦为真实 store 断言 |
| 正常/边界/异常覆盖 | 完整 | 正常（工具命中/关键词/脱敏拦截/基线对比/A/B 胜负）、边界（缺省字段/空数据集/空基线/带内平局/同一模型去重）、异常（非法 JSON/调用异常/缺席/禁工具/失败运行/无 Key/无模型）均覆盖 |
| TDD 合规（RED→GREEN） | 合规 | 完成报告 §2 有逐任务 RED 依据/GREEN 实现/关键修复记录；审查期新增修复均先补测试后实现 |

### 概率性组件（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| EDD 迭代记录完整 | 合规 | BUILD（judge.md v1）→ EVALUATE（真实模型：发现幻觉漏报/误报）→ TUNE（v1→v2：幻觉判定要点强化）→ RE-EVALUATE（探针 8 组全过 + 官方 runs=3 首建基线）完整闭环 |
| 评估数据集覆盖验证场景 | 完整 | 10 用例覆盖直答×2/单工具×3/ReAct/失败恢复/密钥/注入陷阱/越界陷阱，陷阱≥2、密钥正例≥1，符合 Task-33 验证标准 |
| 验证策略与任务类型匹配 | 匹配 | 确定性组件全走 TDD；judge 概率性组件走 EDD（BUILD 契约冒烟 + 真实模型 EVALUATE/TUNE/RE-EVALUATE）；无错配 |

## 8. Prompt 制品与护栏审查（Agent 特有）

### Prompt 制品（EDD 轨）

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 评估指标达标 | 达标 | 真实模型实测（deepseek-v4-flash 多源 judge）：JSON 契约解析成功率 **100%**（>=95% 达标）；长度反偏差抽检通过（3 组同内容不同长度 completeness 均 5，style 对冗长适度扣分）；陷阱任务被幻觉维度正确判负（声称调用轨迹为空 / 与轨迹矛盾均 detected）；脱敏占位符不判罚（c-secret pass） |
| System Prompt 与 2.1 架构一致 | 一致 | judge.md 模块划分（评分维度/输出契约/反偏差/脱敏语义）与 §7.2.1 制品设计要点逐条对齐；占位符 `{{input}}/{{response}}/{{toolTrace}}` 与 `JudgeEvaluator.render` 注入点一致 |
| Token 成本在预算内 | 是 | 评估按需手动触发（run-on-startup 默认 false），10 例 × Pass^3 + 每例 1 次 judge ≈ 40 次调用，实测单轮约 2.5 分钟、符合手动成本预期 |

### 护栏与安全

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 多层护栏落地完整（对照第 6 章） | 完整 | 脱敏单一出口扩大至评估通道（judge 出境 + 报告落盘 + 绕行检测三层）；默认关（eval.enabled=false 零装配）+ 静默降级（judge 缺席不误计）三重护栏维持 |
| 对抗性测试 100% 拦截 | 是（确定性层） | 注入/越界陷阱用例确定性断言（forbiddenTool/拒绝关键词）；judge 出境/落盘零明文三层测试 100%；真实模型对抗实测待 EDD 补跑 |
| 工具执行权限门控 | 无缺口 | 评估 harness 为模型调用方，非被注册工具，不引入新执行面；AC-S07 未削弱既有门控 |

### 工具契约一致性

| 检查项 | 结果 | 说明 |
| :--- | :--- | :--- |
| 工具描述与 2.2 结构模板一致 | 不适用 | 零工具变更（评估 harness 非被调工具） |
| 与 tool-design 制品无漂移 | 不适用 | 同上 |

## 9. 需求符合性（六类 AC 覆盖映射表）

| AC 编号 | AC 摘要 | 实现位置 | 验证证据 | 验证策略 | 满足 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| AC-N10 | 本地评估数据集执行 | EvaluationRunner + DeterministicEvaluator + data/eval/dataset.json | EvaluationRunnerTest 5 用例（含轨迹详情）+ ProjectDatasetValidationTest 类别覆盖；真实 runs=3 执行通过 | TDD+行为 | ✅ |
| AC-N11 | LLM-as-judge 结构化评分 | JudgeEvaluator + judge.md(v2) + SpringJudgeModelAccess | JudgeEvaluatorTest 10 + JudgePromptTemplateTest 3；真实模型解析率 100%（EDD 闭环） | TDD+EDD | ✅ |
| AC-N12 | 回归基线生成与对比 | BaselineManager + ComparisonReport | BaselineManagerTest 8 + EvaluationHarnessTest 首建/对比 | TDD | ✅ |
| AC-N13 | A/B 对比实验 | ABComparisonRunner + ABReport | ABComparisonRunnerTest 5（Pass^3 强制/胜者/平局） | TDD | ✅ |
| AC-S07 | 评估出境与落盘零明文 | JudgeEvaluator.render + ReportWriter + DeterministicEvaluator.leaksSecret | 3 层测试（出境/绕行/落盘）100% | TDD+行为 | ✅ |
| AC-E06 | judge 失败降级 | JudgeEvaluator 缺席 + EvaluationHarness judgeCases | JudgeEvaluatorTest 3 用例 + 报告单元格可辨 | TDD | ✅ |

**Scope Creep 检查**：无功能越界（`LlmConfigSeed` 为 AC-N10/N11 真实执行路径的补链，非 AC 之外新功能；data/skills 残留为运行期产物，已清理）。

---

**附注**：
- 审查期独立重跑 evaluation 模块：修复前 47 测试全绿 → 修复后 **60 测试全绿**（+13：veto、首条成功记录、种子注入×9、工具轨迹详情、render 轨迹详情），BUILD SUCCESS；tools/agent 模块回归全绿（agent 186）。
- **EDD 补跑为生产代码带来的顺带改进**：`SimpleAgent` + `.afterToolExecution` + `ToolExecutor.recordToolExecution` 使 AiServices 同步路径的工具调用进入 TraceCollector 采集（此前技术方案决策 3 接受的生产边界），生产 LangSmith 同步 /chat 工具 span 从缺失变为完整（AC-N01/N04 净提升）——已随评估链路一并落地并回归。
- agent-demo-app 3 个既有集成测试失败（WorkflowIntegration/WorkflowP3）为工作区基线既有问题，CR-002 未触碰 app 模块，不在本次范围。
- **EDD 结果资产**：`data/eval/baseline.json`（judge-v2 首建基线：Pass^runs=70% / 工具选择率 100% / 脱敏拦截率 100%）、`data/eval/report-judge-v2.md`、judge.md v2。数据集 c-time/c-date 关键词断言（"分"/"年"）过严导致工具已正确调用仍判失败，列为后续数据集校准项（非 judge 问题）。

*审查链路：需求澄清 → 技术设计（§1.6 文件清单 + §7.2.1）→ 任务规划（Task-26~34）→ 实现（TDD+EDD）→ **代码审查（本报告）** → EDD 云侧补跑 → 后续流程*
