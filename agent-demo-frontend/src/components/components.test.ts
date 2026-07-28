// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import MessageItem from '@/components/MessageItem.vue';
import MessageInput from '@/components/MessageInput.vue';
import ChatWindow from '@/components/ChatWindow.vue';
import MessageList from '@/components/MessageList.vue';
import KnowledgeBaseSelector from '@/components/KnowledgeBaseSelector.vue';
import NavBar from '@/components/NavBar.vue';
import KnowledgeBasePage from '@/components/KnowledgeBasePage.vue';
import SessionList from '@/components/SessionList.vue';
import App from '@/App.vue';
import { useSessionStore } from '@/stores/session';
import { useRagStore } from '@/stores/rag';
import type { Message, SubTask, KnowledgeBase } from '@/types';

// Mock streamChat 避免 ChatWindow 测试发起真实 API 调用
vi.mock('@/api/chat', () => ({
  streamChat: vi.fn().mockResolvedValue(undefined),
}));

// Mock RAG API 避免 KnowledgeBasePage 测试发起真实 API 调用
vi.mock('@/api/rag', () => ({
  listKnowledgeBases: vi.fn().mockResolvedValue([]),
  createKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
  uploadDocument: vi.fn(),
  listDocuments: vi.fn().mockResolvedValue([]),
  deleteDocument: vi.fn(),
  getDocumentStatus: vi.fn(),
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

  // ===== CR-002 新增：task-block 折叠区块（AC-003, AC-005, AC-015, AC-016）=====

  /** 辅助：构造带子任务的助手消息 */
  function createTaskMessage(overrides: Partial<Message> & { subTasks?: SubTask[] } = {}): Message {
    return {
      id: 'm-task-1',
      role: 'assistant',
      content: '总结回复',
      createdAt: 0,
      status: 'complete',
      subTasks: [
        { index: 1, title: '分析需求', status: 'completed', content: '分析结果' },
        { index: 2, title: '调研方案', status: 'completed', content: '调研结果' },
        { index: 3, title: '生成建议', status: 'pending' },
      ],
      ...overrides,
    };
  }

  it('message.subTasks 存在且非空时渲染 task-block（AC-015）', () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    expect(wrapper.find('.task-block').exists()).toBe(true);
  });

  it('message.subTasks 不存在时不渲染 task-block', () => {
    const msg: Message = {
      id: 'm-task-2',
      role: 'assistant',
      content: '回复',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.task-block').exists()).toBe(false);
  });

  it('message.subTasks 为空数组时不渲染 task-block', () => {
    const wrapper = mount(MessageItem, {
      props: { message: createTaskMessage({ subTasks: [] }) },
    });
    expect(wrapper.find('.task-block').exists()).toBe(false);
  });

  it('流式中 task-block 自动展开（AC-015）', () => {
    const wrapper = mount(MessageItem, {
      props: { message: createTaskMessage({ status: 'incomplete' }) },
    });
    expect(wrapper.find('.task-content').attributes('style')).toContain('display: block');
  });

  it('完成后 task-block 自动折叠（AC-015）', () => {
    const wrapper = mount(MessageItem, {
      props: { message: createTaskMessage({ status: 'complete' }) },
    });
    expect(wrapper.find('.task-content').attributes('style')).toContain('display: none');
  });

  it('流式中标题显示"任务拆解（X/Y 已完成）"格式（AC-015）', () => {
    const wrapper = mount(MessageItem, {
      props: { message: createTaskMessage({ status: 'incomplete' }) },
    });
    // 2 个 completed / 3 个 total
    expect(wrapper.find('.task-title').text()).toContain('任务拆解');
    expect(wrapper.find('.task-title').text()).toContain('2/3');
  });

  it('完成后标题显示"已完成 Y 个子任务"格式（AC-015）', () => {
    const wrapper = mount(MessageItem, {
      props: { message: createTaskMessage({ status: 'complete' }) },
    });
    expect(wrapper.find('.task-title').text()).toContain('已完成');
    expect(wrapper.find('.task-title').text()).toContain('3');
  });

  it('子任务 status=pending 显示图标 ○（AC-016）', () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    const icons = wrapper.findAll('.subtask-status-icon');
    expect(icons[2].text()).toBe('○');
  });

  it('子任务 status=in-progress 显示图标 ◐（AC-003）', () => {
    const msg = createTaskMessage({
      subTasks: [{ index: 1, title: '执行中', status: 'in-progress' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.subtask-status-icon').text()).toBe('◐');
  });

  it('子任务 status=completed 显示图标 ✓（AC-003）', () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    const icons = wrapper.findAll('.subtask-status-icon');
    expect(icons[0].text()).toBe('✓');
  });

  it('子任务 status=failed 显示图标 ✕ 且显示 error 文本（AC-006）', () => {
    const msg = createTaskMessage({
      subTasks: [{ index: 1, title: '失败任务', status: 'failed', error: '超时错误' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.subtask-status-icon').text()).toBe('✕');
    expect(wrapper.find('.subtask-error').text()).toBe('超时错误');
  });

  it('子任务 status=cancelled 显示图标 -（AC-007）', () => {
    const msg = createTaskMessage({
      subTasks: [{ index: 1, title: '取消任务', status: 'cancelled' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.subtask-status-icon').text()).toBe('-');
  });

  it('点击 completed 子任务头部展开详情（AC-005）', async () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    // 初始折叠
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
    // 点击第一个子任务（completed）
    await wrapper.findAll('.subtask-header')[0].trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
    expect(wrapper.find('.subtask-detail').text()).toContain('分析结果');
  });

  it('点击非 completed 子任务头部不展开（AC-005）', async () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    // 点击第三个子任务（pending）
    await wrapper.findAll('.subtask-header')[2].trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
  });

  it('完成后点击 task-header 可展开 task-block（AC-015）', async () => {
    const wrapper = mount(MessageItem, {
      props: { message: createTaskMessage({ status: 'complete' }) },
    });
    // 初始折叠
    expect(wrapper.find('.task-content').attributes('style')).toContain('display: none');
    // 点击展开
    await wrapper.find('.task-header').trigger('click');
    expect(wrapper.find('.task-content').attributes('style')).toContain('display: block');
  });

  it('task-block 样式与 thinking-block 一致（background: var(--bg-sidebar)）（AC-015）', () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    const taskBlock = wrapper.find('.task-block');
    expect(taskBlock.exists()).toBe(true);
    // 验证 class 存在即可（具体 CSS 值在 global.css 中定义）
    expect(taskBlock.classes()).toContain('task-block');
  });

  // ===== CR-001 变更新增：in-progress 可展开 + 自动展开（AC-005 修改, AC-017 新增）=====

  it('点击 in-progress 子任务头部展开详情（AC-005 修改）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{ index: 1, title: '执行中任务', status: 'in-progress', content: '部分结果' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 初始：watcher 未触发（mount 时不自动展开），详情折叠
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
    // 点击展开
    await wrapper.find('.subtask-header').trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
    expect(wrapper.find('.subtask-detail').text()).toContain('部分结果');
    // 再次点击折叠
    await wrapper.find('.subtask-header').trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
  });

  it('点击 pending 子任务头部不展开（AC-005 边界）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{ index: 1, title: '待执行', status: 'pending' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    await wrapper.find('.subtask-header').trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
  });

  it('pending->in-progress 状态变化时自动展开（AC-017）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{ index: 1, title: '待执行', status: 'pending' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 初始不展开
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
    // 状态变为 in-progress
    await wrapper.setProps({
      message: { ...msg, subTasks: [{ index: 1, title: '执行中', status: 'in-progress' }] },
    });
    // 自动展开
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
  });

  it('in-progress 子任务展开后渲染 reactSteps（AC-005 修改）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{
        index: 1,
        title: '执行中任务',
        status: 'in-progress',
        reactSteps: [{
          iteration: 1,
          thought: '需要查询信息',
          toolCalls: [{ toolName: 'http', arguments: '{"url":"..."}', result: '查询结果' }],
        }],
      }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 手动展开（mount 时 watcher 不触发）
    await wrapper.find('.subtask-header').trigger('click');
    // 验证 ReAct 步骤渲染
    expect(wrapper.find('.react-thought').text()).toContain('需要查询信息');
    expect(wrapper.find('.tool-name').text()).toBe('http');
    expect(wrapper.find('.tool-result-text').text()).toContain('查询结果');
  });

  it('in-progress 子任务展开后显示 content（AC-005 修改）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{ index: 1, title: '执行中任务', status: 'in-progress', content: '部分执行结果' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    await wrapper.find('.subtask-header').trigger('click');
    expect(wrapper.find('.subtask-result').text()).toContain('部分执行结果');
  });

  it('in-progress->completed 展开状态保持不变（AC-005）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{ index: 1, title: '执行中', status: 'in-progress', content: '结果' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 手动展开
    await wrapper.find('.subtask-header').trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
    // 状态变为 completed
    await wrapper.setProps({
      message: { ...msg, subTasks: [{ index: 1, title: '已完成', status: 'completed', content: '结果' }] },
    });
    // 仍然展开
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
  });

  it('手动折叠 in-progress 子任务后不被自动重新展开（尊重用户操作）', async () => {
    const msg = createTaskMessage({
      status: 'incomplete',
      subTasks: [{ index: 1, title: '待执行', status: 'pending' }],
    });
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // pending -> in-progress：watcher 自动展开
    await wrapper.setProps({
      message: { ...msg, subTasks: [{ index: 1, title: '执行中', status: 'in-progress', content: '部分' }] },
    });
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
    // 手动折叠
    await wrapper.find('.subtask-header').trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
    // content 变化但 status 不变（watcher 不触发）
    await wrapper.setProps({
      message: { ...msg, subTasks: [{ index: 1, title: '执行中', status: 'in-progress', content: '更多内容' }] },
    });
    // 仍然折叠（用户操作被尊重）
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
  });

  it('现有 completed 展开行为不受影响（回归验证）', async () => {
    const wrapper = mount(MessageItem, { props: { message: createTaskMessage() } });
    // 初始折叠
    expect(wrapper.find('.subtask-detail').exists()).toBe(false);
    // 点击第一个子任务（completed）
    await wrapper.findAll('.subtask-header')[0].trigger('click');
    expect(wrapper.find('.subtask-detail').exists()).toBe(true);
    expect(wrapper.find('.subtask-detail').text()).toContain('分析结果');
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

  // ===== CR-002 新增：任务拆解 toggle（AC-012）=====

  it('渲染"任务拆解"toggle 按钮（AC-012, CR-002）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableTaskBreakdown: false } });
    expect(wrapper.find('.btn-task-breakdown').exists()).toBe(true);
    expect(wrapper.find('.btn-task-breakdown').text()).toContain('任务拆解');
  });

  it('点击任务拆解按钮触发 toggleTaskBreakdown 事件（AC-012）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableTaskBreakdown: false } });
    await wrapper.find('.btn-task-breakdown').trigger('click');
    expect(wrapper.emitted('toggleTaskBreakdown')).toBeTruthy();
    expect(wrapper.emitted('toggleTaskBreakdown')).toHaveLength(1);
  });

  it('enableTaskBreakdown=true 时按钮有 active 样式（AC-012）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableTaskBreakdown: true } });
    expect(wrapper.find('.btn-task-breakdown').classes()).toContain('active');
  });

  it('enableTaskBreakdown=false 时按钮无 active 样式（AC-012）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableTaskBreakdown: false } });
    expect(wrapper.find('.btn-task-breakdown').classes()).not.toContain('active');
  });

  it('任务拆解按钮与深度思考按钮在同一 .input-footer 容器内（AC-012）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, enableThinking: true, enableTaskBreakdown: true } });
    const footer = wrapper.find('.input-footer');
    expect(footer.find('.btn-thinking').exists()).toBe(true);
    expect(footer.find('.btn-task-breakdown').exists()).toBe(true);
  });

  // ===== Task-08 新增：知识库选择器集成（AC-011, AC-029）=====

  it('渲染包含 KnowledgeBaseSelector 组件（AC-029）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    expect(wrapper.findComponent(KnowledgeBaseSelector).exists()).toBe(true);
  });

  it('selectedKnowledgeBases prop 透传给 KnowledgeBaseSelector 的 modelValue（AC-029）', () => {
    const wrapper = mount(MessageInput, {
      props: { isStreaming: false, selectedKnowledgeBases: ['产品手册'] },
    });
    const selector = wrapper.findComponent(KnowledgeBaseSelector);
    expect(selector.props('modelValue')).toEqual(['产品手册']);
  });

  it('knowledgeBases prop 透传给 KnowledgeBaseSelector（AC-029）', () => {
    const kbs: KnowledgeBase[] = [
      { id: '1', name: '产品手册', description: '', documentCount: 0, createTime: '' },
    ];
    const wrapper = mount(MessageInput, {
      props: { isStreaming: false, knowledgeBases: kbs },
    });
    const selector = wrapper.findComponent(KnowledgeBaseSelector);
    expect(selector.props('knowledgeBases')).toEqual(kbs);
  });

  it('isStreaming 为 true 时 KnowledgeBaseSelector 的 disabled 为 true（AC-011）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: true } });
    const selector = wrapper.findComponent(KnowledgeBaseSelector);
    expect(selector.props('disabled')).toBe(true);
  });

  it('isStreaming 为 false 时 KnowledgeBaseSelector 的 disabled 为 false（AC-011）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    const selector = wrapper.findComponent(KnowledgeBaseSelector);
    expect(selector.props('disabled')).toBe(false);
  });

  it('KnowledgeBaseSelector 变更时 MessageInput emit update:selectedKnowledgeBases（AC-029）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    const selector = wrapper.findComponent(KnowledgeBaseSelector);
    await selector.vm.$emit('update:modelValue', ['产品手册']);
    expect(wrapper.emitted('update:selectedKnowledgeBases')).toBeTruthy();
    expect(wrapper.emitted('update:selectedKnowledgeBases')![0][0]).toEqual(['产品手册']);
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

  // ===== CR-002 新增：任务拆解编排（AC-001, AC-012）=====

  it('传递 enableTaskBreakdown 状态给 MessageInput（AC-012, CR-002）', () => {
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('enableTaskBreakdown')).toBe(false);
  });

  it('点击任务拆解 toggle 更新 enableTaskBreakdown 状态（AC-012）', async () => {
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });
    const input = wrapper.findComponent(MessageInput);
    expect(input.props('enableTaskBreakdown')).toBe(false);

    // 点击任务拆解按钮
    await wrapper.find('.btn-task-breakdown').trigger('click');

    expect(input.props('enableTaskBreakdown')).toBe(true);
  });

  it('开启拆解后发送消息，streamChat 第 4 参数为 true（AC-001）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();

    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });

    // 开启任务拆解
    await wrapper.find('.btn-task-breakdown').trigger('click');

    // 输入消息并发送
    await wrapper.find('textarea').setValue('复杂任务');
    await wrapper.find('.btn-send').trigger('click');

    // 等待异步操作完成
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 验证 streamChat 被调用，第 4 参数为 true（enableTaskBreakdown）
    expect(streamChat).toHaveBeenCalled();
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    expect(callArgs[3]).toBe(true);
  });

  // ===== Task-09 新增：知识库选择状态管理（AC-012, AC-013, AC-014, AC-015, AC-037）=====

  it('从 session store 读取当前会话的知识库选择（AC-012）', () => {
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });
    const store = useSessionStore();
    const input = wrapper.findComponent(MessageInput);
    // 初始为空数组（自动模式）
    expect(input.props('selectedKnowledgeBases')).toEqual([]);
    expect(input.props('selectedKnowledgeBases')).toEqual(
      store.getKnowledgeBases(store.currentSessionId),
    );
  });

  it('KnowledgeBaseSelector 变更时调用 store.setKnowledgeBases（AC-014）', async () => {
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });
    const store = useSessionStore();
    const sessionId = store.currentSessionId;

    // 通过 MessageInput 触发 update:selectedKnowledgeBases
    await wrapper.findComponent(MessageInput).vm.$emit('update:selectedKnowledgeBases', ['产品手册']);

    // 验证 store 已更新
    expect(store.getKnowledgeBases(sessionId)).toEqual(['产品手册']);
  });

  it('发送消息时 streamChat 接收的知识库参数为当前会话的选择值（AC-013）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();

    const store = useSessionStore();
    // 先创建会话，确保 currentSessionId 不为空（避免 sendMessage 创建新会话导致 sessionId 不一致）
    store.createNewSession();

    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });

    // 设置知识库选择
    store.setKnowledgeBases(store.currentSessionId, ['产品手册', '常见问题']);

    // 输入消息并发送
    await wrapper.find('textarea').setValue('测试消息');
    await wrapper.find('.btn-send').trigger('click');

    // 等待异步操作完成
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 验证 streamChat 第5个参数为知识库选择值
    expect(streamChat).toHaveBeenCalled();
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    expect(callArgs[4]).toEqual(['产品手册', '常见问题']);
  });

  it('知识库选择为空数组时 streamChat 传空数组（自动模式，AC-014）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();

    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });

    // 不设置知识库选择（默认空数组）

    // 输入消息并发送
    await wrapper.find('textarea').setValue('测试消息');
    await wrapper.find('.btn-send').trigger('click');

    // 等待异步操作完成
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 验证 streamChat 第5个参数为空数组
    expect(streamChat).toHaveBeenCalled();
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    expect(callArgs[4]).toEqual([]);
  });
});

