// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import MessageItem from '@/components/MessageItem.vue';
import MessageInput from '@/components/MessageInput.vue';
import ChatWindow from '@/components/ChatWindow.vue';
import MessageList from '@/components/MessageList.vue';
import KnowledgeBaseSelector from '@/components/KnowledgeBaseSelector.vue';
import ModelSelector from '@/components/ModelSelector.vue';
import NavBar from '@/components/NavBar.vue';
import KnowledgeBasePage from '@/components/KnowledgeBasePage.vue';
import SettingsPage from '@/components/SettingsPage.vue';
import SessionList from '@/components/SessionList.vue';
import App from '@/App.vue';
import { useSessionStore } from '@/stores/session';
import { useRagStore } from '@/stores/rag';
import { useLlmStore } from '@/stores/llm';
import { getModels, getConfigStatus } from '@/api/llm';
import type { Message, SubTask, KnowledgeBase, LlmModel, ConfigStatus } from '@/types';

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

// Mock LLM API 避免 LlmConfigPage 测试发起真实 API 调用
vi.mock('@/api/llm', () => ({
  getPredefinedVendors: vi.fn().mockResolvedValue([]),
  getVendors: vi.fn().mockResolvedValue([]),
  getModels: vi.fn().mockResolvedValue([]),
  getConfigStatus: vi.fn(),
  addVendor: vi.fn(),
  updateVendor: vi.fn(),
  deleteVendor: vi.fn(),
  testConnection: vi.fn(),
  syncConfig: vi.fn(),
}));

