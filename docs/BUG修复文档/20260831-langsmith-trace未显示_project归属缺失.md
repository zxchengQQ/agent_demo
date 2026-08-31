# BUG 修复报告

## 基本信息
- 日期：2026-08-31
- 报告人：AI 编码助手（配合用户排查）
- 模块/功能：agent-demo-observability（LangSmith 可观测性）
- 严重级别（低/中/高）：高
- 影响范围：所有开启 LangSmith 的用户——trace 未上传或落入错误 project，功能不可用
- 严重级别依据：开启后可观测性核心价值（查看 trace）完全不可达；但可绕过（正确配置后可用），不阻断对话功能

## 问题描述
- 期望结果：正常对话后，LangSmith UI 中应能看到对应的 trace（LLM span + 工具 span + 根 span）
- 实际结果：提供了 API Key 并开启（`--langsmith.enabled=true` + `LANGSMITH_API_KEY`）后，正常对话，LangSmith 上看不到对应记录；应用日志出现
  `WARN HttpExporter - Failed to export spans. Server responded with HTTP status code 404`

## 复现步骤（必须可复现）
1) 启动脚本传入 `-LangSmithKey 'lsv2_xxxx'`（或设 `LANGSMITH_API_KEY` 环境变量）启动应用
2) 确认启动日志出现 `LangSmith 已启用，装配 OtlpTraceCollector`
3) 在对话页发起一次含 LLM 调用的对话
4) 应用日志出现 `HTTP status code 404` 导出失败；smith.langchain.com 中看不到 trace

## 复现环境
- 设备/系统：用户本地 Windows（PowerShell 启动脚本）
- 应用版本/配置：agent-demo 1.0.0，Spring Boot 3.2.5，OTel SDK 1.42.1
- 相关依赖或外部条件：LangSmith 云（US 区域默认 endpoint）

## 定位过程
- 关键线索：
  1. 应用日志 `Failed to export spans. Server responded with HTTP status code 404` —— 404 指向**路径不存在**，而非认证（401）或网络问题
  2. 反编译 `opentelemetry-exporter-otlp/.../OtlpHttpSpanExporter` 与 `opentelemetry-exporter-sender-okhttp/.../OkHttpHttpSender`：构造函数直接 `HttpUrl.get(endpoint)`，**exporter 把 endpoint 当作完整 URL 直接使用，不追加任何信号路径**（默认值即 `http://localhost:4318/v1/traces`，已含 `/v1/traces`）
  3. 配置值 `langsmith.endpoint: https://api.smith.langchain.com/otel/`（缺 `/v1/traces`）→ exporter 实际 POST 到 `https://api.smith.langchain.com/otel/` → LangSmith 404
  4. 用本地内嵌 HTTP server 复现：配置 `.../otel/` 时 exporter 请求路径为 `/otel/`（非 `/otel/v1/traces`）；用 `resolveOtlpTracesEndpoint` 补全后请求路径为 `/otel/v1/traces`
  5. 对照官方 Python SDK（langsmith-sdk `_otel_client.py`）：Python exporter 会追加 `/v1/traces`，故其配置 `.../otel` 有效；**Java exporter 行为不同（不追加）**，照搬 Python 配置即踩坑
  6. 附加发现：生产装配未设置 Resource（service.name）也未传 `Langsmith-Project` header，即使上传成功 trace 也会落入 OTel 默认 project `unknown_service:java`，用户按 default project 找不到（官方 Python/JS SDK 均显式设置归属）
- 排查路径：认证头（官方 SDK 确认 x-api-key 正确）→ endpoint 路径（反编译 exporter 确认不追加信号路径 + 内嵌 server 复现）→ project 归属（默认 service.name=unknown_service:java 复现测试）
- 根因说明（两个独立问题）：
  - **直接根因（404）**：`langsmith.endpoint` 配置缺 `/v1/traces` 信号路径。OTel Java 的 `OtlpHttpSpanExporter.setEndpoint` 要求完整 URL（内部直接使用、不追加），配置只给 base 会导致 POST 到不存在路径 → 404 → trace 根本未上传。
  - **隐藏问题（project 归属）**：装配未设置 `service.name` 资源属性 / `Langsmith-Project` header，trace 上传后落入 `unknown_service:java` project，用户按 default 找不到。

