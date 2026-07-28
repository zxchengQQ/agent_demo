import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

/**
 * RAG 知识库状态管理测试
 * 验证标准来源：Task-04 验证标准（共 8 项）
 * 关联 AC：AC-003、AC-004、AC-005、AC-006、AC-007、AC-009、AC-010
 *
 * 测试策略：mock '../api/rag' 模块所有导出函数，仅验证 store 的 state 流转，
 * 不依赖真实网络请求。
 */

// mock API 模块：所有 RAG API 在测试中被替换为 vi.fn()，避免真实请求
vi.mock('../api/rag', () => ({
  createKnowledgeBase: vi.fn(),
  listKnowledgeBases: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
  uploadDocument: vi.fn(),
  listDocuments: vi.fn(),
  getDocumentStatus: vi.fn(),
  deleteDocument: vi.fn(),
  getDocumentChunks: vi.fn(),
}));

import { useRagStore } from './rag';
import {
  createKnowledgeBase,
  listKnowledgeBases,
  deleteKnowledgeBase,
  uploadDocument,
  listDocuments,
  deleteDocument,
  getDocumentChunks,
} from '../api/rag';
import type { KnowledgeBase, DocumentInfo, DocumentChunk } from '@/types';

/** 知识库 mock 数据 */
const mockKb: KnowledgeBase = {
  id: 'kb-1',
  name: '知识库1',
  description: '描述1',
  documentCount: 0,
  createTime: '2024-01-01T00:00:00Z',
};

/** 文档 mock 数据 */
const mockDoc: DocumentInfo = {
  documentId: 'doc-1',
  fileName: 'test.pdf',
  fileSize: 1024,
  format: 'pdf',
  status: 'COMPLETED',
  chunkCount: 5,
  failReason: null,
  uploadTime: '2024-01-01T00:00:00Z',
};