// Mock SettingsPage，避免其内部 LlmConfigPage/McpServicePage 在 App 条件渲染测试中实例化
vi.mock('@/components/SettingsPage.vue', () => ({
  default: { name: 'SettingsPage', template: '<div class="mock-settings"></div>' },
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

  // ========== HITL 人机交互渲染（Task-09，BUG 修复；unified-chat-mode Task-17 适配 AskUserCard）==========

  /**
   * BUG 修复核心测试：ask_user 事件后后端立即发送 done，消息 status=complete，
   * 但 HITL 场景下选项按钮必须可交互（不能 disabled），
   * 否则用户无法点击选项，前端"未渲染为选项框"。
   * 修复前 disabled=status!=='incomplete' 导致 status=complete 时按钮全部禁用。
   * unified-chat-mode：ConfirmCard 已由 AskUserCard 替代（类名 .option-row，事件 reply）。
   */
  it('confirm 类型 askUserData 渲染选项按钮且 status=complete 时仍可点击（HITL BUG 修复）', () => {
    const msg: Message = {
      id: 'hitl-confirm-1',
      role: 'assistant',
      content: '',
      createdAt: 0,
      // 模拟 ask_user 事件后 done 事件触发 markComplete 的状态
      status: 'complete',
      askUserData: {
        type: 'confirm',
        question: '确认删除文件 test.txt？此操作不可恢复。',
        options: ['确认删除', '取消'],
        retryCount: 0,
      },
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    // 渲染统一交互卡片
    expect(wrapper.find('.ask-user-card').exists()).toBe(true);
    expect(wrapper.find('.question-text').text()).toBe('确认删除文件 test.txt？此操作不可恢复。');
    // 选项按钮渲染且非禁用（可交互）
    const buttons = wrapper.findAll('.option-row');
    expect(buttons).toHaveLength(2);
    expect(buttons[0].text()).toContain('确认删除');
    expect(buttons[0].attributes('disabled')).toBeUndefined();
    expect(buttons[1].attributes('disabled')).toBeUndefined();
  });

  it('text 类型 askUserData 渲染内嵌输入框（非选项框，AC-T02）', () => {
    const msg: Message = {
      id: 'hitl-text-1',
      role: 'assistant',
      content: '',
      createdAt: 0,
      status: 'complete',
      askUserData: {
        type: 'text',
        question: '请提供订单号，例如：ORD-12345',
        options: [],
        retryCount: 0,
      },
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.question-text').text()).toBe('请提供订单号，例如：ORD-12345');
    // text 类型渲染输入框而非选项行
    expect(wrapper.find('.text-area .ask-input').exists()).toBe(true);
    expect(wrapper.findAll('.option-row')).toHaveLength(0);
  });

  it('confirm 选项点击后 emit reply 事件（逐层传递到 ChatWindow 发送回复）', async () => {
    const msg: Message = {
      id: 'hitl-confirm-2',
      role: 'assistant',
      content: '',
      createdAt: 0,
      status: 'complete',
      askUserData: {
        type: 'confirm',
        question: '确认删除？',
        options: ['确认删除', '取消'],
        retryCount: 0,
      },
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    await wrapper.findAll('.option-row')[0].trigger('click');
    expect(wrapper.emitted('reply')?.[0]).toEqual(['确认删除']);
  });

  it('无 askUserData 时不渲染 HITL 区块（零回归）', () => {
    const msg: Message = {
      id: 'hitl-none-1',
      role: 'assistant',
      content: '正常回复',
      createdAt: 0,
      status: 'complete',
    };
    const wrapper = mount(MessageItem, { props: { message: msg } });
    expect(wrapper.find('.ask-user-block').exists()).toBe(false);
  });

  // ===== BUG 修复回归测试：拒绝后权限确认卡片必须锁定为"已拒绝" =====
  // 复现依据：MessageItem 的 answered 判定原先为 !!approved——拒绝时 approved=false，
  // !!false=false 导致卡片不锁定、批准/拒绝按钮仍可点击，与批准路径行为不一致。
  // 正确语义：approved !== undefined 即已决策（true=已批准，false=已拒绝，undefined=待决策）。

  /** 构造 permission 形态消息（kind=permission） */
  function buildPermissionMsg(approved?: boolean): Message {
    return {
      id: 'perm-card-1',
      role: 'assistant',
      content: '',
      createdAt: 0,
      status: 'complete',
      askUserData: {
        type: 'confirm',
        kind: 'permission',
        question: '请求使用工具「httpGet」',
        retryCount: 0,
        toolName: 'httpGet',
        toolDescription: '发送 HTTP GET 请求',
        toolArguments: '{"url":"https://example.com"}',
        approved,
      },
    };
  }

  it('拒绝后（approved=false）权限卡片锁定为"已拒绝"，按钮隐藏（BUG 修复：拒绝不锁定卡片）', () => {
    const wrapper = mount(MessageItem, { props: { message: buildPermissionMsg(false) } });
    // 修复断言：卡片应进入锁定态展示"已拒绝"，而非保留批准/拒绝按钮
    expect(wrapper.find('.answered-line').text()).toBe('已拒绝');
    expect(wrapper.find('[data-testid="confirm-approve"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="confirm-deny"]').exists()).toBe(false);
  });

  it('批准后（approved=true）权限卡片锁定为"已批准"（既有行为回归保障）', () => {
    const wrapper = mount(MessageItem, { props: { message: buildPermissionMsg(true) } });
    expect(wrapper.find('.answered-line').text()).toBe('已批准');
    expect(wrapper.find('[data-testid="confirm-approve"]').exists()).toBe(false);
  });

  it('待决策（approved=undefined）权限卡片显示批准/拒绝按钮（既有行为回归保障）', () => {
    const wrapper = mount(MessageItem, { props: { message: buildPermissionMsg(undefined) } });
    expect(wrapper.find('[data-testid="confirm-approve"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="confirm-deny"]').exists()).toBe(true);
    expect(wrapper.find('.answered-line').exists()).toBe(false);
  });
});

describe('MessageInput', () => {
  let pinia: Pinia;

  beforeEach(() => {
    // MessageInput 内部使用 useSessionStore，需要激活 Pinia
    pinia = createPinia();
    setActivePinia(pinia);
  });

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

  // ===== unified-chat-mode Task-19：/plan 前缀命令提示条（AC-N04 可发现性）=====
  // 注：传入 chat 模型使 textarea 可用（hasModels=true），否则 setValue 不生效

  it('输入以 / 开头时显示 /plan 提示条（AC-N04）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, models: [chatModel] } });
    expect(wrapper.find('.plan-hint').exists()).toBe(false);

    await wrapper.find('textarea').setValue('/plan 帮我做个计划');
    expect(wrapper.find('.plan-hint').exists()).toBe(true);
    expect(wrapper.find('.plan-hint').text()).toContain('/plan');
  });

  it('输入不以 / 开头时不显示 /plan 提示条（AC-N04）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, models: [chatModel] } });
    await wrapper.find('textarea').setValue('帮我做个计划');
    expect(wrapper.find('.plan-hint').exists()).toBe(false);
  });

  it('流式中不显示 /plan 提示条（AC-N04）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: true, models: [chatModel] } });
    await wrapper.find('textarea').setValue('/plan 帮我做个计划');
    expect(wrapper.find('.plan-hint').exists()).toBe(false);
  });

  it('输入为 / 时点击提示条自动补全为 /plan （AC-N04）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, models: [chatModel] } });
    await wrapper.find('textarea').setValue('/');
    await wrapper.find('.plan-hint').trigger('click');
    const textarea = wrapper.find('textarea');
    expect((textarea.element as HTMLTextAreaElement).value).toBe('/plan ');
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

  // ===== Task-22 新增：模型选择器集成 =====

  const chatModel: LlmModel = {
    id: 'model-001',
    vendorId: 'v1',
    vendorName: '火山引擎',
    modelName: 'doubao-seed-2.0-pro',
    displayName: '豆包Seed 2.0 Pro',
    type: 'chat',
    supportsVision: true,
  };

  it('渲染包含 ModelSelector 组件（Task-22）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    expect(wrapper.findComponent(ModelSelector).exists()).toBe(true);
  });

  it('selectedModel 与 models prop 透传给 ModelSelector（Task-22）', () => {
    const wrapper = mount(MessageInput, {
      props: { isStreaming: false, selectedModel: 'model-001', models: [chatModel] },
    });
    const selector = wrapper.findComponent(ModelSelector);
    expect(selector.props('modelValue')).toBe('model-001');
    expect(selector.props('models')).toEqual([chatModel]);
  });

  it('isStreaming 为 true 时 ModelSelector 的 disabled 为 true（Task-22）', () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: true, models: [chatModel] } });
    const selector = wrapper.findComponent(ModelSelector);
    expect(selector.props('disabled')).toBe(true);
  });

  it('ModelSelector 变更时 MessageInput emit update:selectedModel（Task-22）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false } });
    const selector = wrapper.findComponent(ModelSelector);
    await selector.vm.$emit('update:modelValue', 'model-001');
    expect(wrapper.emitted('update:selectedModel')).toBeTruthy();
    expect(wrapper.emitted('update:selectedModel')![0][0]).toBe('model-001');
  });

  it('无 chat 模型时显示"请先配置 chat 类型模型"引导并触发去配置（Task-22）', async () => {
    const wrapper = mount(MessageInput, { props: { isStreaming: false, models: [] } });
    expect(wrapper.find('.config-empty-state').text()).toContain('请先配置 chat 类型模型');
    await wrapper.find('.btn-go-config').trigger('click');
    expect(wrapper.emitted('navigate-to-config')).toBeTruthy();
  });

  it('hasConfig 为 false 时显示"请先配置 LLM 模型"引导（Task-22）', () => {
    const wrapper = mount(MessageInput, {
      props: { isStreaming: false, hasConfig: false, models: [chatModel] },
    });
    expect(wrapper.find('.config-empty-state').text()).toContain('请先配置 LLM 模型');
  });
});

