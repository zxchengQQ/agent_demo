// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock SSE 调用避免真实请求（捕获 callbacks 以触发 skill_activated 等回调）
let lastCallbacks: Parameters<typeof streamChat>[5] | undefined;
vi.mock('@/api/chat', () => ({
  streamChat: vi.fn((...args: unknown[]) => {
    lastCallbacks = args[5] as Parameters<typeof streamChat>[5];
    return Promise.resolve(undefined);
  }),
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

// Mock 技能 API 避免真实请求
vi.mock('@/api/skill', () => ({
  listSkills: vi.fn(),
}));

import ChatWindow from '@/components/ChatWindow.vue';
import { useSessionStore } from '@/stores/session';
import { useLlmStore } from '@/stores/llm';
import { useSkillStore } from '@/stores/skill';
import { streamChat } from '@/api/chat';
import { getModels, getConfigStatus } from '@/api/llm';
import { listSkills } from '@/api/skill';
import type { LlmModel, ConfigStatus, SkillInfo } from '@/types';

/**
 * ChatWindow /skill 前缀指令与可视化区块测试（CR-001 Task-37，AC-N07）
 * 验证：/skill 指令指定技能并剥离前缀发送、技能不存在时提示、可视化区块展示与移除。
 */

const chatModelA: LlmModel = {
  id: 'model-a',
  vendorId: 'v1',
  vendorName: '火山引擎',
  modelName: 'doubao-seed-2.0-pro',
  displayName: '豆包Seed 2.0 Pro',
  type: 'chat',
  supportsVision: true,
};

const configStatus: ConfigStatus = {
  hasConfig: true,
  hasChatModel: true,
  hasEmbeddingModel: false,
  vendorCount: 1,
  chatModelCount: 1,
};

const dataQuerySkill: SkillInfo = {
  id: 'data-query-assistant',
  name: '数据查询助手',
  description: '查询数据',
  instruction: '指令',
  resources: [],
  scripts: [],
  enabled: true,
  source: 'PRESET',
};

describe('ChatWindow /skill 指令', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    localStorage.clear();
    vi.clearAllMocks();
    const llmStore = useLlmStore();
    llmStore.chatModels = [chatModelA];
    llmStore.configStatus = configStatus;
    vi.mocked(getModels).mockResolvedValue([chatModelA]);
    vi.mocked(getConfigStatus).mockResolvedValue(configStatus);
    vi.mocked(listSkills).mockResolvedValue([dataQuerySkill]);
  });

  async function mountWindow() {
    const wrapper = mount(ChatWindow, { attachTo: document.body });
    await flushPromises();
    return wrapper;
  }

  it('应在 /skill 指令下指定技能并剥离前缀发送，且用户消息保留原始输入（CR-002）', async () => {
    const wrapper = await mountWindow();
    const store = useSessionStore();
    const skillStore = useSkillStore();
    await skillStore.loadSkills();
    await flushPromises();

    await wrapper.find('.textarea').setValue('/skill data-query-assistant 帮我查数据');
    await wrapper.find('.btn-send').trigger('click');
    await flushPromises();

    // streamChat 收到剥离前缀后的消息（LLM 内容不含命令前缀，AC-N07）
    const call = vi.mocked(streamChat).mock.calls[0];
    expect(call[1]).toBe('帮我查数据');
    // 会话级 skills 已指定
    expect(store.getSkills(store.currentSessionId)).toContain('data-query-assistant');
    // CR-002: 用户消息气泡保留原始输入（含技能名前缀，所见即所得）
    const userMsg = store.sessions[0].messages.find((m) => m.role === 'user');
    expect(userMsg?.content).toBe('/skill data-query-assistant 帮我查数据');
  });

  it('技能激活不再插入 AI 提示消息，激活状态仍记录（CR-002）', async () => {
    const wrapper = await mountWindow();
    const store = useSessionStore();
    const skillStore = useSkillStore();
    await skillStore.loadSkills();
    await flushPromises();

    // 发送 /skill 指令消息
    await wrapper.find('.textarea').setValue('/skill data-query-assistant 帮我查数据');
    await wrapper.find('.btn-send').trigger('click');
    await flushPromises();

    // 触发 manual 激活事件（选中技能后发送 → 后端下发 skill_activated）
    expect(lastCallbacks).toBeTruthy();
    lastCallbacks!.onSkillActivated?.({
      skillId: 'data-query-assistant',
      skillName: '数据查询助手',
      source: 'manual',
      boundToolIds: [],
    });
    await flushPromises();

    // CR-002: 不出现 AI"已加载技能"提示消息（反馈由用户消息保留原始输入承载）
    expect(store.sessions[0].messages.some((m) => m.content.includes('已加载技能'))).toBe(false);
    // 会话级激活状态仍记录（供排除逻辑）
    expect(store.activatedSkillsBySession[store.currentSessionId]).toContain('data-query-assistant');
    // 页面激活元素不存在
    expect(wrapper.find('.activated-skills-bar').exists()).toBe(false);
    expect(wrapper.find('.manual-skills-bar').exists()).toBe(false);
  });

  it('技能不存在时提示且保持自动模式', async () => {
    const wrapper = await mountWindow();
    const store = useSessionStore();

    await wrapper.find('.textarea').setValue('/skill 幽灵技能 查一下');
    await wrapper.find('.btn-send').trigger('click');
    await flushPromises();

    // 不设置 skills（保持自动模式）
    expect(store.getSkills(store.currentSessionId)).toEqual([]);
    // 显示提示
    expect(wrapper.find('.cmd-error').exists()).toBe(true);
    expect(wrapper.find('.cmd-error').text()).toContain('幽灵技能');
    // 消息仍以原样发送（不含前缀剥离）；CR-002：用户消息气泡保留原始输入
    const call = vi.mocked(streamChat).mock.calls[0];
    expect(call[1]).toContain('查一下');
    const userMsg = store.sessions[0].messages.find((m) => m.role === 'user');
    expect(userMsg?.content).toContain('/skill 幽灵技能');
  });
});
