// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia, type Pinia } from 'pinia';

// Mock RAG API 避免 KnowledgeBaseList 测试发起真实 API 调用
vi.mock('@/api/rag', () => ({
  listKnowledgeBases: vi.fn().mockResolvedValue([]),
  createKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn().mockResolvedValue(undefined),
  uploadDocument: vi.fn(),
  listDocuments: vi.fn().mockResolvedValue([]),
  deleteDocument: vi.fn(),
  getDocumentStatus: vi.fn(),
}));

import KnowledgeBaseList from '@/components/KnowledgeBaseList.vue';
import { useRagStore } from '@/stores/rag';
import type { KnowledgeBase } from '@/types';

/**
 * KnowledgeBaseList 组件测试（Task-12）
 * 验证标准来源：Task-12 验证标准
 * 关联 AC：AC-003, AC-005, AC-006, AC-016, AC-026, AC-036
 */
describe('KnowledgeBaseList', () => {
  let pinia: Pinia;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.clearAllMocks();
  });

  /** 辅助：构造测试用知识库列表（按创建时间正序，组件应倒序显示） */
  function createKnowledgeBases(): KnowledgeBase[] {
    return [
      { id: '1', name: '产品手册', description: '', documentCount: 5, createTime: '2025-01-01T00:00:00Z' },
      { id: '2', name: '常见问题', description: '', documentCount: 3, createTime: '2025-01-02T00:00:00Z' },
      { id: '3', name: '开发文档', description: '', documentCount: 0, createTime: '2025-01-03T00:00:00Z' },
    ];
  }

  it('知识库列表按创建时间倒序，每项显示名称、文档数、创建时间（AC-003）', () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    const items = wrapper.findAll('.kb-item');
    expect(items).toHaveLength(3);
    // 倒序：开发文档（01-03）> 常见问题（01-02）> 产品手册（01-01）
    expect(items[0].text()).toContain('开发文档');
    expect(items[1].text()).toContain('常见问题');
    expect(items[2].text()).toContain('产品手册');
    // 每项显示文档数
    expect(items[0].text()).toContain('0');
    expect(items[1].text()).toContain('3');
    expect(items[2].text()).toContain('5');
    // 每项显示创建时间
    expect(items[0].text()).toContain('2025-01-03');
    expect(items[1].text()).toContain('2025-01-02');
    expect(items[2].text()).toContain('2025-01-01');
  });

  it('点击知识库项时高亮选中并触发 select（AC-005）', async () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    // 点击第一项（开发文档，id='3'）
    await wrapper.findAll('.kb-item')[0].trigger('click');

    // store currentKnowledgeBaseId 被设置
    expect(ragStore.currentKnowledgeBaseId).toBe('3');
    // 该项高亮
    expect(wrapper.findAll('.kb-item')[0].classes()).toContain('active');
    // emit select 事件
    expect(wrapper.emitted('select')).toBeTruthy();
  });

  it('点击删除按钮弹出确认框，显示级联删除数量（AC-006）', async () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    // 点击第一项的删除按钮（开发文档，0 个文档）
    await wrapper.findAll('.btn-delete')[0].trigger('click');

    // 确认框显示
    expect(wrapper.find('.confirm-dialog').exists()).toBe(true);
    // 显示级联删除数量
    expect(wrapper.find('.confirm-dialog').text()).toContain('0');
    expect(wrapper.find('.confirm-dialog').text()).toContain('不可恢复');
  });

  it('确认删除后调用 deleteKnowledgeBase，列表移除该项（AC-006）', async () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    // 点击第一项的删除按钮（开发文档，id='3'）
    await wrapper.findAll('.btn-delete')[0].trigger('click');
    // 确认删除
    await wrapper.find('.btn-confirm').trigger('click');
    // 等待异步操作完成
    await new Promise((resolve) => setTimeout(resolve, 50));

    // 验证 API 被调用
    const { deleteKnowledgeBase } = await import('@/api/rag');
    expect(deleteKnowledgeBase).toHaveBeenCalledWith('3');
    // 列表移除该项
    expect(ragStore.knowledgeBases).toHaveLength(2);
    expect(ragStore.knowledgeBases.find((k) => k.id === '3')).toBeUndefined();
  });

  it('取消删除后确认框关闭，列表不变（AC-006）', async () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    const initialCount = wrapper.findAll('.kb-item').length;

    // 点击删除
    await wrapper.findAll('.btn-delete')[0].trigger('click');
    expect(wrapper.find('.confirm-dialog').exists()).toBe(true);

    // 取消
    await wrapper.find('.btn-cancel').trigger('click');
    expect(wrapper.find('.confirm-dialog').exists()).toBe(false);

    // 列表不变
    expect(wrapper.findAll('.kb-item').length).toBe(initialCount);
  });

  it('知识库列表为空时显示空状态引导 + 新建入口（AC-026）', () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = [];

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    expect(wrapper.find('.empty-state').exists()).toBe(true);
    expect(wrapper.find('.empty-state').text()).toContain('暂无知识库');
    // 空状态中有新建入口
    expect(wrapper.find('.empty-state .btn-create').exists()).toBe(true);
  });

  it('点击新建知识库按钮触发 create 事件（AC-036）', async () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    await wrapper.find('.btn-create').trigger('click');
    expect(wrapper.emitted('create')).toBeTruthy();
  });

  it('当前选中的知识库项有 active 样式（AC-016）', () => {
    const ragStore = useRagStore();
    ragStore.knowledgeBases = createKnowledgeBases();
    // 预设选中第二项（常见问题，id='2'）
    ragStore.currentKnowledgeBaseId = '2';

    const wrapper = mount(KnowledgeBaseList, { global: { plugins: [pinia] } });
    const items = wrapper.findAll('.kb-item');
    // 倒序后第二项是常见问题
    expect(items[1].classes()).toContain('active');
    expect(items[0].classes()).not.toContain('active');
    expect(items[2].classes()).not.toContain('active');
  });
});