## 修复方案
- 修改点说明：
  1. 端点解析 `resolveOtlpTracesEndpoint(endpoint)`：去尾部斜杠；未以 `/v1/traces` 结尾则自动补全。兼容 base / 带尾斜杠 / 完整端点三种配置习惯，统一为 OTel Java exporter 需要的完整 URL。
  2. `langsmith.endpoint` 默认值改为完整端点 `https://api.smith.langchain.com/otel/v1/traces`。
  3. project 归属（上轮已修，保留）：新增 `langsmith.project` 配置（默认 `agent-demo`），`SdkTracerProvider.setResource(service.name)` + exporter `Langsmith-Project` header 双保险。
- 影响评估：仅影响启用状态下的导出元数据与上报 URL；默认关（Noop）路径零改动零影响
- 风险点：自定义自建 OTLP collector 若路径不含 `/v1/traces` 会被自动补全（本项目仅面向 LangSmith，可接受；如需自定义可在后续扩展为显式开关）
- 回滚方案：移除 `resolveOtlpTracesEndpoint` 补全并恢复 base 配置（不推荐，会再次 404）

## 变更内容
- 代码/配置改动摘要：
  - `ObservabilityAutoConfiguration.java`：新增 `resolveOtlpTracesEndpoint`（自动补全 /v1/traces）；endpoint 默认值改为完整 URL；装配设置 Resource(service.name) + `Langsmith-Project` header（project 归属）
  - `application.yml`：endpoint 改为 `https://api.smith.langchain.com/otel/v1/traces`；新增 `project: agent-demo` 说明
  - `ObservabilityAutoConfigurationTest`：新增 `resolveOtlpTracesEndpoint` 2 个测试（补全/保留完整端点/边界）
  - `OtlpTraceCollectorTest`：新增 2 个测试（service.name 默认值复现 + 设置后正确归属）
- 相关文件：
  - `agent-demo-observability/.../ObservabilityAutoConfiguration.java`
  - `agent-demo-bootstrap/src/main/resources/application.yml`
  - `agent-demo-observability/src/test/java/com/agentdemo/observability/ObservabilityAutoConfigurationTest.java`
  - `agent-demo-observability/src/test/java/com/agentdemo/observability/OtlpTraceCollectorTest.java`
- 回归风险/可能受影响模块：agent-demo-observability 导出链路；默认关路径无影响

## 单元测试
- 新增/更新测试：
  - `resolveOtlpTracesEndpoint_appendsSignalPath` / `resolveOtlpTracesEndpoint_keepsFullEndpointUnchanged`：验证端点补全逻辑（修复 404）
  - `exportedSpan_resource_serviceName_unknownServiceJava`：复现 project 归属缺失（修复前默认值）
  - `exportedSpan_resource_serviceName_isConfiguredProject`：验证归属修复
  - 端到端临时验证（已删除）：内嵌 HTTP server 确认真实 exporter 请求路径 = `/otel/v1/traces`
  - 回归：observability 43 + llm 148 全绿，BUILD SUCCESS

## 验证步骤（手动，逐步）
1) 用真实 Key 重启应用（`.\start.ps1 -LangSmithKey 'lsv2_xxxx'`），确认启动日志：`LangSmith 已启用，装配 OtlpTraceCollector: endpoint=https://api.smith.langchain.com/otel/v1/traces, project=agent-demo, ...`
2) 在对话页发起含工具调用的对话（如"现在几点"）
3) 观察应用日志：**不再出现** `Failed to export spans ... 404`（若 key/区域正确则无任何导出 WARN）
4) 等待 5~10 秒（导出间隔 5s + 网络）
5) 打开 smith.langchain.com → Tracing → 项目列表选择 **agent-demo**（不是 default）
6) 确认能看到本次对话 trace（agent.request 根 span + chat <model> LLM span + 工具 span）
7) 若仍 401/403：账号位于 EU 等非 US 区域时，需将 `langsmith.endpoint` 改为区域端点（如 `https://eu.api.smith.langchain.com/otel/v1/traces`）

## 验证结果
- 结果说明：自动化验证通过（端点解析单测 + service.name 归属测试 + 内嵌 server 端到端确认 exporter 请求路径正确 + 全量回归）；真实云侧验证依赖用户真实 Key
- 是否通过：自动化 是；云侧待用户按手动步骤确认

## 遗留问题/后续动作
- 若用户账号位于 EU 等非 US 区域：需把 `langsmith.endpoint` 改为区域端点（本次未自动探测区域）
- 建议观察 Task-14 联调清单：thread 聚合键（gen_ai.conversation.id / langsmith.thread.id）是否在 UI 生效
