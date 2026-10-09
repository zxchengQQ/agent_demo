<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useSessionStore } from '@/stores/session';
import { useRagStore } from '@/stores/rag';
import { useLlmStore } from '@/stores/llm';
import { useSkillStore } from '@/stores/skill';
import { streamChat } from '@/api/chat';
import type { Message } from '@/types';
import MessageList from './MessageList.vue';
import MessageInput from './MessageInput.vue';
import SkillSelector from './SkillSelector.vue';

const store = useSessionStore();
const ragStore = useRagStore();
const llmStore = useLlmStore();
const skillStore = useSkillStore();
const isStreaming = ref(false);

/** 跳转到 LLM 配置视图（Task-22 空状态引导） */
const emit = defineEmits<{
  'navigate-to-config': [];
}>();

// 初始化：加载 chat 模型列表、配置状态、恢复上次使用的模型（Task-22）
onMounted(() => {
  llmStore.loadChatModels();
  llmStore.loadConfigStatus();
  llmStore.initLastUsedModelId();
  // agent-skill：加载技能列表（选择器与激活徽标数据源）
  skillStore.loadSkills().catch(() => {
    // 技能加载失败静默处理（对话不受影响）
  });
});

/** 是否具备可用的 chat 模型（Task-22 空状态判断） */
const hasChatModels = computed(
  () => !!(llmStore.configStatus?.hasConfig) && llmStore.chatModels.length > 0,
);

/**
 * 当前会话选中的模型 ID（Task-22）
 * 业务含义：优先会话级选择；会话未选则回退到上次使用；仍无则回退到第一个可用模型。
 * 当选中的模型被删除时（不再存在于 chatModels），自动回退到第一个可用模型。
 */
const selectedModel = computed(() => {
  const sessionId = store.currentSessionId;
  const sessionModel = store.getModel(sessionId);
  if (sessionModel && llmStore.chatModels.some((m) => m.id === sessionModel)) {
    return sessionModel;
  }
  if (llmStore.lastUsedModelId && llmStore.chatModels.some((m) => m.id === llmStore.lastUsedModelId)) {
    return llmStore.lastUsedModelId;
  }
  return llmStore.chatModels[0]?.id ?? '';
});

/** 模型选择变更处理（Task-22）：更新会话级状态 + 持久化 lastUsedModelId */
function handleModelChange(modelId: string) {
  if (!modelId) return;
  store.setModel(store.currentSessionId, modelId);
  llmStore.setLastUsedModelId(modelId);
}

/**
 * 当前会话的知识库选择（Task-09，AC-012/AC-014）
 * 业务含义：从 session store 读取，按会话隔离。空数组表示"自动"模式（Agent 自主检索）。
 */
const selectedKnowledgeBases = computed(() =>
  store.getKnowledgeBases(store.currentSessionId),
);

/**
 * 知识库选择变更处理（Task-09，AC-014）
 * 业务含义：用户通过 KnowledgeBaseSelector 切换选择时，更新 session store 中的会话级状态。
 */
function handleKnowledgeBasesChange(bases: string[]) {
  store.setKnowledgeBases(store.currentSessionId, bases);
}

/**
 * 当前会话的工具选择（工具按需加载）
 * 业务含义：从 session store 读取用户选中的可选工具 ID 列表。
 * 不包含默认工具（默认工具始终加载）。空数组表示仅默认工具。
 */
const selectedTools = computed(() => store.getTools(store.currentSessionId));

/**
 * 工具选择变更处理
 * 业务含义：用户通过 ToolSelector 切换选择时，更新 session store 中的会话级状态。
 */
function handleToolsChange(tools: string[]) {
  store.setTools(store.currentSessionId, tools);
}

/**
 * 当前会话的技能选择（agent-skill Task-20，AC-N03 手动指定入口）
 * 业务含义：从 session store 读取用户手动指定的技能 id 列表。
 * 空数组表示"自动"模式（Agent 自主匹配激活）。
 */
const selectedSkills = computed(() => store.getSkills(store.currentSessionId));

/**
 * 技能选择变更处理
 * 业务含义：用户通过 SkillSelector 切换选择时，更新 session store 中的会话级状态。
 */
function handleSkillsChange(skills: string[]) {
  store.setSkills(store.currentSessionId, skills);
}

