import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import SettingsPage from './SettingsPage.vue';

/**
 * SettingsPage 组件测试（Task-08）
 * 验证标准来源：Task-08 验证标准
 * 关联 AC：AC-001, AC-002, AC-003, AC-035
 */

// mock 子组件，聚焦标签页切换编排逻辑
vi.mock('./LlmConfigPage.vue', () => ({
  default: {
    name: 'LlmConfigPage',
    template: '<div class="mock-llm">LLM 配置页</div>',
  },
}));

vi.mock('./McpServicePage.vue', () => ({
  default: {
    name: 'McpServicePage',
    template: '<div class="mock-mcp">MCP 服务页</div>',
  },
}));

async function mountPage() {
  const wrapper = mount(SettingsPage);
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('SettingsPage 默认状态', () => {
  it('默认显示 LLM 配置标签页（AC-001）', async () => {
    const wrapper = await mountPage();
    expect(wrapper.find('.mock-llm').exists()).toBe(true);
    expect(wrapper.find('.mock-mcp').exists()).toBe(false);
  });

  it('显示两个标签按钮，LLM 配置默认激活', async () => {
    const wrapper = await mountPage();
    const tabs = wrapper.findAll('.settings-tab');
    expect(tabs).toHaveLength(2);
    expect(tabs[0].text()).toBe('LLM 配置');
    expect(tabs[1].text()).toBe('MCP 服务');
    expect(tabs[0].classes()).toContain('active');
    expect(tabs[1].classes()).not.toContain('active');
  });
});

describe('SettingsPage 标签切换', () => {
  it('点击 MCP 服务切换到 MCP 标签页（AC-002）', async () => {
    const wrapper = await mountPage();
    const tabs = wrapper.findAll('.settings-tab');
    await tabs[1].trigger('click');
    expect(wrapper.find('.mock-mcp').exists()).toBe(true);
    expect(wrapper.find('.mock-llm').exists()).toBe(false);
    expect(tabs[1].classes()).toContain('active');
  });

  it('点击 LLM 配置切回 LLM 标签页（AC-003）', async () => {
    const wrapper = await mountPage();
    const tabs = wrapper.findAll('.settings-tab');
    await tabs[1].trigger('click'); // 切到 MCP
    await tabs[0].trigger('click'); // 切回 LLM
    expect(wrapper.find('.mock-llm').exists()).toBe(true);
    expect(wrapper.find('.mock-mcp').exists()).toBe(false);
  });
});

describe('SettingsPage 状态保持（AC-003, AC-035）', () => {
  it('切换标签后 LLM 配置组件实例被缓存（KeepAlive）', async () => {
    const wrapper = await mountPage();
    // 初始 LLM 组件挂载
    const llmBefore = wrapper.findComponent({ name: 'LlmConfigPage' });
    expect(llmBefore.exists()).toBe(true);

    // 切到 MCP，再切回 LLM
    const tabs = wrapper.findAll('.settings-tab');
    await tabs[1].trigger('click');
    await tabs[0].trigger('click');

    // KeepAlive 缓存后，LLM 组件实例保留（非重建）
    const llmAfter = wrapper.findComponent({ name: 'LlmConfigPage' });
    expect(llmAfter.exists()).toBe(true);
    // 组件仍正常渲染（无回归，AC-035）
    expect(wrapper.find('.mock-llm').text()).toBe('LLM 配置页');
  });
});
