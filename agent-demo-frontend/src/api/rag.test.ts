// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  createKnowledgeBase,
  listKnowledgeBases,
  deleteKnowledgeBase,
  uploadDocument,
  listDocuments,
  getDocumentStatus,
  deleteDocument,
  getDocumentChunks,
} from './rag';
import type { KnowledgeBase, DocumentInfo, DocumentStatusResponse, DocumentChunk } from '@/types';

/**
 * RAG API 封装测试
 * 验证标准来源：Task-02 验证标准
 * 关联 AC：AC-003, AC-004, AC-005, AC-006, AC-007, AC-009, AC-010, AC-027
 */

/** mock fetch 的辅助方法，构造 Result<T> 响应 */
function mockFetchSuccess<T>(data: T): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: true, code: 200, message: '成功', data, traceId: 'test' }),
  } as Response);
}

/** mock fetch 失败响应（后端返回业务错误） */
function mockFetchBusinessError(message: string): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: true,
    json: async () => ({ success: false, code: 5307, message, data: null, traceId: 'test' }),
  } as Response);
}

/** mock fetch 网络错误（response.ok=false，无 JSON body） */
function mockFetchNetworkError(): void {
  vi.mocked(global.fetch).mockResolvedValueOnce({
    ok: false,
    json: async () => { throw new Error('parse error'); },
  } as Response);
}

beforeEach(() => {
  vi.restoreAllMocks();
  global.fetch = vi.fn();
});

describe('RAG API 封装', () => {
  it('createKnowledgeBase 发送 POST 请求并返回 KnowledgeBase（AC-004）', async () => {
    const mockKb: KnowledgeBase = {
      id: 'kb-001', name: '测试库', description: '描述',
      documentCount: 0, createTime: '2026-07-27T10:00:00',
    };
    mockFetchSuccess(mockKb);

    const result = await createKnowledgeBase('测试库', '描述');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/rag/knowledges',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '测试库', description: '描述' }),
      }),
    );
    expect(result).toEqual(mockKb);
  });

  it('listKnowledgeBases 发送 GET 请求并返回数组（AC-003）', async () => {
    const mockList: KnowledgeBase[] = [
      { id: 'kb-001', name: '库A', description: '', documentCount: 2, createTime: '2026-07-27T10:00:00' },
    ];
    mockFetchSuccess(mockList);

    const result = await listKnowledgeBases();
    expect(global.fetch).toHaveBeenCalledWith('/api/rag/knowledges', undefined);
    expect(result).toEqual(mockList);
  });

  it('deleteKnowledgeBase 发送 DELETE 请求（AC-006）', async () => {
    mockFetchSuccess(null);
    await deleteKnowledgeBase('kb-001');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/rag/knowledges/kb-001',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('uploadDocument 发送 POST multipart 请求并返回 DocumentInfo（AC-007）', async () => {
    const mockDoc: DocumentInfo = {
      documentId: 'doc-001', fileName: 'test.txt', fileSize: 100,
      format: 'txt', status: 'PENDING', chunkCount: 0,
      failReason: null, uploadTime: '2026-07-27T10:00:00',
    };
    mockFetchSuccess(mockDoc);

    const file = new File(['content'], 'test.txt', { type: 'text/plain' });
    const result = await uploadDocument('kb-001', file);
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/rag/knowledges/kb-001/documents',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(result).toEqual(mockDoc);
  });

  it('listDocuments 发送 GET 请求并返回数组（AC-005）', async () => {
    const mockDocs: DocumentInfo[] = [];
    mockFetchSuccess(mockDocs);
    const result = await listDocuments('kb-001');
    expect(global.fetch).toHaveBeenCalledWith('/api/rag/knowledges/kb-001/documents', undefined);
    expect(result).toEqual(mockDocs);
  });

  it('getDocumentStatus 发送 GET 请求并返回状态（AC-009）', async () => {
    const mockStatus: DocumentStatusResponse = {
      documentId: 'doc-001', status: 'COMPLETED', chunkCount: 5, failReason: null,
    };
    mockFetchSuccess(mockStatus);
    const result = await getDocumentStatus('doc-001');
    expect(global.fetch).toHaveBeenCalledWith('/api/rag/documents/doc-001/status', undefined);
    expect(result).toEqual(mockStatus);
  });

  it('deleteDocument 发送 DELETE 请求（AC-010）', async () => {
    mockFetchSuccess(null);
    await deleteDocument('doc-001');
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/rag/documents/doc-001',
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('getDocumentChunks 发送 GET 请求并返回分块列表（AC-038）', async () => {
    const mockChunks: DocumentChunk[] = [
      { chunkIndex: 0, content: '第一个分块', charCount: 5 },
      { chunkIndex: 1, content: '第二个分块', charCount: 5 },
    ];
    mockFetchSuccess(mockChunks);

    const result = await getDocumentChunks('doc-001');
    expect(global.fetch).toHaveBeenCalledWith('/api/rag/documents/doc-001/chunks', undefined);
    expect(result).toEqual(mockChunks);
    expect(result).toHaveLength(2);
    expect(result[0].chunkIndex).toBe(0);
  });

  it('getDocumentChunks 文档无分块时返回空数组（AC-041）', async () => {
    mockFetchSuccess([]);

    const result = await getDocumentChunks('doc-empty');
    expect(result).toEqual([]);
  });

  it('后端返回业务错误时抛出异常（AC-027）', async () => {
    mockFetchBusinessError('知识库名称已存在');
    await expect(createKnowledgeBase('重复名', '')).rejects.toThrow('知识库名称已存在');
  });

  it('网络请求失败时抛出异常（AC-027）', async () => {
    mockFetchNetworkError();
    await expect(listKnowledgeBases()).rejects.toThrow('网络异常，请稍后重试');
  });
});
