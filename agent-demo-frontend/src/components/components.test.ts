// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import MessageItem from '@/components/MessageItem.vue';
import MessageInput from '@/components/MessageInput.vue';
import type { Message } from '@/types';

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
});
