import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import McpServicePage from './McpServicePage.vue';
import type { McpServerInfo } from '@/types';

/**
 * McpServicePage 组件测试（Task-07）
 * 验证标准来源：Task-07 验证标准
 * 关联 AC：AC-002, AC-004, AC-006, AC-012, AC-013, AC-014, AC-016,
 *          AC-018, AC-025, AC-026, AC-027
 */

// mock 子组件，聚焦页面编排逻辑
vi.mock('./McpServerCard.vue', () => ({
  default: {
    name: 'McpServerCard',
    template: '<div class="mock-card" @click="$emit(\'delete\', \'fetch\')">card</div>',
    props: ['server', 'tools', 'loadingTools'],
    emits: ['delete', 'reconnect', 'toggleTools'],
  },
}));

vi.mock('./McpJsonConfigEditor.vue', () => ({
  default: {
    name: 'McpJsonConfigEditor',
    template: '<div class="mock-editor" :data-visible="visible"></div>',
    props: ['visible'],
    emits: ['close', 'saved'],
  },
}));

// mock store
const storeState = vi.hoisted(() => ({
  servers: [] as McpServerInfo[],
  toolsCache: {} as Record<string, unknown[]>,
  loadServers: vi.fn(),
  deleteServer: vi.fn(),
  reconnectServer: vi.fn(),
  loadServerTools: vi.fn(),
}));

vi.mock('@/stores/mcp', () => ({
  useMcpStore: () => storeState,
}));

/** 构造 Server mock 数据 */
function makeServer(overrides: Partial<McpServerInfo> = {}): McpServerInfo {
  return {
    name: 'fetch',
    transport: 'STDIO',
    status: 'CONNECTED',
    enabled: true,
    toolCount: 1,
    lastError: null,
    connectTime: '2026-08-07T10:00:00',
    url: null,
    command: 'npx',
    args: ['mcp-fetch-server'],
    ...overrides,
  };
}

async function mountPage() {
  const wrapper = mount(McpServicePage);
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
  storeState.servers = [];
  storeState.toolsCache = {};
  storeState.loadServers.mockResolvedValue(undefined);
  storeState.deleteServer.mockResolvedValue(undefined);
  storeState.reconnectServer.mockResolvedValue(undefined);
  storeState.loadServerTools.mockResolvedValue([]);
});

describe('McpServicePage 初始化', () => {
  it('onMounted 时自动调用 loadServers（AC-004）', async () => {
    await mountPage();
    expect(storeState.loadServers).toHaveBeenCalled();
  });

  it('servers 为空时显示空状态引导（AC-006）', async () => {
    const wrapper = await mountPage();
    expect(wrapper.find('.empty-state').exists()).toBe(true);
    expect(wrapper.find('.empty-text').text()).toBe('暂无 MCP 服务');
  });

  it('servers 有数据时渲染 Server 卡片列表', async () => {
    storeState.servers = [makeServer(), makeServer({ name: 'mermaid', transport: 'HTTP' })];
    const wrapper = await mountPage();
    expect(wrapper.findAll('.mock-card')).toHaveLength(2);
    expect(wrapper.find('.empty-state').exists()).toBe(false);
  });
});

describe('McpServicePage 添加服务', () => {
  it('点击添加服务按钮显示编辑器', async () => {
    const wrapper = await mountPage();
    await wrapper.find('.btn-add').trigger('click');
    expect(wrapper.find('.mock-editor').attributes('data-visible')).toBe('true');
  });

  it('编辑器 saved 后关闭并刷新列表', async () => {
    const wrapper = await mountPage();
    await wrapper.find('.btn-add').trigger('click');
    const editor = wrapper.findComponent({ name: 'McpJsonConfigEditor' });
    editor.vm.$emit('saved');
    await flushPromises();
    expect(storeState.loadServers).toHaveBeenCalledTimes(2); // 初始 + saved 后刷新
    expect(editor.props('visible')).toBe(false);
  });
});

