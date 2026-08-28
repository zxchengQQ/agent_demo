package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.registry.ModelFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前置规划判断组件（unified-chat-mode 新增）
 * <p>
 * 业务含义：统一对话模式下每条普通消息（无 /plan 前缀）先执行一次轻量规划判断，
 * 判定任务复杂度：返回非空子任务列表则进入拆解执行，返回空列表则直接回答。
 * 判断调用失败/异常/结果解析失败一律返回空列表降级直答（AC-E01，不中断对话）。
 * </p>
 * <p>
 * 逻辑自 TaskBreakdownStream 的 planTasks/parseTaskPlan/extractJsonArray 迁移
 * （技术方案 1.6.1：规划上移至独立组件，TaskBreakdownStream 收敛为纯执行引擎）。
 * </p>
 * <p>
 * 关联 AC：AC-T01（前置规划判断）、AC-N02/N03（判定结果路由）、AC-E01（失败降级）
 * </p>
 */
@Component
public class TaskPlanJudge {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanJudge.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ModelFactory modelFactory;
    private final AgentConfig agentConfig;
    private final PromptTemplateLoader promptTemplateLoader;

    public TaskPlanJudge(ModelFactory modelFactory, AgentConfig agentConfig,
                         PromptTemplateLoader promptTemplateLoader) {
        this.modelFactory = modelFactory;
        this.agentConfig = agentConfig;
        this.promptTemplateLoader = promptTemplateLoader;
    }

    /**
     * 前置规划判断（兼容签名：无历史注入）
     *
     * @param sessionId 会话 ID（仅用于日志追踪）
     * @param message   用户消息（已剥离 /plan 前缀后的有效内容）
     * @param modelId   模型 ID（null 使用默认模型）
     * @return 子任务列表（空列表表示无需拆解/判断失败降级直答）
     */
    public List<SubTask> judge(String sessionId, String message, String modelId) {
        return judge(sessionId, message, modelId, null);
    }

    /**
     * 前置规划判断（agent-context-engineering Task-07，AC-N03：携带最近会话历史）
     * <p>
     * 业务含义：统一对话模式下每条普通消息（无 /plan 前缀）先执行一次轻量规划判断，
     * 判定任务复杂度：返回非空子任务列表则进入拆解执行，返回空列表则直接回答。
     * 历史注入：调用方传入最近 N 条记忆消息（默认 6 条），保证指代类消息（"那它的物流呢"）
     * 可正确解析指代对象；历史为空/异常时降级为仅当前消息（现状行为）。
     * 判断调用失败/异常/结果解析失败一律返回空列表降级直答（AC-E01，不中断对话）。
     * </p>
     *
     * @param sessionId      会话 ID（仅用于日志追踪）
     * @param message        用户消息（已剥离 /plan 前缀后的有效内容）
     * @param modelId        模型 ID（null 使用默认模型）
     * @param recentHistory  最近会话历史消息（可为 null，注入于当前消息之前）
     * @return 子任务列表（空列表表示无需拆解/判断失败降级直答）
     */
    public List<SubTask> judge(String sessionId, String message, String modelId, List<ChatMessage> recentHistory) {
        try {
            // 业务含义：按 modelId 选择 ChatModel，null 时使用默认模型
            ChatModel chatModel = (modelId != null)
                    ? modelFactory.getChatModelByModelId(modelId)
                    : modelFactory.getDefaultChatModel();

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(
                    promptTemplateLoader.composeSystemPrompt(PromptTemplateLoader.SCENARIO_TASK_PLAN)));
            // AC-N03：历史注入（位于当前消息之前，供指代解析）
            if (recentHistory != null && !recentHistory.isEmpty()) {
                messages.addAll(recentHistory);
            }
            messages.add(UserMessage.from(message));

            ChatResponse response = chatModel.chat(messages);
            String responseText = response.aiMessage().text();

            log.info("规划判断响应: sessionId={}, historyMessages={}, responseLength={}", sessionId,
                    recentHistory != null ? recentHistory.size() : 0,
                    responseText != null ? responseText.length() : 0);

            return parseTaskPlan(responseText);
        } catch (Exception e) {
            // AC-E01：规划判断调用异常/超时 -> 降级直答，不中断对话
            log.warn("规划判断调用失败，降级直接回答: sessionId={}, error={}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析 LLM 返回的 JSON 为子任务列表
     * <p>
     * 业务含义：尝试从 LLM 响应中提取 JSON 数组并解析为 SubTask 列表。
     * 支持 markdown 代码块包裹的 JSON。解析失败返回空列表（AC-E01 降级）。
     * 子任务数量超过上限时截断。
     * </p>
     *
     * @param responseText LLM 返回的文本
     * @return 解析后的子任务列表（空列表表示无需拆解或解析失败）
     */
    List<SubTask> parseTaskPlan(String responseText) {
        if (responseText == null || responseText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String json = extractJsonArray(responseText);
        if (json == null) {
            return Collections.emptyList();
        }

        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return Collections.emptyList();
            }

            List<SubTask> tasks = new ArrayList<>();
            for (JsonNode node : array) {
                String title = node.path("title").asText("");
                if (title.isEmpty()) {
                    continue;
                }
                tasks.add(new SubTask(tasks.size() + 1, title));
            }

            // 子任务数量上限校验（沿用现有配置）
            int maxSubtasks = agentConfig.getTaskBreakdownMaxSubtasks();
            if (tasks.size() > maxSubtasks) {
                log.info("子任务数量 {} 超过上限 {}，截断", tasks.size(), maxSubtasks);
                tasks = new ArrayList<>(tasks.subList(0, maxSubtasks));
            }

            return tasks;
        } catch (Exception e) {
            log.warn("解析子任务 JSON 失败，降级直接回答: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 LLM 响应文本中提取 JSON 数组
     * <p>
     * 业务含义：LLM 可能返回纯 JSON 或带 markdown 标记的 JSON。
     * 先尝试直接解析，失败后用正则提取 [...] 部分。
     * </p>
     *
     * @param text LLM 响应文本
     * @return JSON 数组字符串，无法提取时返回 null
     */
    private String extractJsonArray(String text) {
        String trimmed = text.trim();

        // 尝试直接解析
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.isArray()) {
                return trimmed;
            }
        } catch (Exception ignored) {
            // 不是合法 JSON，继续尝试正则提取
        }

        // 正则提取 [...] 部分（处理 markdown 代码块包裹的情况）
        Pattern pattern = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }
}