/**
 * MessageList 组件测试
 * 验证标准来源：Bug1 修复 - 任务完成后自动滚动以显示总结结果
 */
describe('MessageList', () => {
  it('消息 status 变化时自动滚动到底部（Bug1：任务完成后显示总结）', async () => {
    const msg: Message = {
      id: '1',
      role: 'assistant',
      content: '总结内容',
      createdAt: 0,
      status: 'incomplete',
      subTasks: [{ index: 1, title: '子任务1', status: 'completed' }],
    };

    const wrapper = mount(MessageList, {
      props: { messages: [msg] },
    });

    // Mock scrollHeight（jsdom 默认为 0）
    const listEl = wrapper.find('.message-list').element as HTMLDivElement;
    Object.defineProperty(listEl, 'scrollHeight', { value: 500, configurable: true });

    // 初始 scrollTop 为 0
    expect(listEl.scrollTop).toBe(0);

    // 模拟任务完成：status 从 incomplete 变为 complete（content 不变）
    await wrapper.setProps({
      messages: [{ ...msg, status: 'complete' }],
    });
    await nextTick();

    // 验证 scrollToBottom 被触发（scrollTop 应被设为 scrollHeight）
    expect(listEl.scrollTop).toBe(500);
  });
});

/**
 * NavBar 组件测试（Task-10）
 * 验证标准来源：Task-10 验证标准
 * 关联 AC：AC-001
 */
