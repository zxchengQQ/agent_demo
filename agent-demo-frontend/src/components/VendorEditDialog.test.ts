// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

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

// Mock localStorage 工具避免污染真实存储
vi.mock('@/utils/llm-storage', () => ({
  saveLlmConfig: vi.fn(),
  loadLlmConfig: vi.fn(),
  clearLlmConfig: vi.fn(),
  saveLastUsedModelId: vi.fn(),
  loadLastUsedModelId: vi.fn(),
}));

import VendorEditDialog from '@/components/VendorEditDialog.vue';
import { testConnection, addVendor, updateVendor } from '@/api/llm';
import { saveLlmConfig } from '@/utils/llm-storage';
import type { PredefinedVendor, LlmVendor } from '@/types';

/** 测试用预定义厂商 */
const predefined: PredefinedVendor[] = [
  {
    code: 'volc',
    name: '火山引擎',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    thinkingTrigger: 'enabled',
    models: [
      { modelName: 'doubao-seed-2.0-pro', displayName: '豆包Seed 2.0 Pro', type: 'chat', supportsVision: true },
      { modelName: 'doubao-embedding', displayName: '豆包向量化', type: 'embedding', supportsVision: false },
    ],
  },
];

/** 测试用厂商（编辑模式） */
const vendor: LlmVendor = {
  id: 'vendor-001',
  name: '火山引擎',
  type: 'predefined',
  baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
  apiKeyMasked: 'sk-****7890',
  apiKeyConfigured: true,
  thinkingTrigger: 'enabled',
  timeout: 60000,
  maxRetries: 3,
  temperature: 0.7,
  models: [
    { id: 'm1', vendorId: 'vendor-001', vendorName: '火山引擎', modelName: 'doubao-seed-2.0-pro', displayName: '豆包Seed 2.0 Pro', type: 'chat', supportsVision: true },
  ],
};