/**
 * 当前会话的技能排除集合（agent-skill）
 * 业务含义：从 session store 读取用户排除的技能 id 列表（激活徽标"×"操作）。
 */
const selectedExcludedSkills = computed(() => store.getExcludedSkills(store.currentSessionId));

// ===== CR-001: /skill 前缀指令（AC-N07，与 /plan 同构）=====

/** /skill 指令错误提示（可视化区块展示） */
const skillCommandError = ref('');

/**
 * 解析 /skill 前缀指令
 * 业务含义：形如 "/skill 技能名 消息内容"，返回技能名与剥离前缀后的剩余消息；非 /skill 指令返回 null。
 */
function parseSkillCommand(message: string): { skillName: string; rest: string } | null {
  const m = message.trim().match(/^\/skill\s+(\S+)\s*([\s\S]*)$/);
  if (!m) return null;
  return { skillName: m[1], rest: m[2] ?? '' };
}

/**
 * 应用 /skill 指令：匹配技能（id 或名称精确），设为会话手动指定；不存在时提示并保持自动模式（AC-N07）
 */
function applySkillCommand(skillName: string): void {
  const skill = skillStore.skills.find((s) => s.id === skillName || s.name === skillName);
  if (!skill) {
    skillCommandError.value = `技能不存在: ${skillName}，请在技能选择器或设置页确认`;
    return;
  }
  skillCommandError.value = '';
  store.setSkills(store.currentSessionId, [skill.id]);
}

let abortController: AbortController | null = null;

/** 当前会话的消息列表 */
const currentMessages = computed<Message[]>(() => {
  const session = store.sessions.find((s) => s.sessionId === store.currentSessionId);
  return session?.messages ?? [];
});

/** 生成消息 ID */
function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

/**
 * 发送消息（AC-002: 流式输出）
 * 业务含义：用户发送消息 -> 创建助手占位（或复用 HITL 卡片气泡）-> 流式接收 -> 完成/中断/错误
 *
 * @param message 用户消息
 * @param toolApproved 工具权限确认结果（true=批准/继续执行，false=拒绝/换方案；undefined=普通消息，Task-17）
 * @param silent 静默模式（HITL 交互专用）：请求正常发出但不在对话中产生用户消息气泡——
 *               决策/答案已由交互卡片锁定态可视化，重复气泡属冗余信息（交互优化）。
 *               CR-001 扩展：批准/拒绝（toolApproved 已定义）与 askUser 卡片回复（silent=true）
 *               均走 HITL 恢复路径，续写复用卡片气泡（AC-N03/AC-N04）。
 */