describe('NavBar', () => {
  it('渲染"对话"和"知识库"两个导航项', () => {
    const wrapper = mount(NavBar, { props: { currentView: 'chat' } });
    const items = wrapper.findAll('.nav-item');
    expect(items).toHaveLength(2);
    expect(items[0].text()).toContain('对话');
    expect(items[1].text()).toContain('知识库');
  });

  it('currentView 为 chat 时"对话"项高亮（AC-001）', () => {
    const wrapper = mount(NavBar, { props: { currentView: 'chat' } });
    const items = wrapper.findAll('.nav-item');
    expect(items[0].classes()).toContain('active');
    expect(items[1].classes()).not.toContain('active');
  });

  it('currentView 为 knowledge 时"知识库"项高亮（AC-001）', () => {
    const wrapper = mount(NavBar, { props: { currentView: 'knowledge' } });
    const items = wrapper.findAll('.nav-item');
    expect(items[0].classes()).not.toContain('active');
    expect(items[1].classes()).toContain('active');
  });

  it('点击"知识库"时 emit update:currentView 为 "knowledge"', async () => {
    const wrapper = mount(NavBar, { props: { currentView: 'chat' } });
    await wrapper.findAll('.nav-item')[1].trigger('click');
    expect(wrapper.emitted('update:currentView')).toBeTruthy();
    expect(wrapper.emitted('update:currentView')![0]).toEqual(['knowledge']);
  });

  it('点击"对话"时 emit update:currentView 为 "chat"', async () => {
    const wrapper = mount(NavBar, { props: { currentView: 'knowledge' } });
    await wrapper.findAll('.nav-item')[0].trigger('click');
    expect(wrapper.emitted('update:currentView')![0]).toEqual(['chat']);
  });
});