/**
 * ChatWindow 组件测试（CR-001 T-28）
 * 验证标准来源：T-28 验证标准
 * 关联 AC：AC-021、AC-022、AC-024
 */
describe('ChatWindow', () => {
  let pinia: Pinia;

  /** 测试用 chat 模型（Task-22） */
  const chatModel: LlmModel = {
    id: 'model-001',
    vendorId: 'vendor-001',
    vendorName: '火山引擎',
    modelName: 'doubao-seed-2.0-pro',
    displayName: '豆包Seed 2.0 Pro',
    type: 'chat',
    supportsVision: true,
  };

  /** 测试用配置状态（Task-22） */
  const configStatus: ConfigStatus = {
    hasConfig: true,
    hasChatModel: true,
    hasEmbeddingModel: false,
    vendorCount: 1,
    chatModelCount: 1,
  };

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    localStorage.clear();
    vi.clearAllMocks();
    // Task-22: 预置 chat 模型与配置状态，使 MessageInput 正常渲染且可发送
    const llmStore = useLlmStore();
    llmStore.chatModels = [chatModel];
    llmStore.configStatus = configStatus;
    vi.mocked(getModels).mockResolvedValue([chatModel]);
    vi.mocked(getConfigStatus).mockResolvedValue(configStatus);
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

    // 验证 streamChat 第3个参数为知识库选择值（unified-chat-mode：签名改为 (sessionId, message, knowledgeBases, ...)）
    expect(streamChat).toHaveBeenCalled();
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    expect(callArgs[2]).toEqual(['产品手册', '常见问题']);
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

    // 验证 streamChat 第3个参数为空数组（unified-chat-mode：签名改为 (sessionId, message, knowledgeBases, ...)）
    expect(streamChat).toHaveBeenCalled();
    const callArgs = vi.mocked(streamChat).mock.calls[0];
    expect(callArgs[2]).toEqual([]);
  });

  // ===== BUG 修复回归测试：权限确认批准/拒绝必须发出非空 message 请求 =====
  // 验证标准来源：BUG（点击确认按钮页面卡死）——handleApprove/handleDeny 原先传
  // 空 message，被 sendMessage 空消息拦截与后端 @NotBlank 校验双重拒绝，
  // 请求根本发不出去导致流程卡死。修复后必须携带非空决策文案。

  /** 构造带权限确认卡片的会话状态（模拟 tool_confirm 事件后） */
  function setupPendingToolConfirm() {
    const store = useSessionStore();
    store.createNewSession();
    const sessionId = store.currentSessionId;
    store.addMessage(sessionId, {
      id: 'assistant-msg-1',
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'incomplete',
      reasoning: '',
    });
    store.setToolConfirmData('assistant-msg-1', {
      toolName: 'httpGet',
      toolDescription: '发送 HTTP GET 请求',
      arguments: '{"url":"https://example.com"}',
    });
    return { store, sessionId };
  }

  it('点击批准按钮时 streamChat 收到非空 message 且 toolApproved=true（BUG 修复：空消息被拦截导致卡死）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();

    const { store } = setupPendingToolConfirm();
    const beforeCount = store.sessions.find((s) => s.sessionId === store.currentSessionId)!.messages.length;
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });

    // 模拟 ConfirmCard 点击批准：MessageList emit approve -> ChatWindow handleApprove
    await wrapper.findComponent(MessageList).vm.$emit('approve');
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 修复断言：请求必须发出（修复前 sendMessage('') 被空消息拦截，streamChat 零调用）
    expect(streamChat).toHaveBeenCalledTimes(1);
    const args = vi.mocked(streamChat).mock.calls[0];
    // message 参数（第2位）非空，通过前端拦截与后端 @NotBlank 校验
    expect(args[1].trim().length).toBeGreaterThan(0);
    // toolApproved 参数（最后一位）为 true
    expect(args[args.length - 1]).toBe(true);
    // 交互优化：silent 静默恢复——不产生"已批准使用工具"用户消息气泡
    const session = store.sessions.find((s) => s.sessionId === store.currentSessionId);
    const userBubbles = session!.messages.filter((m) => m.role === 'user');
    expect(userBubbles.every((m) => !m.content.includes('已批准使用工具'))).toBe(true);
    expect(userBubbles.length).toBe(0);
    expect(session!.messages.length).toBe(beforeCount + 1); // 仅新增助手占位
    // 卡片决策已记录（approved=true 锁定）
    const confirmMsg = session!.messages.find((m) => m.askUserData?.kind === 'permission');
    expect(confirmMsg!.askUserData!.approved).toBe(true);
  });

  it('点击拒绝按钮时 streamChat 收到非空 message 且 toolApproved=false（BUG 修复回归）', async () => {
    const { streamChat } = await import('@/api/chat');
    vi.mocked(streamChat).mockClear();

    const { store } = setupPendingToolConfirm();
    const beforeCount = store.sessions.find((s) => s.sessionId === store.currentSessionId)!.messages.length;
    const wrapper = mount(ChatWindow, {
      global: { plugins: [pinia] },
    });

    // 模拟 ConfirmCard 点击拒绝：MessageList emit deny -> ChatWindow handleDeny
    await wrapper.findComponent(MessageList).vm.$emit('deny');
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(streamChat).toHaveBeenCalledTimes(1);
    const args = vi.mocked(streamChat).mock.calls[0];
    expect(args[1].trim().length).toBeGreaterThan(0);
    expect(args[args.length - 1]).toBe(false);
    // 交互优化：silent 静默恢复——不产生"已拒绝使用工具"用户消息气泡
    const session = store.sessions.find((s) => s.sessionId === store.currentSessionId);
    const userBubbles = session!.messages.filter((m) => m.role === 'user');
    expect(userBubbles.every((m) => !m.content.includes('已拒绝使用工具'))).toBe(true);
    expect(userBubbles.length).toBe(0);
    expect(session!.messages.length).toBe(beforeCount + 1); // 仅新增助手占位
    const confirmMsg = session!.messages.find((m) => m.askUserData?.kind === 'permission');
    expect(confirmMsg!.askUserData!.approved).toBe(false);
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
  it('渲染"对话"、"知识库"、"编排"和"设置"四个导航项（P2 新增编排，AC-032）', () => {
    const wrapper = mount(NavBar, { props: { currentView: 'chat' } });
    const items = wrapper.findAll('.nav-item');
    expect(items).toHaveLength(4);
    expect(items[0].text()).toContain('对话');
    expect(items[1].text()).toContain('知识库');
    expect(items[2].text()).toContain('编排');
    expect(items[3].text()).toContain('设置');
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

  it('currentView 为 settings 时"设置"项高亮（AC-001）', () => {
    const wrapper = mount(NavBar, { props: { currentView: 'settings' } });
    const items = wrapper.findAll('.nav-item');
    expect(items[3].classes()).toContain('active');
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

  it('点击"设置"时 emit update:currentView 为 "settings"', async () => {
    const wrapper = mount(NavBar, { props: { currentView: 'chat' } });
    await wrapper.findAll('.nav-item')[3].trigger('click');
    expect(wrapper.emitted('update:currentView')![0]).toEqual(['settings']);
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

  it('点击"设置"导航后渲染 SettingsPage，对话页面隐藏', async () => {
    const wrapper = mount(App, { global: { plugins: [pinia] } });
    // 初始为对话页面
    expect(wrapper.findComponent(SettingsPage).exists()).toBe(false);
    // 点击设置导航（P2 新增编排后 index 3）
    await wrapper.findAll('.nav-item')[3].trigger('click');
    // 渲染设置页面
    expect(wrapper.findComponent(SettingsPage).exists()).toBe(true);
    // 对话页面隐藏
    expect(wrapper.findComponent(SessionList).exists()).toBe(false);
  });

  it('从 ChatWindow 的 navigate-to-config 事件切换到设置视图', async () => {
    const wrapper = mount(App, { global: { plugins: [pinia] } });
    expect(wrapper.findComponent(SettingsPage).exists()).toBe(false);
    // 触发 ChatWindow 的 navigate-to-config 事件
    await wrapper.findComponent(ChatWindow).vm.$emit('navigate-to-config');
    expect(wrapper.findComponent(SettingsPage).exists()).toBe(true);
  });
});