describe('McpServicePage 删除与重连', () => {
  it('卡片 delete 事件触发 store.deleteServer', async () => {
    storeState.servers = [makeServer()];
    const wrapper = await mountPage();
    await wrapper.find('.mock-card').trigger('click');
    expect(storeState.deleteServer).toHaveBeenCalledWith('fetch');
  });

  it('删除已展开的 Server 时折叠工具列表', async () => {
    storeState.servers = [makeServer()];
    const wrapper = await mountPage();
    // 先展开
    const card = wrapper.findComponent({ name: 'McpServerCard' });
    card.vm.$emit('toggleTools', 'fetch');
    await flushPromises();
    // 删除
    await wrapper.find('.mock-card').trigger('click');
    await flushPromises();
    // 折叠后卡片 tools 为 null
    expect(card.props('tools')).toBeNull();
  });

  it('重连成功调用 store.reconnectServer', async () => {
    storeState.servers = [makeServer({ status: 'DISCONNECTED' })];
    const wrapper = await mountPage();
    const card = wrapper.findComponent({ name: 'McpServerCard' });
    card.vm.$emit('reconnect', 'fetch');
    await flushPromises();
    expect(storeState.reconnectServer).toHaveBeenCalledWith('fetch');
  });

  it('重连失败显示错误信息（AC-016）', async () => {
    storeState.servers = [makeServer()];
    storeState.reconnectServer.mockRejectedValueOnce(new Error('MCP Server 已连接，无需重连'));
    const wrapper = await mountPage();
    const card = wrapper.findComponent({ name: 'McpServerCard' });
    card.vm.$emit('reconnect', 'fetch');
    await flushPromises();
    expect(wrapper.find('.page-error').text()).toContain('MCP Server 已连接');
  });

  it('删除失败显示错误信息（AC-025）', async () => {
    storeState.servers = [makeServer()];
    storeState.deleteServer.mockRejectedValueOnce(new Error('MCP Server 不存在'));
    const wrapper = await mountPage();
    await wrapper.find('.mock-card').trigger('click');
    await flushPromises();
    expect(wrapper.find('.page-error').text()).toContain('MCP Server 不存在');
  });
});

describe('McpServicePage 工具展开（AC-018）', () => {
  it('toggleTools 加载工具列表并传给卡片', async () => {
    const tools = [{ originalName: 'fetch', registeredName: 'mcp_fetch_fetch', description: '', parametersSchema: '' }];
    storeState.servers = [makeServer()];
    storeState.loadServerTools.mockResolvedValueOnce(tools);
    const wrapper = await mountPage();
    const card = wrapper.findComponent({ name: 'McpServerCard' });
    card.vm.$emit('toggleTools', 'fetch');
    await flushPromises();
    expect(storeState.loadServerTools).toHaveBeenCalledWith('fetch');
    expect(card.props('tools')).toEqual(tools);
  });

  it('再次 toggleTools 折叠工具列表', async () => {
    storeState.servers = [makeServer()];
    storeState.loadServerTools.mockResolvedValueOnce([]);
    const wrapper = await mountPage();
    const card = wrapper.findComponent({ name: 'McpServerCard' });
    card.vm.$emit('toggleTools', 'fetch');
    await flushPromises();
    card.vm.$emit('toggleTools', 'fetch');
    await flushPromises();
    expect(card.props('tools')).toBeNull();
  });
});

describe('McpServicePage 异常处理', () => {
  it('模块禁用时显示提示并禁用添加按钮（AC-026）', async () => {
    storeState.loadServers.mockRejectedValueOnce(new Error('MCP 模块已禁用，请在配置中启用'));
    const wrapper = await mountPage();
    expect(wrapper.find('.page-error').text()).toContain('MCP 模块已禁用');
    expect(wrapper.find('.btn-add').attributes('disabled')).toBeDefined();
  });

  it('网络异常时显示网络错误提示（AC-027）', async () => {
    storeState.loadServers.mockRejectedValueOnce(new Error('Failed to fetch'));
    const wrapper = await mountPage();
    expect(wrapper.find('.page-error').text()).toContain('网络异常，请检查后端服务是否运行');
  });
});