async function sendMessage(message: string, toolApproved?: boolean, silent?: boolean) {
  // AC-014: 空消息拦截
  if (!message.trim()) return;
  // Task-22: 无可用 chat 模型时禁止发送（含未配置）
  if (!hasChatModels.value) return;
  // Task-22: 获取当前选中的模型 ID（可能为空，后端使用默认模型）
  const modelId = selectedModel.value;

  // 确保有当前会话
  if (!store.currentSessionId) {
    store.createNewSession();
  }
  const sessionId = store.currentSessionId;

  // CR-001/CR-002: /skill 前缀指令——指定技能后剥离前缀发送；仅指令无内容时不发送（AC-N07）。
  // CR-002: 用户消息气泡保留原始输入（含技能名，所见即所得），LLM 内容剥离前缀。
  const userDisplay = message;
  const skillCmd = parseSkillCommand(message);
  if (skillCmd) {
    applySkillCommand(skillCmd.skillName);
    if (!skillCmd.rest.trim()) return;
    message = skillCmd.rest.trim();
  }

  // CR-001（agent-human-interaction）：HITL 恢复同气泡续写检测。
  // 场景：① 批准/拒绝（toolApproved 已定义）；② askUser 卡片回复（silent=true）；
  // ③ 主输入框在等待态下的回复（isWaitingForUserInput 为真，最后一条为未回答卡片）。
  // 上述路径复用最后一条含交互卡片的助手气泡作为流式目标，不再新建助手气泡，
  // 让续写展示为对同一对话问题的延续（AC-N03/AC-N04）。
  const sessionBefore = store.sessions.find((s) => s.sessionId === sessionId);
  const lastMsg =
    sessionBefore && sessionBefore.messages.length > 0
      ? sessionBefore.messages[sessionBefore.messages.length - 1]
      : null;
  const isHitlResume =
    toolApproved !== undefined || silent === true || store.isWaitingForUserInput;

  // unified-chat-mode（决策 7 双通道收敛，技术方案 11.1 风险 4 对策）：
  // 等待态下用户通过主输入框发送的任意消息，先记录为对 askUser 卡片的回答
  // （卡片进入锁定态可回看），再作为普通消息/恢复消息发送。
  // 注：kind=permission 卡片不走此路径（isWaitingForUserInput 已排除），由批准/拒绝按钮决策。
  if (store.isWaitingForUserInput) {
    store.setAskUserAnswer(sessionId, message);
  }

  // 乐观添加用户消息（silent 模式跳过：HITL 交互不产生对话气泡，卡片锁定态已展示）
  // CR-002: 气泡内容=原始输入（userDisplay，/skill 指令时含技能名前缀）
  if (!silent && !isHitlResume) {
    store.addMessage(sessionId, {
      id: generateId(),
      role: 'user',
      content: userDisplay,
      createdAt: Date.now(),
      status: 'complete',
      reasoning: '',
    });
  }

  // 流式目标消息 id（CR-001）：
  // - HITL 恢复：复用最后一条含交互卡片的助手气泡，并置回流式态（不新建气泡）
  // - 普通消息：新建助手消息占位（流式追加内容）
  const hitlCardMsg =
    isHitlResume && lastMsg && lastMsg.role === 'assistant' && lastMsg.askUserData
      ? lastMsg
      : null;
  let assistantMsgId: string;
  if (hitlCardMsg) {
    assistantMsgId = hitlCardMsg.id;
    store.markStreaming(assistantMsgId);
  } else {
    assistantMsgId = generateId();
    store.addMessage(sessionId, {
      id: assistantMsgId,
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
      reasoning: '',
    });
  }

  // 流式调用
  isStreaming.value = true;
  abortController = new AbortController();

  try {
    await streamChat(
      sessionId,
      message,
      selectedKnowledgeBases.value,
      modelId,
      selectedTools.value,
      {
        // AC-010: 透明续聊 - 后端返回新 sessionId 时更新关联
        onSession: (newSessionId: string) => {
          store.updateSessionId(sessionId, newSessionId);
        },
        // AC-020: 逐字追加
        onToken: (token: string) => {
          store.appendContent(assistantMsgId, token);
        },
        // CR-001: 推理过程流式展示（AC-022），追加到助手消息 reasoning 字段
        onReasoning: (reasoning: string) => {
          store.appendReasoning(assistantMsgId, reasoning);
        },
        // ReAct: 思考内容，追加到对应 iteration 的 reactStep
        onThought: (thought: string, iteration: number) => {
          store.appendThought(assistantMsgId, thought, iteration);
        },
        // ReAct: 工具调用，追加工具调用信息
        onAction: (toolName: string, args: string, iteration: number) => {
          store.appendAction(assistantMsgId, toolName, args, iteration);
        },
        // ReAct: 工具结果，追加到对应工具调用
        onObservation: (result: string, iteration: number) => {
          store.appendObservation(assistantMsgId, result, iteration);
        },
        // ReAct: 最终答案标记。
        // BUG 修复：正式内容（最终答案）已通过 onToken 流式追加到 content，
        // 不再调用 moveThoughtToContent（避免 content 重复追加）。
        onFinalAnswer: (_iteration: number) => {
          // 无操作：最终答案已由 token 事件流式显示
        },
        // Task-17: Token 消耗统计，累加到当前会话
        onUsage: (usage) => {
          store.addTokenUsage(store.currentSessionId, usage);
        },
        // 流式完成
        onDone: () => {
          store.markComplete(assistantMsgId);
          store.touchSession(store.currentSessionId);
          // AC-006: 首条消息生成标题
          const session = store.sessions.find(
            (s) => s.sessionId === store.currentSessionId,
          );
          if (session && session.messages.length === 2) {
            store.generateTitle(store.currentSessionId, message);
          }
        },
        // AC-012/AC-013: 错误处理
        onError: (msg: string) => {
          store.markError(assistantMsgId, msg);
        },

        // ===== CR-002 任务拆解回调（AC-001, AC-003, AC-005, AC-006）=====

        // 任务规划完成，初始化子任务列表（AC-001）
        onTaskPlan: (tasks) => {
          store.initSubTasks(assistantMsgId, tasks);
        },
        // 子任务开始执行，状态变为 in-progress（AC-003）
        onTaskStart: (index, _title) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'in-progress');
        },
        // 子任务执行内容片段（AC-005）
        onTaskToken: (index, content) => {
          store.appendSubTaskContent(assistantMsgId, index, content);
        },
        // 子任务推理片段（AC-011）
        onTaskReasoning: (index, content) => {
          store.appendSubTaskReasoning(assistantMsgId, index, content);
        },
        // 子任务 ReAct 思考（AC-005）
        onTaskThought: (index, content, iteration) => {
          store.appendSubTaskThought(assistantMsgId, index, content, iteration);
        },
        // 子任务工具调用（AC-005）
        onTaskAction: (index, toolName, args, iteration) => {
          store.appendSubTaskAction(assistantMsgId, index, toolName, args, iteration);
        },
        // 子任务工具结果（AC-005）
        onTaskObservation: (index, result, iteration) => {
          store.appendSubTaskObservation(assistantMsgId, index, result, iteration);
        },
        // 子任务完成，状态变为 completed（AC-003）
        onTaskComplete: (index) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'completed');
        },
        // 子任务失败，状态变为 failed（AC-006）
        onTaskFailed: (index, error) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'failed', error);
        },
        // 子任务取消，状态变为 cancelled（AC-006, AC-007）
        onTaskCancelled: (index) => {
          store.updateSubTaskStatus(assistantMsgId, index, 'cancelled');
        },

        // CR-002: 知识库来源信息累积写入消息（AC-043）
        onSources: (sources) => {
          store.addKnowledgeSources(assistantMsgId, sources);
        },

        // Task-10: HITL 人机交互请求，将交互数据写入助手消息
        onAskUser: (data) => {
          store.setAskUserData(assistantMsgId, data);
        },

        // Task-17: 工具权限确认请求（tool_confirm 事件）
        // 业务含义：ask 级工具被 Agent 调用时，将确认卡片四要素
        // （工具名/用途描述/参数摘要）写入助手消息（kind=permission），
        // 前端据此渲染 ConfirmCard 等待用户批准/拒绝；事件后后端发送 done 并关闭流（BUG 修复），
        // 用户决策后 sendMessage 携带 toolApproved 发起新流恢复。
        onToolConfirm: (data) => {
          store.setToolConfirmData(assistantMsgId, data);
        },

        // agent-skill: 技能激活事件（skill_activated）
        // CR-002: 不再插入 AI"已加载技能"提示消息（技能反馈由用户消息保留原始输入承载），
        // 仅记录激活状态（供会话级状态与排除逻辑使用）。
        onSkillActivated: (data) => {
          store.addActivatedSkill(assistantMsgId, data);
          store.addActivatedSkillToSession(store.currentSessionId, data.skillId);
        },
      },
      abortController.signal,
      toolApproved,
      selectedSkills.value,
      selectedExcludedSkills.value,
    );
  } finally {
    isStreaming.value = false;
    abortController = null;
  }
  // Task-22: 发送后记录上次使用的模型（持久化到 localStorage，刷新后恢复）
  if (modelId) {
    llmStore.setLastUsedModelId(modelId);
  }
}

