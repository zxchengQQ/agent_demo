// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock SSE 调用避免真实请求
vi.mock('@/api/chat', () => ({
  streamChat: vi.fn().mockResolvedValue(undefined),
}));

// Mock LLM API 避免真实请求
vi.mock('@/api/llm', () => ({
  getPredefinedVendors: vi.fn(),
  getVendors: vi.fn(),
  getModels: vi.fn(),
  getConfigStatus: vi.fn(),
  addVendor: vi.fn(),
  updateVendor: vi.fn(),
  deleteVendor: vi.fn(),
  testConnection: vi.fn(),
  syncConfig: vi.fn(),
}));

import ChatWindow from '@/components/ChatWindow.vue';
import MessageInput from '@/components/MessageInput.vue';
import MessageList from '@/components/MessageList.vue';
import { useSessionStore } from '@/stores/session';
import { useLlmStore } from '@/stores/llm';
import { streamChat } from '@/api/chat';
import { getModels, getConfigStatus } from '@/api/llm';
import type { LlmModel, ConfigStatus } from '@/types';

/**
 * ChatWindow 模型选择集成测试（Task-22）
 * 验证：selectedModel 传递、模型变更写入会话级状态、发送携带 modelId、
 * lastUsedModelId 记录、删除模型回退、空状态引导。
 */

/** 测试用 chat 模型 */
const chatModelA: LlmModel = {
  id: 'model-a',
  vendorId: 'v1',
  vendorName: '火山引擎',
  modelName: 'doubao-seed-2.0-pro',
  displayName: '豆包Seed 2.0 Pro',
  type: 'chat',
  supportsVision: true,
};

const chatModelB: LlmModel = {
  id: 'model-b',
  vendorId: 'v2',
  vendorName: 'OpenAI',
  modelName: 'gpt-4o',
  displayName: 'GPT-4o',
  type: 'chat',
  supportsVision: true,
};

/** 测试用配置状态 */
const configStatus: ConfigStatus = {
  hasConfig: true,
  hasChatModel: true,
  hasEmbeddingModel: false,
  vendorCount: 2,
  chatModelCount: 2,
};

describe('ChatWindow 模型选择集成', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    localStorage.clear();
    vi.clearAllMocks();
    const llmStore = useLlmStore();
    llmStore.chatModels = [chatModelA, chatModelB];
    llmStore.configStatus = configStatus;
    vi.mocked(getModels).mockResolvedValue([chatModelA, chatModelB]);
    vi.mocked(getConfigStatus).mockResolvedValue(configStatus);
  });

  it('无会话级选择时 selectedModel 回退到第一个可用模型', async () => {
    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('selectedModel')).toBe('model-a');
  });

  it('会话级选择优先于第一个模型', async () => {
    const sessionStore = useSessionStore();
    sessionStore.createNewSession();
    sessionStore.setModel(sessionStore.currentSessionId, 'model-b');

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('selectedModel')).toBe('model-b');
  });

  it('ModelSelector 变更时调用 sessionStore.setModel 和 llmStore.setLastUsedModelId', async () => {
    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();
    const sessionStore = useSessionStore();
    const llmStore = useLlmStore();
    const sessionId = sessionStore.currentSessionId;

    await wrapper.findComponent(MessageInput).vm.$emit('update:selectedModel', 'model-b');

    expect(sessionStore.getModel(sessionId)).toBe('model-b');
    expect(llmStore.lastUsedModelId).toBe('model-b');
  });

  it('发送消息时 streamChat 第 6 参数（modelId）为当前选中模型', async () => {
    vi.mocked(streamChat).mockClear();
    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();

    await wrapper.find('textarea').setValue('测试消息');
    await wrapper.find('.btn-send').trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(streamChat).toHaveBeenCalled();
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    // unified-chat-mode：签名改为 (sessionId, message, knowledgeBases, modelId, ...)
    expect(callArgs[3]).toBe('model-a');
  });

  it('发送后记录 lastUsedModelId', async () => {
    vi.mocked(streamChat).mockClear();
    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();
    const llmStore = useLlmStore();
    // 先选择 model-b
    await wrapper.findComponent(MessageInput).vm.$emit('update:selectedModel', 'model-b');

    await wrapper.find('textarea').setValue('测试消息');
    await wrapper.find('.btn-send').trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 发送携带 model-b，并记录 lastUsedModelId
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    // unified-chat-mode：签名改为 (sessionId, message, knowledgeBases, modelId, ...)
    expect(callArgs[3]).toBe('model-b');
    expect(llmStore.lastUsedModelId).toBe('model-b');
  });

  it('选中模型被删除后回退到第一个可用模型', async () => {
    const sessionStore = useSessionStore();
    sessionStore.createNewSession();
    sessionStore.setModel(sessionStore.currentSessionId, 'model-b');

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();

    // 删除 model-b（chatModels 只剩 model-a）
    const llmStore = useLlmStore();
    llmStore.chatModels = [chatModelA];

    await flushPromises();
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('selectedModel')).toBe('model-a');
  });

  it('无 chat 模型时显示空状态引导并隐藏输入区', async () => {
    const llmStore = useLlmStore();
    llmStore.chatModels = [];
    // onMounted 会重新 loadChatModels，故 mock getModels 返回空以保持一致
    vi.mocked(getModels).mockResolvedValue([]);

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();

    expect(wrapper.findComponent(MessageInput).exists()).toBe(false);
    expect(wrapper.find('.config-guide').exists()).toBe(true);
  });

  it('点击空状态"去配置"按钮 emit navigate-to-config', async () => {
    const llmStore = useLlmStore();
    llmStore.chatModels = [];
    vi.mocked(getModels).mockResolvedValue([]);

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();

    await wrapper.find('.btn-go-config').trigger('click');
    expect(wrapper.emitted('navigate-to-config')).toBeTruthy();
  });

  it('未配置 LLM（hasConfig=false）时显示空状态引导', async () => {
    const llmStore = useLlmStore();
    const noConfig: ConfigStatus = { hasConfig: false, hasChatModel: false, hasEmbeddingModel: false, vendorCount: 0, chatModelCount: 0 };
    llmStore.configStatus = noConfig;
    // onMounted 会重新 loadConfigStatus，故 mock getConfigStatus 返回无配置状态
    vi.mocked(getConfigStatus).mockResolvedValue(noConfig);

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await flushPromises();

    expect(wrapper.find('.config-guide').exists()).toBe(true);
    expect(wrapper.findComponent(MessageInput).exists()).toBe(false);
  });
});

