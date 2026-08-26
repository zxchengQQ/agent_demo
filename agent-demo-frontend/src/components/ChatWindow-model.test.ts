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