/**
 * 停止生成（AC-011）
 * 业务含义：中断流式，已接收内容保留并标记 incomplete
 */
function stopGeneration() {
  abortController?.abort();
  isStreaming.value = false;
}

/**
 * 处理 AskUserCard 回复事件（unified-chat-mode Task-18）
 * 业务含义：用户在统一交互卡片中回复（选项值或输入文本）后，先记录回答
 * （setAskUserAnswer 写入 answer + 持久化，卡片锁定可回看），再作为用户消息发送
 * （后端 hasPending 检测后走恢复路径）。
 * CR-001：askUser 回复改为 silent——答案由卡片锁定态展示，不再产生用户气泡；
 * 由 sendMessage 检测 HITL 恢复并复用卡片气泡续写（AC-N03）。
 */
function handleAskUserReply(value: string) {
  store.setAskUserAnswer(store.currentSessionId, value);
  sendMessage(value, undefined, true);
}

/**
 * 处理工具权限确认：批准（Task-17，AC-N03）
 * 业务含义：用户点击 ConfirmCard 的"批准"按钮，记录决策（approved=true，卡片锁定可回看），
 * 然后携带 toolApproved=true 重新发起流式请求，后端经 resumeToolConfirm 恢复执行被拦截的工具。
 */
function handleApprove() {
  if (!store.currentSessionId) return;
  store.setToolConfirmApproved(store.currentSessionId, true);
  // 业务含义：决策文案不可为空——空串会被 sendMessage 的空消息拦截
  // 与后端 @NotBlank 校验双重拒绝，导致确认流程卡死（BUG 修复：请求根本发不出去）
  // silent=true：决策已由卡片锁定态展示，不再产生冗余对话气泡（交互优化）
  sendMessage('已批准使用工具', true, true);
}

