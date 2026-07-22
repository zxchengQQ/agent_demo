// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import MessageItem from '@/components/MessageItem.vue';
import MessageInput from '@/components/MessageInput.vue';
import ChatWindow from '@/components/ChatWindow.vue';
import type { Message } from '@/types';

// Mock streamChat 避免 ChatWindow 测试发起真实 API 调用
vi.mock('@/api/chat', () => ({
  streamChat: vi.fn().mockResolvedValue(undefined),
}));

/**
 * 组件渲染测试
 * 验证标准来源：T-11、T-13 验证标准
 * 关联 AC：AC-011、AC-012、AC-017、AC-020
 */
describe('MessageItem', () => {
  it('渲染用户消息（右对齐）', () => {
    const msg: Message = {
      id: '1',
      role: 'user',
      content: '你好',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.text()).toContain('你好');
    expect(wrapper.classes()).toContain('user');
  });

  it('渲染助手消息（左对齐 + 头像）', () => {
    const msg: Message = {
      id: '2',
      role: 'assistant',
      content: '回复内容',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.text()).toContain('回复内容');
    expect(wrapper.classes()).toContain('assistant');
    expect(wrapper.find('.avatar').text()).toBe('AI');
  });

  it('incomplete 状态显示"回复不完整"标记（AC-012）', () => {
    const msg: Message = {
      id: '3',
      role: 'assistant',
      content: '部分内容',
      createdAt: 0,
      status: 'incomplete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.text()).toContain('回复不完整');
  });

  it('error 状态显示错误标记', () => {
    const msg: Message = {
      id: '4',
      role: 'assistant',
      content: '',
      createdAt: 0,
      status: 'error',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.text()).toContain('发生错误');
  });

  // ===== CR-001 T-26 新增：推理折叠区块 + Markdown 渲染（AC-022、AC-023）=====

  it('助手消息 Markdown 渲染：# 标题 渲染为 h1（AC-023）', () => {
    const msg: Message = {
      id: 'm-md-1',
      role: 'assistant',
      content: '# 标题',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.html()).toContain('<h1>标题</h1>');
  });

  it('用户消息保持纯文本，不渲染 Markdown（AC-023）', () => {
    const msg: Message = {
      id: 'm-md-2',
      role: 'user',
      content: '# 标题',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.html()).not.toContain('<h1>');
    expect(wrapper.text()).toContain('# 标题');
  });

  it('助手消息 reasoning 非空时显示推理区块（AC-022）', () => {
    const msg: Message = {
      id: 'm-rs-1',
      role: 'assistant',
      content: '回复',
      reasoning: '推理过程',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.thinking-block').exists()).toBe(true);
    expect(wrapper.text()).toContain('推理过程');
  });

  it('流式中推理区块标题为"思考中..."且展开（AC-022）', () => {
    const msg: Message = {
      id: 'm-rs-2',
      role: 'assistant',
      content: '',
      reasoning: '推理中',
      createdAt: 0,
      status: 'incomplete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.thinking-title').text()).toBe('思考中...');
    expect(wrapper.find('.thinking-content').isVisible()).toBe(true);
  });

  it('完成后推理区块标题为"已思考"且默认折叠（AC-022）', () => {
    const msg: Message = {
      id: 'm-rs-3',
      role: 'assistant',
      content: '回复',
      reasoning: '推理完成',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.thinking-title').text()).toBe('已思考');
    expect(wrapper.find('.thinking-content').isVisible()).toBe(false);
  });

  it('完成后点击标题可切换展开/折叠（AC-022）', async () => {
    const msg: Message = {
      id: 'm-rs-4',
      role: 'assistant',
      content: '回复',
      reasoning: '推理完成',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 初始折叠（display: none）
    expect(wrapper.find('.thinking-content').attributes('style')).toContain('display: none');
    // 点击展开
    await wrapper.find('.thinking-header').trigger('click');
    expect(wrapper.find('.thinking-content').attributes('style')).toContain('display: block');
    // 再次点击折叠
    await wrapper.find('.thinking-header').trigger('click');
    expect(wrapper.find('.thinking-content').attributes('style')).toContain('display: none');
  });

  it('流式中点击标题不切换（保持展开，AC-022）', async () => {
    const msg: Message = {
      id: 'm-rs-5',
      role: 'assistant',
      content: '',
      reasoning: '推理中',
      createdAt: 0,
      status: 'incomplete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 流式中始终展开（display: block）
    expect(wrapper.find('.thinking-content').attributes('style')).toContain('display: block');
    // 流式中点击不折叠
    await wrapper.find('.thinking-header').trigger('click');
    expect(wrapper.find('.thinking-content').attributes('style')).toContain('display: block');
  });
});

describe('MessageInput', () => {
  it('渲染输入框和发送按钮', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    expect(wrapper.find('textarea').exists()).toBe(true);
    expect(wrapper.find('.btn-send').exists()).toBe(true);
  });

  it('流式时显示停止生成按钮，隐藏发送按钮（AC-011, AC-017）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: true } });
    expect(wrapper.find('.btn-stop').exists()).toBe(true);
    expect(wrapper.find('.btn-send').exists()).toBe(false);
    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined();
  });

  it('显示字符计数', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    expect(wrapper.find('.char-count').exists()).toBe(true);
    expect(wrapper.find('.char-count').text()).toContain('4000');
  });

  // ===== CR-001 T-27 新增：深度思考 toggle（AC-021）=====

  it('渲染"深度思考"toggle 按钮（AC-021）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableThinking: false } });
    expect(wrapper.find('.btn-thinking').exists()).toBe(true);
    expect(wrapper.find('.btn-thinking').text()).toContain('深度思考');
  });

  it('点击按钮触发 toggleThinking 事件（AC-021）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableThinking: false } });
    await wrapper.find('.btn-thinking').trigger('click');
    expect(wrapper.emitted('toggleThinking')).toBeTruthy();
    expect(wrapper.emitted('toggleThinking')).toHaveLength(1);
  });

  it('enableThinking=true 时按钮有 active 样式（AC-021）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableThinking: true } });
    expect(wrapper.find('.btn-thinking').classes()).toContain('active');
  });

  it('enableThinking=false 时按钮无 active 样式（AC-021）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableThinking: false } });
    expect(wrapper.find('.btn-thinking').classes()).not.toContain('active');
  });

  it('流式中按钮仍可点击切换（AC-021）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: true, enableThinking: false } });
    await wrapper.find('.btn-thinking').trigger('click');
    expect(wrapper.emitted('toggleThinking')).toBeTruthy();
  });
});

/**
 * ChatWindow 组件测试（CR-001 T-28）
 * 验证标准来源：T-28 验证标准
 * 关联 AC：AC-021、AC-022、AC-024
 */
describe('ChatWindow', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    localStorage.clear();
  });

  it('传递 enableThinking 状态给 MessageInput（AC-021）', () => {
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('enableThinking')).toBe(false);
  });

  it('点击思考 toggle 更新 enableThinking 状态（AC-021）', async () => {
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('enableThinking')).toBe(false);

    // 点击深度思考按钮
    await wrapper.find('.btn-thinking').trigger('click');

    expect(input.props('enableThinking')).toBe(true);
  });
});