describe('Rag Store', () => {
  beforeEach(() => {
    // 每个用例独立的 Pinia 实例，避免 state 串扰
    setActivePinia(createPinia());
    // 清理 mock 调用记录和返回值，避免用例间相互影响
    vi.clearAllMocks();
  });

  // ===== 验证标准 1：loadKnowledgeBases 填充 knowledgeBases =====
  it('loadKnowledgeBases 调用后 knowledgeBases 填充为数组', async () => {
    vi.mocked(listKnowledgeBases).mockResolvedValue([mockKb]);
    const store = useRagStore();

    await store.loadKnowledgeBases();

    expect(store.knowledgeBases).toHaveLength(1);
    expect(store.knowledgeBases[0].id).toBe('kb-1');
  });

  // ===== 验证标准 8：loading 在异步操作期间为 true，完成后为 false =====
  it('loading 在异步操作期间为 true，完成后为 false', async () => {
    vi.mocked(listKnowledgeBases).mockResolvedValue([mockKb]);
    const store = useRagStore();

    // 调用但不 await，此时 action 内部已同步设置 loading=true
    const promise = store.loadKnowledgeBases();
    expect(store.loading).toBe(true);

    await promise;
    expect(store.loading).toBe(false);
  });

  // ===== 验证标准 2：createKnowledgeBase 插入头部且更新 currentKnowledgeBaseId =====
  it('createKnowledgeBase 新知识库插入列表头部且更新 currentKnowledgeBaseId', async () => {
    const newKb: KnowledgeBase = {
      id: 'kb-new',
      name: '新库',
      description: '描述',
      documentCount: 0,
      createTime: '2024-01-02T00:00:00Z',
    };
    vi.mocked(createKnowledgeBase).mockResolvedValue(newKb);
    const store = useRagStore();
    // 预置已有知识库，验证新库插入头部
    store.knowledgeBases = [mockKb];

    await store.createKnowledgeBase('新库', '描述');

    expect(store.knowledgeBases[0].id).toBe('kb-new');
    expect(store.knowledgeBases).toHaveLength(2);
    expect(store.currentKnowledgeBaseId).toBe('kb-new');
  });

  // ===== 验证标准 3：selectKnowledgeBase 更新 ID 且加载文档 =====
  it('selectKnowledgeBase 更新 currentKnowledgeBaseId 且加载 currentDocuments', async () => {
    vi.mocked(listDocuments).mockResolvedValue([mockDoc]);
    const store = useRagStore();

    await store.selectKnowledgeBase('kb-123');

    expect(store.currentKnowledgeBaseId).toBe('kb-123');
    expect(store.currentDocuments).toHaveLength(1);
    expect(store.currentDocuments[0].documentId).toBe('doc-1');
  });

  // ===== 验证标准 4：deleteKnowledgeBase 移除；删当前项则清空 =====
  it('deleteKnowledgeBase 删除当前选中项后，currentKnowledgeBaseId 清空', async () => {
    vi.mocked(deleteKnowledgeBase).mockResolvedValue(undefined);
    const store = useRagStore();
    const kb2: KnowledgeBase = {
      id: 'kb-2',
      name: '库2',
      description: '',
      documentCount: 0,
      createTime: '2024-01-01T00:00:00Z',
    };
    store.knowledgeBases = [mockKb, kb2];
    store.currentKnowledgeBaseId = 'kb-1';

    await store.deleteKnowledgeBase('kb-1');

    expect(store.knowledgeBases.find((k) => k.id === 'kb-1')).toBeUndefined();
    expect(store.currentKnowledgeBaseId).toBe('');
  });

  it('deleteKnowledgeBase 删除非选中项不影响 currentKnowledgeBaseId', async () => {
    vi.mocked(deleteKnowledgeBase).mockResolvedValue(undefined);
    const store = useRagStore();
    const kb2: KnowledgeBase = {
      id: 'kb-2',
      name: '库2',
      description: '',
      documentCount: 0,
      createTime: '2024-01-01T00:00:00Z',
    };
    store.knowledgeBases = [mockKb, kb2];
    store.currentKnowledgeBaseId = 'kb-1';

    await store.deleteKnowledgeBase('kb-2');

    expect(store.knowledgeBases.find((k) => k.id === 'kb-2')).toBeUndefined();
    expect(store.currentKnowledgeBaseId).toBe('kb-1');
  });

  // ===== 验证标准 5：uploadDocument 新文档插入 currentDocuments 头部 =====
  it('uploadDocument 新文档插入 currentDocuments 头部', async () => {
    const newDoc: DocumentInfo = {
      documentId: 'doc-new',
      fileName: 'new.pdf',
      fileSize: 2048,
      format: 'pdf',
      status: 'PENDING',
      chunkCount: 0,
      failReason: null,
      uploadTime: '2024-01-02T00:00:00Z',
    };
    vi.mocked(uploadDocument).mockResolvedValue(newDoc);
    const store = useRagStore();
    store.currentKnowledgeBaseId = 'kb-1';
    store.currentDocuments = [mockDoc];
    const file = new File(['content'], 'new.pdf', { type: 'application/pdf' });

    await store.uploadDocument(file);

    expect(store.currentDocuments[0].documentId).toBe('doc-new');
    expect(store.currentDocuments).toHaveLength(2);
  });

  // ===== 验证标准 6：deleteDocument 从 currentDocuments 移除 =====
  it('deleteDocument 从 currentDocuments 移除对应文档', async () => {
    vi.mocked(deleteDocument).mockResolvedValue(undefined);
    const store = useRagStore();
    const doc2: DocumentInfo = {
      documentId: 'doc-2',
      fileName: 'b.pdf',
      fileSize: 0,
      format: 'pdf',
      status: 'COMPLETED',
      chunkCount: 0,
      failReason: null,
      uploadTime: '2024-01-01T00:00:00Z',
    };
    store.currentDocuments = [mockDoc, doc2];

    await store.deleteDocument('doc-1');

    expect(store.currentDocuments.find((d) => d.documentId === 'doc-1')).toBeUndefined();
    expect(store.currentDocuments).toHaveLength(1);
  });

  // ===== 验证标准 7：updateDocumentStatus 更新文档状态（轮询用）=====
  it('updateDocumentStatus 更新对应文档状态为 COMPLETED', () => {
    const store = useRagStore();
    store.currentDocuments = [
      {
        ...mockDoc,
        documentId: 'doc-456',
        status: 'PROCESSING',
        chunkCount: 0,
        failReason: null,
      },
    ];

    store.updateDocumentStatus('doc-456', 'COMPLETED', 5, null);

    const doc = store.currentDocuments.find((d) => d.documentId === 'doc-456');
    expect(doc?.status).toBe('COMPLETED');
    expect(doc?.chunkCount).toBe(5);
    expect(doc?.failReason).toBe(null);
  });

  // ===== CR-001 新增：loadDocumentChunks =====
  it('loadDocumentChunks 调用后 currentChunks 填充为分块列表', async () => {
    const mockChunks: DocumentChunk[] = [
      { chunkIndex: 0, content: '分块1', charCount: 3 },
      { chunkIndex: 1, content: '分块2', charCount: 3 },
    ];
    vi.mocked(getDocumentChunks).mockResolvedValue(mockChunks);
    const store = useRagStore();

    await store.loadDocumentChunks('doc-1');

    expect(getDocumentChunks).toHaveBeenCalledWith('doc-1');
    expect(store.currentChunks).toHaveLength(2);
    expect(store.currentChunks[0].chunkIndex).toBe(0);
  });

  it('loadDocumentChunks 加载失败时 currentChunks 为空数组', async () => {
    vi.mocked(getDocumentChunks).mockRejectedValue(new Error('网络异常'));
    const store = useRagStore();

    await store.loadDocumentChunks('doc-fail');

    expect(store.currentChunks).toEqual([]);
  });
});
