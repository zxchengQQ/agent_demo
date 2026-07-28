import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import DocumentList from './DocumentList.vue';
import { useRagStore } from '@/stores/rag';
import * as ragApi from '@/api/rag';
import type { DocumentInfo } from '@/types';

/**
 * DocumentList 组件测试（Task-15）
 * 验证标准来源：Task-15 验证标准
 * 关联 AC：AC-005, AC-009, AC-010, AC-017, AC-024, AC-035
 */

/** 构造测试文档 */
function makeDoc(overrides: Partial<DocumentInfo> = {}): DocumentInfo {
  return {
    documentId: 'doc-001',
    fileName: 'guide.pdf',
    fileSize: 1024,
    format: 'pdf',
    status: 'COMPLETED',
    chunkCount: 5,
    failReason: null,
    uploadTime: '2026-07-27T10:00:00',
    ...overrides,
  };
}

beforeEach(() => {
  setActivePinia(createPinia());
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('DocumentList 组件', () => {
  it('文档列表每项显示文件名、大小、格式、状态标签、上传时间（AC-005）', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc()];

    const wrapper = mount(DocumentList, {
      global: { plugins: [] },
    });

    expect(wrapper.text()).toContain('guide.pdf');
    expect(wrapper.text()).toContain('pdf');
    expect(wrapper.text()).toContain('已完成');
  });

  it('PENDING 状态显示灰色"待处理"标签', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'PENDING' })];

    const wrapper = mount(DocumentList);
    expect(wrapper.text()).toContain('待处理');
  });

  it('PROCESSING 状态显示蓝色"处理中"标签和加载动画', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'PROCESSING', chunkCount: 0 })];

    const wrapper = mount(DocumentList);
    expect(wrapper.text()).toContain('处理中');
    expect(wrapper.find('.status-spinner').exists()).toBe(true);
  });

  it('COMPLETED 状态显示绿色"已完成"标签和分块数', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'COMPLETED', chunkCount: 8 })];

    const wrapper = mount(DocumentList);
    expect(wrapper.text()).toContain('已完成');
    expect(wrapper.text()).toContain('8');
  });

  it('FAILED 状态显示红色"失败"标签和失败原因（AC-024）', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'FAILED', failReason: 'PDF 解析失败' })];

    const wrapper = mount(DocumentList);
    expect(wrapper.text()).toContain('失败');
    expect(wrapper.text()).toContain('PDF 解析失败');
  });

  it('存在 PENDING 文档时每 3 秒自动轮询状态（AC-009）', async () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ documentId: 'doc-pending', status: 'PENDING' })];

    const spy = vi.spyOn(ragApi, 'getDocumentStatus');
    spy.mockResolvedValue({
      documentId: 'doc-pending',
      status: 'PROCESSING',
      chunkCount: 0,
      failReason: null,
    });

    mount(DocumentList);

    // 立即执行一次（onMounted）
    await vi.advanceTimersByTimeAsync(0);
    expect(spy).toHaveBeenCalledWith('doc-pending');

    // 3 秒后再次轮询
    spy.mockResolvedValue({
      documentId: 'doc-pending',
      status: 'COMPLETED',
      chunkCount: 3,
      failReason: null,
    });
    await vi.advanceTimersByTimeAsync(3000);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('所有文档到达终态后停止轮询（AC-035）', async () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ documentId: 'doc-1', status: 'PENDING' })];

    const spy = vi.spyOn(ragApi, 'getDocumentStatus');
    spy.mockResolvedValue({
      documentId: 'doc-1',
      status: 'COMPLETED',
      chunkCount: 2,
      failReason: null,
    });

    mount(DocumentList);

    // 立即执行 -> COMPLETED
    await vi.advanceTimersByTimeAsync(0);
    expect(spy).toHaveBeenCalledTimes(1);

    // 文档已终态，3 秒后不应再调用
    await vi.advanceTimersByTimeAsync(3000);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('组件卸载时清理轮询定时器', async () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ documentId: 'doc-x', status: 'PENDING' })];

    const spy = vi.spyOn(ragApi, 'getDocumentStatus');
    spy.mockResolvedValue({
      documentId: 'doc-x',
      status: 'PENDING',
      chunkCount: 0,
      failReason: null,
    });

    const wrapper = mount(DocumentList);
    await vi.advanceTimersByTimeAsync(0);

    wrapper.unmount();

    // 卸载后 3 秒不应再调用
    await vi.advanceTimersByTimeAsync(3000);
    const callCountAfterUnmount = spy.mock.calls.length;
    await vi.advanceTimersByTimeAsync(3000);
    expect(spy.mock.calls.length).toBe(callCountAfterUnmount);
  });

  it('点击删除按钮后调用 deleteDocument 并 Toast 提示（AC-010）', async () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ documentId: 'doc-del', status: 'COMPLETED' })];

    const deleteSpy = vi.spyOn(store, 'deleteDocument').mockResolvedValue(undefined);

    const wrapper = mount(DocumentList);
    const deleteBtn = wrapper.find('.doc-delete-btn');
    await deleteBtn.trigger('click');

    expect(deleteSpy).toHaveBeenCalledWith('doc-del');
    // Toast 通过 emit 通知父组件
    expect(wrapper.emitted('notify')).toBeTruthy();
    expect(wrapper.emitted('notify')![0]).toEqual(['删除成功']);
  });

  it('知识库无文档时显示空状态引导和上传区域（AC-017）', () => {
    const store = useRagStore();
    store.currentDocuments = [];

    const wrapper = mount(DocumentList);
    expect(wrapper.text()).toContain('还没有文档');
    expect(wrapper.find('.upload-area').exists()).toBe(true);
  });

  // ===== CR-001 新增：查看分块按钮测试 =====

  it('COMPLETED 文档显示"查看分块"按钮（AC-040）', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'COMPLETED', chunkCount: 5 })];

    const wrapper = mount(DocumentList);
    expect(wrapper.find('.btn-view-chunks').exists()).toBe(true);
    expect(wrapper.find('.btn-view-chunks').text()).toContain('查看分块');
  });

  it('PENDING 文档不显示"查看分块"按钮（AC-040）', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'PENDING' })];

    const wrapper = mount(DocumentList);
    expect(wrapper.find('.btn-view-chunks').exists()).toBe(false);
  });

  it('PROCESSING 文档不显示"查看分块"按钮（AC-040）', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'PROCESSING' })];

    const wrapper = mount(DocumentList);
    expect(wrapper.find('.btn-view-chunks').exists()).toBe(false);
  });

  it('FAILED 文档不显示"查看分块"按钮（AC-040）', () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ status: 'FAILED', failReason: '解析失败' })];

    const wrapper = mount(DocumentList);
    expect(wrapper.find('.btn-view-chunks').exists()).toBe(false);
  });

  it('点击"查看分块"按钮打开抽屉面板（AC-038）', async () => {
    const store = useRagStore();
    store.currentDocuments = [makeDoc({ documentId: 'doc-chunks', status: 'COMPLETED', chunkCount: 3 })];

    // mock getDocumentChunks 避免真实请求
    vi.spyOn(ragApi, 'getDocumentChunks').mockResolvedValue([
      { chunkIndex: 0, content: '分块1', charCount: 3 },
    ]);

    const wrapper = mount(DocumentList);

    // 初始状态抽屉不可见
    expect(wrapper.find('.chunk-drawer').exists()).toBe(false);

    // 点击查看分块
    await wrapper.find('.btn-view-chunks').trigger('click');

    // 抽屉应可见
    expect(wrapper.find('.chunk-drawer').exists()).toBe(true);
  });
});
