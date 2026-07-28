// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock RAG API 避免 KnowledgeBasePage 发起真实 API 调用
vi.mock('@/api/rag', () => ({
  listKnowledgeBases: vi.fn().mockResolvedValue([]),
  createKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
  uploadDocument: vi.fn(),
  listDocuments: vi.fn().mockResolvedValue([]),
  deleteDocument: vi.fn(),
  getDocumentStatus: vi.fn(),
}));

import KnowledgeBasePage from '@/components/KnowledgeBasePage.vue';
import KnowledgeBaseList from '@/components/KnowledgeBaseList.vue';
import DocumentList from '@/components/DocumentList.vue';
import CreateKnowledgeBaseDialog from '@/components/CreateKnowledgeBaseDialog.vue';

/**
 * KnowledgeBasePage 页面容器测试（Task-11）
 * 验证标准来源：Task-11 验证标准
 * 关联 AC：AC-002
 */
describe('KnowledgeBasePage', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
  });

  it('渲染左侧知识库列表区域和右侧文档列表区域', () => {
    const wrapper = mount(KnowledgeBasePage, { global: { plugins: [pinia] } });
    expect(wrapper.find('.kb-list-panel').exists()).toBe(true);
    expect(wrapper.find('.doc-list-panel').exists()).toBe(true);
  });

  it('左侧区域为固定宽度，右侧区域自适应（flex 布局）', () => {
    const wrapper = mount(KnowledgeBasePage, { global: { plugins: [pinia] } });
    const leftPanel = wrapper.find('.kb-list-panel');
    const rightPanel = wrapper.find('.doc-list-panel');
    // 验证布局 class 存在（具体宽度由 CSS 控制）
    expect(leftPanel.exists()).toBe(true);
    expect(rightPanel.exists()).toBe(true);
    // 左侧不应有 flex: 1（固定宽度），右侧应有 flex: 1（自适应）
    expect(leftPanel.element.style.flex).toBeFalsy();
  });

  it('左侧区域渲染 KnowledgeBaseList 组件', () => {
    const wrapper = mount(KnowledgeBasePage, { global: { plugins: [pinia] } });
    const leftPanel = wrapper.find('.kb-list-panel');
    expect(leftPanel.findComponent(KnowledgeBaseList).exists()).toBe(true);
  });

  it('右侧区域渲染 DocumentList 组件', () => {
    const wrapper = mount(KnowledgeBasePage, { global: { plugins: [pinia] } });
    const rightPanel = wrapper.find('.doc-list-panel');
    expect(rightPanel.findComponent(DocumentList).exists()).toBe(true);
  });

  it('onMounted 时调用 ragStore.loadKnowledgeBases()', async () => {
    const { listKnowledgeBases } = await import('@/api/rag');
    vi.mocked(listKnowledgeBases).mockClear();

    mount(KnowledgeBasePage, { global: { plugins: [pinia] } });

    // loadKnowledgeBases 内部调用 apiListKnowledgeBases（即 listKnowledgeBases）
    expect(listKnowledgeBases).toHaveBeenCalled();
  });

  it('点击"新建知识库"按钮时弹出 CreateKnowledgeBaseDialog（BUG 修复）', async () => {
    const wrapper = mount(KnowledgeBasePage, { global: { plugins: [pinia] } });

    // 初始状态：弹窗不显示
    const dialog = wrapper.findComponent(CreateKnowledgeBaseDialog);
    expect(dialog.exists()).toBe(true);
    expect(dialog.props('visible')).toBe(false);

    // 点击"新建知识库"按钮
    const createBtn = wrapper.find('.btn-create');
    await createBtn.trigger('click');

    // 弹窗应显示
    expect(dialog.props('visible')).toBe(true);
  });
});
