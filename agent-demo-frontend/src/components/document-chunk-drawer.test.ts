// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock RAG API
vi.mock('@/api/rag', () => ({
  getDocumentChunks: vi.fn(),
  listKnowledgeBases: vi.fn().mockResolvedValue([]),
  createKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
  uploadDocument: vi.fn(),
  listDocuments: vi.fn().mockResolvedValue([]),
  deleteDocument: vi.fn(),
  getDocumentStatus: vi.fn(),
}));

import DocumentChunkDrawer from '@/components/DocumentChunkDrawer.vue';
import { getDocumentChunks } from '@/api/rag';
import type { DocumentChunk } from '@/types';

/**
 * DocumentChunkDrawer 组件测试（Task-20，CR-001）
 * 验证标准来源：Task-20 验证标准
 * 关联 AC：AC-038, AC-039, AC-041, AC-042
 */
describe('DocumentChunkDrawer', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
  });

  /** 辅助：构造分块列表 */
  function createMockChunks(count: number): DocumentChunk[] {
    return Array.from({ length: count }, (_, i) => ({
      chunkIndex: i,
      content: `这是第${i + 1}个分块的文本内容，用于测试展示。`,
      charCount: 20 + i,
    }));
  }

  /** 辅助：挂载组件 */
  function mountDrawer(props: Partial<{ visible: boolean; documentId: string; chunkCount: number }> = {}) {
    return mount(DocumentChunkDrawer, {
      props: {
        visible: true,
        documentId: 'doc-001',
        chunkCount: 3,
        ...props,
      },
      global: { plugins: [pinia] },
    });
  }

  it('visible 为 true 时抽屉展示（AC-038）', () => {
    vi.mocked(getDocumentChunks).mockResolvedValue(createMockChunks(3));
    const wrapper = mountDrawer({ visible: true });
    expect(wrapper.find('.chunk-drawer').exists()).toBe(true);
  });

  it('visible 为 false 时抽屉不展示', () => {
    vi.mocked(getDocumentChunks).mockResolvedValue(createMockChunks(3));
    const wrapper = mountDrawer({ visible: false });
    expect(wrapper.find('.chunk-drawer').exists()).toBe(false);
  });

  it('加载完成后展示分块列表，每个分块显示索引和字符数（AC-038）', async () => {
    const chunks = createMockChunks(3);
    vi.mocked(getDocumentChunks).mockResolvedValue(chunks);
    const wrapper = mountDrawer();

    await new Promise((resolve) => setTimeout(resolve, 50));

    const items = wrapper.findAll('.chunk-item');
    expect(items).toHaveLength(3);
    // 验证第一个分块显示索引 "分块 1/3"
    expect(wrapper.find('.chunk-index').text()).toContain('1');
    expect(wrapper.find('.chunk-index').text()).toContain('3');
    // 验证字符数显示
    expect(wrapper.find('.chunk-char-count').text()).toContain('20');
  });

  it('分块内容超过截断阈值时显示展开按钮（AC-039）', async () => {
    const longContent = 'a'.repeat(250);
    vi.mocked(getDocumentChunks).mockResolvedValue([
      { chunkIndex: 0, content: longContent, charCount: 250 },
    ]);
    const wrapper = mountDrawer();

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(wrapper.find('.btn-expand').exists()).toBe(true);
    expect(wrapper.find('.btn-expand').text()).toContain('展开');
  });

  it('点击展开按钮显示完整内容，点击收起恢复截断（AC-039）', async () => {
    const longContent = 'a'.repeat(250);
    vi.mocked(getDocumentChunks).mockResolvedValue([
      { chunkIndex: 0, content: longContent, charCount: 250 },
    ]);
    const wrapper = mountDrawer();

    await new Promise((resolve) => setTimeout(resolve, 50));

    // 点击展开
    await wrapper.find('.btn-expand').trigger('click');
    expect(wrapper.find('.chunk-content').text()).toContain('a'.repeat(250));
    expect(wrapper.find('.btn-expand').text()).toContain('收起');

    // 点击收起
    await wrapper.find('.btn-expand').trigger('click');
    expect(wrapper.find('.btn-content-truncated').exists()).toBe(true);
    expect(wrapper.find('.btn-expand').text()).toContain('展开');
  });

  it('分块列表为空时显示空状态提示（AC-041）', async () => {
    vi.mocked(getDocumentChunks).mockResolvedValue([]);
    const wrapper = mountDrawer();

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(wrapper.find('.empty-state').exists()).toBe(true);
    expect(wrapper.find('.empty-state').text()).toContain('无分块数据');
  });

  it('加载中显示 loading 状态', async () => {
    vi.mocked(getDocumentChunks).mockReturnValue(new Promise(() => {})); // 永不 resolve
    const wrapper = mountDrawer();

    // 加载中应显示 loading
    expect(wrapper.find('.loading-state').exists()).toBe(true);
  });

  it('点击关闭按钮 emit update:visible=false', async () => {
    vi.mocked(getDocumentChunks).mockResolvedValue(createMockChunks(3));
    const wrapper = mountDrawer();

    await new Promise((resolve) => setTimeout(resolve, 50));

    await wrapper.find('.btn-close').trigger('click');
    expect(wrapper.emitted('update:visible')).toBeTruthy();
    expect(wrapper.emitted('update:visible')![0]).toEqual([false]);
  });

  it('加载失败时 Toast 提示并关闭抽屉（AC-042）', async () => {
    vi.mocked(getDocumentChunks).mockRejectedValue(new Error('网络异常'));
    const wrapper = mountDrawer();

    await new Promise((resolve) => setTimeout(resolve, 50));

    // 应 emit update:visible=false（关闭抽屉）
    expect(wrapper.emitted('update:visible')).toBeTruthy();
    expect(wrapper.emitted('update:visible')![0]).toEqual([false]);
  });

  it('visible 从 true 变为 false 时清空分块数据', async () => {
    vi.mocked(getDocumentChunks).mockResolvedValue(createMockChunks(3));
    const wrapper = mountDrawer({ visible: true });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wrapper.findAll('.chunk-item')).toHaveLength(3);

    // 切换为不可见
    await wrapper.setProps({ visible: false });
    // 再次切为可见，数据应重新加载
    vi.mocked(getDocumentChunks).mockResolvedValue(createMockChunks(2));
    await wrapper.setProps({ visible: true });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wrapper.findAll('.chunk-item')).toHaveLength(2);
  });
});
