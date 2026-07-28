import type { KnowledgeBase, DocumentInfo, DocumentStatusResponse, DocumentChunk } from '@/types';

/**
 * RAG 知识库 API 封装
 * 关联 AC：AC-003, AC-004, AC-005, AC-006, AC-007, AC-009, AC-010, AC-027
 *
 * 业务含义：封装后端 /api/rag/* 接口，统一处理 Result<T> 返回结构。
 * 成功时返回 data 字段，失败时抛出 Error（含后端错误消息或网络异常提示）。
 */

const API_BASE = '/api/rag';

/** 后端 Result<T> 返回结构 */
interface Result<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
  traceId: string;
}

/**
 * 统一请求封装：解析 Result<T> 结构，失败抛异常
 * 业务含义：所有 RAG API 共用的请求方法，统一处理 HTTP 错误和业务错误。
 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);

  // HTTP 状态码非 2xx（网络层错误）
  if (!response.ok) {
    const errorResult = await response.json().catch(() => null);
    throw new Error(errorResult?.message || '网络异常，请稍后重试');
  }

  // 解析 Result<T> 结构
  const result: Result<T> = await response.json();

  // 业务错误（success=false）
  if (!result.success) {
    throw new Error(result.message || '操作失败');
  }

  return result.data;
}

/** 创建知识库（AC-004） */
export async function createKnowledgeBase(name: string, description: string): Promise<KnowledgeBase> {
  return request<KnowledgeBase>(`${API_BASE}/knowledges`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  });
}

/** 查询知识库列表（AC-003） */
export async function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return request<KnowledgeBase[]>(`${API_BASE}/knowledges`);
}

/** 删除知识库（级联删除文档和向量数据，AC-006） */
export async function deleteKnowledgeBase(id: string): Promise<void> {
  await request<void>(`${API_BASE}/knowledges/${id}`, { method: 'DELETE' });
}

/** 上传文档到指定知识库（AC-007） */
export async function uploadDocument(knowledgeBaseId: string, file: File): Promise<DocumentInfo> {
  const formData = new FormData();
  formData.append('file', file);
  return request<DocumentInfo>(`${API_BASE}/knowledges/${knowledgeBaseId}/documents`, {
    method: 'POST',
    body: formData,
  });
}

/** 查询知识库下文档列表（AC-005） */
export async function listDocuments(knowledgeBaseId: string): Promise<DocumentInfo[]> {
  return request<DocumentInfo[]>(`${API_BASE}/knowledges/${knowledgeBaseId}/documents`);
}

/** 查询文档处理状态（供轮询，AC-009） */
export async function getDocumentStatus(documentId: string): Promise<DocumentStatusResponse> {
  return request<DocumentStatusResponse>(`${API_BASE}/documents/${documentId}/status`);
}

/** 删除文档（同步删除向量数据，AC-010） */
export async function deleteDocument(documentId: string): Promise<void> {
  await request<void>(`${API_BASE}/documents/${documentId}`, { method: 'DELETE' });
}

/** 查询文档分块列表（CR-001 新增，AC-038） */
export async function getDocumentChunks(documentId: string): Promise<DocumentChunk[]> {
  return request<DocumentChunk[]>(`${API_BASE}/documents/${documentId}/chunks`);
}
