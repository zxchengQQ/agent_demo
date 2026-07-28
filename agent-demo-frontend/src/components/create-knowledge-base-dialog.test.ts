// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock RAG API
vi.mock('@/api/rag', () => ({
  listKnowledgeBases: vi.fn().mockResolvedValue([]),
  createKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
  uploadDocument: vi.fn(),
  listDocuments: vi.fn().mockResolvedValue([]),
  deleteDocument: vi.fn(),
  getDocumentStatus: vi.fn(),
}));

import CreateKnowledgeBaseDialog from '@/components/CreateKnowledgeBaseDialog.vue';
import type { KnowledgeBase } from '@/types';

/**
 * CreateKnowledgeBaseDialog 组件测试（Task-13）
 * 验证标准来源：Task-13 验证标准
 * 关联 AC：AC-004, AC-018, AC-019, AC-020, AC-021, AC-030, AC-031, AC-032
 */
describe('CreateKnowledgeBaseDialog', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
  });

  /** 辅助：构造创建成功后返回的知识库对象 */
  function createMockKb(name: string, description: string): KnowledgeBase {
    return {
      id: 'new-1',
      name,
      description,
      documentCount: 0,
      createTime: '2025-01-01T00:00:00Z',
    };
  }

  it('名称为空时确定按钮禁用（AC-019）', () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    expect(wrapper.find('.btn-submit').attributes('disabled')).toBeDefined();
  });

  it('名称超 50 字符时实时提示，确定禁用（AC-020）', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    // 输入 51 字符的超长名称
    const longName = 'a'.repeat(51);
    await wrapper.find('.name-input').setValue(longName);

    // 显示错误提示
    expect(wrapper.find('.name-error').text()).toContain('50');
    // 确定按钮禁用
    expect(wrapper.find('.btn-submit').attributes('disabled')).toBeDefined();
  });

  it('名称含非法字符时实时提示，确定禁用（AC-019）', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    // 输入含空格和特殊字符的名称
    await wrapper.find('.name-input').setValue('测试@知识库');

    // 显示非法字符提示
    expect(wrapper.find('.name-error').text()).toContain('仅允许');
    expect(wrapper.find('.name-error').text()).toContain('中英文');
    expect(wrapper.find('.name-error').text()).toContain('数字');
    // 确定按钮禁用
    expect(wrapper.find('.btn-submit').attributes('disabled')).toBeDefined();
  });

  it('描述超 200 字符时实时提示并显示字数计数（AC-021）', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    // 先输入合法名称使确定按钮可用
    await wrapper.find('.name-input').setValue('测试知识库');

    // 输入 201 字符的超长描述
    const longDesc = 'a'.repeat(201);
    await wrapper.find('.desc-input').setValue(longDesc);

    // 显示字数计数
    expect(wrapper.find('.char-count').text()).toContain('201');
    expect(wrapper.find('.char-count').text()).toContain('200');
    // 显示错误提示
    expect(wrapper.find('.desc-error').text()).toContain('200');
    // 确定按钮禁用
    expect(wrapper.find('.btn-submit').attributes('disabled')).toBeDefined();
  });

  it('提交合法数据后调用 createKnowledgeBase，成功后弹窗关闭（AC-004）', async () => {
    const { createKnowledgeBase } = await import('@/api/rag');
    const mockKb = createMockKb('测试知识库', '测试描述');
    vi.mocked(createKnowledgeBase).mockResolvedValue(mockKb);

    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });

    // 输入合法数据
    await wrapper.find('.name-input').setValue('测试知识库');
    await wrapper.find('.desc-input').setValue('测试描述');

    // 提交
    await wrapper.find('.btn-submit').trigger('click');
    // 等待异步操作完成
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 验证 API 被调用
    expect(createKnowledgeBase).toHaveBeenCalledWith('测试知识库', '测试描述');
    // emit created 和 update:visible
    expect(wrapper.emitted('created')).toBeTruthy();
    expect(wrapper.emitted('update:visible')).toBeTruthy();
    expect(wrapper.emitted('update:visible')![0]).toEqual([false]);
  });

  it('后端返回名称重复错误时弹窗内提示，弹窗不关闭（AC-030）', async () => {
    const { createKnowledgeBase } = await import('@/api/rag');
    vi.mocked(createKnowledgeBase).mockRejectedValue(new Error('知识库名称已存在'));

    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });

    // 输入合法数据
    await wrapper.find('.name-input').setValue('重复名称');
    await wrapper.find('.desc-input').setValue('');

    // 提交
    await wrapper.find('.btn-submit').trigger('click');
    // 等待异步操作完成
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 弹窗内显示错误信息
    expect(wrapper.find('.dialog-error').text()).toContain('已存在');
    // 弹窗不关闭（未 emit update:visible）
    expect(wrapper.emitted('update:visible')).toBeFalsy();
    // 未 emit created
    expect(wrapper.emitted('created')).toBeFalsy();
  });

  it('合法名称（中英文+数字+下划线+连字符）确定按钮可用（AC-019）', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    await wrapper.find('.name-input').setValue('测试-KB_2025');
    expect(wrapper.find('.btn-submit').attributes('disabled')).toBeUndefined();
  });

  it('描述字数计数正确显示（AC-031）', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    await wrapper.find('.desc-input').setValue('四字描述');
    expect(wrapper.find('.char-count').text()).toContain('4');
    expect(wrapper.find('.char-count').text()).toContain('200');
  });

  it('点击取消按钮关闭弹窗（AC-018）', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { visible: true },
      global: { plugins: [pinia] },
    });
    await wrapper.find('.btn-cancel').trigger('click');
    expect(wrapper.emitted('update:visible')).toBeTruthy();
    expect(wrapper.emitted('update:visible')![0]).toEqual([false]);
  });
});