// ===== CR-001 新增：HITL 恢复同气泡续写（AC-N03/AC-N04）=====
// 验证标准来源：agent-human-interaction CR-001 Task-15 验证标准

describe('ChatWindow HITL 恢复同气泡续写（CR-001，AC-N03/AC-N04）', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    localStorage.clear();
    vi.clearAllMocks();
    const llmStore = useLlmStore();
    llmStore.chatModels = [chatModelA, chatModelB];
    llmStore.configStatus = configStatus;
    vi.mocked(getModels).mockResolvedValue([chatModelA, chatModelB]);
    vi.mocked(getConfigStatus).mockResolvedValue(configStatus);
  });

  /** 构造含 HITL 交互卡片的会话（最后一条为卡片助手消息） */
  function setupHitlCard(kind: 'permission' | 'askUser') {
    const sessionStore = useSessionStore();
    sessionStore.createNewSession();
    const sessionId = sessionStore.currentSessionId;
    sessionStore.addMessage(sessionId, {
      id: 'assistant-card',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'complete',
      reasoning: '',
    });
    if (kind === 'permission') {
      sessionStore.setToolConfirmData('assistant-card', {
        toolName: 'httpGet',
        toolDescription: '发送 HTTP GET 请求',
        arguments: '{"url":"https://example.com"}',
      });
    } else {
      sessionStore.setAskUserData('assistant-card', {
        type: 'confirm',
        question: '确认删除文件？',
        options: ['确认', '取消'],
        retryCount: 0,
      });
    }
    return { sessionStore, sessionId };
  }

  it('批准后复用卡片气泡续写，不新增助手气泡（AC-N04）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();
    const { sessionStore } = setupHitlCard('permission');
    const beforeCount = sessionStore.sessions[0].messages.length;

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await wrapper.findComponent(MessageList).vm.$emit('approve');
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 未新增任何气泡（批准不再产生新助手占位）
    expect(sessionStore.sessions[0].messages.length).toBe(beforeCount);
    // 复用气泡被置回流式态
    const cardMsg = sessionStore.sessions[0].messages.find((m) => m.id === 'assistant-card');
    expect(cardMsg!.status).toBe('incomplete');

    // 模拟后端续写流式回调
    const callbacks = vi.mocked(streamChat).mock.calls[0][5] as Parameters<typeof streamChat>[5];
    callbacks.onToken('已批准并执行，结果：');
    callbacks.onToken('成功');
    callbacks.onDone(10);

    expect(cardMsg!.content).toBe('已批准并执行，结果：成功');
    expect(cardMsg!.status).toBe('complete');
    // 卡片决策锁定态保留（approved=true）
    expect(cardMsg!.askUserData!.approved).toBe(true);
    // 请求携带 toolApproved=true
    expect(vi.mocked(streamChat).mock.calls[0][7]).toBe(true);
  });

  it('拒绝后复用卡片气泡续写，不新增助手气泡（AC-N04）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();
    const { sessionStore } = setupHitlCard('permission');
    const beforeCount = sessionStore.sessions[0].messages.length;

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await wrapper.findComponent(MessageList).vm.$emit('deny');
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(sessionStore.sessions[0].messages.length).toBe(beforeCount);
    const cardMsg = sessionStore.sessions[0].messages.find((m) => m.id === 'assistant-card');
    expect(cardMsg!.status).toBe('incomplete');

    const callbacks = vi.mocked(streamChat).mock.calls[0][5] as Parameters<typeof streamChat>[5];
    callbacks.onToken('已拒绝，换一种方案继续');
    callbacks.onDone(10);

    expect(cardMsg!.content).toBe('已拒绝，换一种方案继续');
    expect(cardMsg!.status).toBe('complete');
    expect(cardMsg!.askUserData!.approved).toBe(false);
    expect(vi.mocked(streamChat).mock.calls[0][7]).toBe(false);
  });

  it('AskUserCard 回复后不插用户气泡、复用卡片气泡续写（AC-N03）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();
    const { sessionStore } = setupHitlCard('askUser');
    const beforeCount = sessionStore.sessions[0].messages.length;

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await wrapper.findComponent(MessageList).vm.$emit('reply', '确认');
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 无用户气泡、无新助手气泡（原实现会新增 2 条）
    expect(sessionStore.sessions[0].messages.length).toBe(beforeCount);
    const cardMsg = sessionStore.sessions[0].messages.find((m) => m.id === 'assistant-card');
    // 卡片锁定态展示答案
    expect(cardMsg!.askUserData!.answer).toBe('确认');
    expect(cardMsg!.status).toBe('incomplete');

    const callbacks = vi.mocked(streamChat).mock.calls[0][5] as Parameters<typeof streamChat>[5];
    callbacks.onToken('好的，已按您的选择继续');
    callbacks.onDone(5);

    expect(cardMsg!.content).toBe('好的，已按您的选择继续');
    expect(cardMsg!.status).toBe('complete');
  });

  it('主输入框在等待态回复：同气泡续写、不插用户气泡（AC-N03）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();
    const { sessionStore } = setupHitlCard('askUser');
    const beforeCount = sessionStore.sessions[0].messages.length;

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    // 等待态下主输入框发送回复
    await wrapper.find('textarea').setValue('取消');
    await wrapper.find('.btn-send').trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(sessionStore.sessions[0].messages.length).toBe(beforeCount);
    const cardMsg = sessionStore.sessions[0].messages.find((m) => m.id === 'assistant-card');
    expect(cardMsg!.askUserData!.answer).toBe('取消');
    expect(cardMsg!.status).toBe('incomplete');
  });

  it('已回答卡片后再发普通消息：正常新建用户 + 助手气泡（回归，不误复用）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();
    const { sessionStore } = setupHitlCard('askUser');
    // 模拟已回答（卡片锁定态）
    sessionStore.setAskUserAnswer(sessionStore.currentSessionId, '确认');
    const beforeCount = sessionStore.sessions[0].messages.length;

    const wrapper = mount(ChatWindow, { global: { plugins: [pinia] } });
    await wrapper.find('textarea').setValue('好的谢谢');
    await wrapper.find('.btn-send').trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 新增用户气泡 + 新助手气泡（不误复用已答卡片）
    expect(sessionStore.sessions[0].messages.length).toBe(beforeCount + 2);
    const last = sessionStore.sessions[0].messages[sessionStore.sessions[0].messages.length - 1];
    expect(last.id).not.toBe('assistant-card');
    expect(last.role).toBe('assistant');
    // 已答卡片保持原状（未被 markStreaming）
    const cardMsg = sessionStore.sessions[0].messages.find((m) => m.id === 'assistant-card');
    expect(cardMsg!.status).toBe('complete');
  });
});