/**
 * 处理工具权限确认：拒绝（Task-17，AC-S02）
 * 业务含义：用户点击 ConfirmCard 的"拒绝"按钮，记录决策（approved=false，卡片锁定可回看），
 * 然后携带 toolApproved=false 重新发起流式请求，后端将拒绝结果注入 Observation，
 * 引导 LLM 换方案继续（不执行该工具）。
 */
function handleDeny() {
  if (!store.currentSessionId) return;
  store.setToolConfirmApproved(store.currentSessionId, false);
  // 业务含义：同 handleApprove——决策文案不可为空，否则请求被双重拦截导致流程卡死
  // silent=true：决策已由卡片锁定态展示，不再产生冗余对话气泡（交互优化）
  sendMessage('已拒绝使用工具', false, true);
}
</script>

<template>
  <div class="chat-window">
    <!-- 消息列表区 -->
    <MessageList
      :messages="currentMessages"
      @reply="handleAskUserReply"
      @approve="handleApprove"
      @deny="handleDeny"
    />

    <!-- 无可用 chat 模型/未配置 LLM 空状态引导（Task-22）：禁用发送 -->
    <div v-if="!hasChatModels" class="config-guide">
      <p class="guide-text">请先配置 LLM 模型</p>
      <button class="btn-go-config" @click="emit('navigate-to-config')">去配置</button>
    </div>

    <!-- 输入区（Task-22 集成模型选择） -->
    <MessageInput
      v-else
      :is-streaming="isStreaming"
      :is-waiting-for-user-input="store.isWaitingForUserInput"
      :knowledge-bases="ragStore.knowledgeBases"
      :selected-knowledge-bases="selectedKnowledgeBases"
      :selected-tools="selectedTools"
      :selected-model="selectedModel"
      :models="llmStore.chatModels"
      :has-config="!!llmStore.configStatus?.hasConfig"
      @send="sendMessage"
      @stop="stopGeneration"
      @update:selected-knowledge-bases="handleKnowledgeBasesChange"
      @update:selected-tools="handleToolsChange"
      @update:selected-model="handleModelChange"
      @navigate-to-config="emit('navigate-to-config')"
    />

    <!-- 技能选择器（agent-skill Task-20，AC-N03 手动指定入口）：空=自动模式 -->
    <div v-if="hasChatModels && skillStore.skills.length > 0" class="skill-selector-bar">
      <SkillSelector
        :model-value="selectedSkills"
        :skills="skillStore.skills.filter((s) => s.enabled)"
        :disabled="isStreaming"
        @update:model-value="handleSkillsChange"
      />
    </div>

    <!-- CR-001: /skill 指令错误提示（技能不存在，AC-N07：提示并保持自动模式） -->
    <div v-if="skillCommandError" class="cmd-error">{{ skillCommandError }}</div>
  </div>
</template>

<style scoped>
.chat-window {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

/* 技能选择器栏（agent-skill）：输入区上方，右侧对齐 */
.skill-selector-bar {
  display: flex;
  justify-content: flex-end;
  padding: 4px var(--spacing-md) 0;
}

.cmd-error {
  font-size: 12px;
  color: #ff6b6b;
}

/* 未配置模型空状态引导（Task-22） */
.config-guide {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border-top: 1px solid var(--border);
  background: var(--bg-sidebar);
}

.guide-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.btn-go-config {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--accent-dim);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent);
  font-family: var(--font-display);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-go-config:hover {
  background: var(--accent-dim);
}
</style>