describe('VendorEditDialog', () => {
  let pinia: Pinia;

  /** 挂载并打开弹窗（visible false -> true 触发初始化 watcher） */
  async function openDialog(overrides: { vendor?: LlmVendor | null; predefinedVendors?: PredefinedVendor[] } = {}) {
    const wrapper = mount(VendorEditDialog, {
      props: {
        visible: false,
        vendor: overrides.vendor !== undefined ? overrides.vendor : null,
        predefinedVendors: overrides.predefinedVendors ?? predefined,
      },
      global: { plugins: [pinia] },
    });
    await wrapper.setProps({ visible: true });
    return wrapper;
  }

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
  });

  it('新增模式打开时渲染"添加厂商"标题和表单区域', async () => {
    const wrapper = await openDialog();
    expect(wrapper.find('.dialog-title').text()).toBe('添加厂商');
    expect(wrapper.find('.section-label').text()).toContain('厂商选择');
    expect(wrapper.find('.btn-test').exists()).toBe(true);
    expect(wrapper.find('.btn-submit').exists()).toBe(true);
  });

  it('新增模式默认选中预定义厂商并自动填充基础信息', async () => {
    const wrapper = await openDialog();
    // 自动填充第一个预定义厂商的名称和 Base URL
    const nameInput = wrapper.findAll('.form-field .input')[0];
    expect((nameInput.element as HTMLInputElement).value).toBe('火山引擎');
  });

  it('新增模式模型配置自动填充预定义模型', async () => {
    const wrapper = await openDialog();
    // 预定义厂商的 chat 模型行已填充
    const modelRows = wrapper.findAll('.model-row');
    expect(modelRows.length).toBeGreaterThan(0);
    expect((modelRows[0].find('.model-name-input').element as HTMLInputElement).value)
      .toBe('doubao-seed-2.0-pro');
  });

  it('切换"自定义"选项清空基础信息与模型', async () => {
    const wrapper = await openDialog();
    // 选择"自定义"选项触发变更
    const select = wrapper.find('.vendor-select-row select');
    await select.setValue('__custom__');
    // 名称被清空
    const nameInput = wrapper.findAll('.form-field .input')[0];
    expect((nameInput.element as HTMLInputElement).value).toBe('');
  });

  it('点击小眼睛按钮切换密码显示/隐藏', async () => {
    const wrapper = await openDialog();
    const apiKeyInput = wrapper.findAll('.api-key-row .input')[0];
    // 初始为 password
    expect(apiKeyInput.attributes('type')).toBe('password');
    await wrapper.find('.btn-eye').trigger('click');
    expect(apiKeyInput.attributes('type')).toBe('text');
    await wrapper.find('.btn-eye').trigger('click');
    expect(apiKeyInput.attributes('type')).toBe('password');
  });

  it('测试连接成功显示绿色提示并可保存', async () => {
    vi.mocked(testConnection).mockResolvedValue({ success: true, message: '连接成功', latency: 100 });
    const wrapper = await openDialog();

    // 输入 API Key
    await wrapper.findAll('.api-key-row .input')[0].setValue('sk-test-key');
    // 点击测试连接
    await wrapper.find('.btn-test').trigger('click');

    // 等待异步
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(testConnection).toHaveBeenCalled();
    expect(wrapper.find('.result-success').exists()).toBe(true);
    expect(wrapper.find('.result-success').text()).toContain('连接成功');
  });

  it('测试连接失败显示红色提示并禁止保存', async () => {
    vi.mocked(testConnection).mockResolvedValue({ success: false, message: 'API Key 无效', latency: 100 });
    const wrapper = await openDialog();

    await wrapper.findAll('.api-key-row .input')[0].setValue('sk-bad-key');
    await wrapper.find('.btn-test').trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(wrapper.find('.result-fail').exists()).toBe(true);
    expect(wrapper.find('.result-fail').text()).toContain('API Key 无效');
    // 测试失败时保存按钮禁用
    expect((wrapper.find('.btn-submit').element as HTMLButtonElement).disabled).toBe(true);
  });

  it('chat 类型模型行显示"支持视图"勾选框', async () => {
    const wrapper = await openDialog();
    // 预定义 chat 模型行（第一个 model-row 为 chat）
    const firstRow = wrapper.findAll('.model-row')[0];
    expect(firstRow.find('.vision-check').exists()).toBe(true);
  });

  it('模型名称为空时显示校验提示', async () => {
    const wrapper = await openDialog();
    // 添加一个空模型行（当前 chat tab）
    await wrapper.find('.btn-add-model').trigger('click');
    expect(wrapper.find('.field-error').exists()).toBe(true);
    expect(wrapper.find('.field-error').text()).toContain('模型名称不能为空');
  });

  it('新增模式保存调用 addVendor 并 emit saved', async () => {
    vi.mocked(addVendor).mockResolvedValue(vendor);
    const wrapper = await openDialog();
    const submit = wrapper.find('.btn-submit');

    // 预定义填充后基础信息已完整，补齐 API Key
    await wrapper.findAll('.api-key-row .input')[0].setValue('sk-test-key');
    // 模型行已有内容
    expect((submit.element as HTMLButtonElement).disabled).toBe(false);

    await submit.trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(addVendor).toHaveBeenCalled();
    expect(saveLlmConfig).toHaveBeenCalled();
    expect(wrapper.emitted('saved')).toBeTruthy();
  });

  it('编辑模式显示厂商名称且只读', async () => {
    const wrapper = await openDialog({ vendor });
    expect(wrapper.find('.dialog-title').text()).toBe('编辑厂商');
    expect(wrapper.find('.readonly-name').text()).toBe('火山引擎');
  });

  it('编辑模式显示 API Key 脱敏提示', async () => {
    const wrapper = await openDialog({ vendor });
    expect(wrapper.find('.api-key-hint').text()).toContain('sk-****7890');
  });

  it('编辑模式保存调用 updateVendor 并 emit saved', async () => {
    vi.mocked(updateVendor).mockResolvedValue(vendor);
    const wrapper = await openDialog({ vendor });

    // 编辑模式基础信息已填充，模型行已有
    const submit = wrapper.find('.btn-submit');
    expect((submit.element as HTMLButtonElement).disabled).toBe(false);

    await submit.trigger('click');
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(updateVendor).toHaveBeenCalledWith('vendor-001', expect.objectContaining({ name: '火山引擎' }));
    expect(wrapper.emitted('saved')).toBeTruthy();
  });

  it('点击取消 emit close', async () => {
    const wrapper = await openDialog();
    await wrapper.find('.btn-cancel').trigger('click');
    expect(wrapper.emitted('close')).toBeTruthy();
  });
});