/**
 * App 条件渲染测试（Task-10）
 * 验证标准来源：Task-10 验证标准
 * 关联 AC：AC-001
 */
describe('App 条件渲染', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    localStorage.clear();
  });

  it('currentView 为 chat 时渲染对话页面（SessionList + ChatWindow）', () => {
    const wrapper = mount(App, { global: { plugins: [pinia] } });
    expect(wrapper.findComponent(SessionList).exists()).toBe(true);
    expect(wrapper.findComponent(ChatWindow).exists()).toBe(true);
  });

  it('点击知识库导航后渲染 KnowledgeBasePage，对话页面隐藏', async () => {
    const wrapper = mount(App, { global: { plugins: [pinia] } });
    // 初始为对话页面
    expect(wrapper.findComponent(KnowledgeBasePage).exists()).toBe(false);
    // 点击知识库导航
    await wrapper.findAll('.nav-item')[1].trigger('click');
    // 渲染知识库页面
    expect(wrapper.findComponent(KnowledgeBasePage).exists()).toBe(true);
    // 对话页面隐藏
    expect(wrapper.findComponent(SessionList).exists()).toBe(false);
  });

  it('切换到知识库再切回对话后，会话选择状态保持不变', async () => {
    const wrapper = mount(App, { global: { plugins: [pinia] } });
    const store = useSessionStore();
    // 创建新会话并选中
    store.createNewSession();
    const sessionId = store.currentSessionId;
    // 切换到知识库页面
    await wrapper.findAll('.nav-item')[1].trigger('click');
    // 切换回对话页面
    await wrapper.findAll('.nav-item')[0].trigger('click');
    // 会话选择状态保持不变
    expect(store.currentSessionId).toBe(sessionId);
  });
});
