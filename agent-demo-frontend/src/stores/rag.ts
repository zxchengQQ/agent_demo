import { defineStore } from 'pinia';
import type { KnowledgeBase, DocumentInfo, DocumentStatus, DocumentChunk } from '@/types';
import {
  createKnowledgeBase as apiCreateKnowledgeBase,
  listKnowledgeBases as apiListKnowledgeBases,
  deleteKnowledgeBase as apiDeleteKnowledgeBase,
  uploadDocument as apiUploadDocument,
  listDocuments as apiListDocuments,
  deleteDocument as apiDeleteDocument,
  getDocumentChunks as apiGetDocumentChunks,
} from '@/api/rag';

/**
 * RAG 知识库状态管理
 * 关联 AC：AC-003、AC-004、AC-005、AC-006、AC-007、AC-009、AC-010
 *
 * 业务含义：作为知识库与文档列表在各组件间共享的唯一数据源，
 * 封装与后端 /api/rag/* 的交互，组件只需调用 action 即可完成增删查改。
 * loading 在异步操作期间为 true，供 UI 层展示加载态（AC-027）。
 */

export const useRagStore = defineStore('rag', {
  state: () => ({
    /** 知识库列表 */
    knowledgeBases: [] as KnowledgeBase[],
    /** 当前选中的知识库 ID */
    currentKnowledgeBaseId: '' as string,
    /** 当前知识库的文档列表 */
    currentDocuments: [] as DocumentInfo[],
    /** 当前查看的文档分块列表（CR-001 新增） */
    currentChunks: [] as DocumentChunk[],
    /** 加载状态：异步操作期间为 true，完成后为 false */
    loading: false as boolean,
  }),

  actions: {
    /**
     * 加载知识库列表（AC-003）
     */
    async loadKnowledgeBases() {
      this.loading = true;
      try {
        this.knowledgeBases = await apiListKnowledgeBases();
      } finally {
        this.loading = false;
      }
    },

    /**
     * 创建知识库，成功后插入列表头部并选中（AC-004）
     * 业务含义：新建后立即设为当前选中，便于用户直接上传文档。
     */
    async createKnowledgeBase(name: string, description: string) {
      this.loading = true;
      try {
        const kb = await apiCreateKnowledgeBase(name, description);
        this.knowledgeBases.unshift(kb);
        this.currentKnowledgeBaseId = kb.id;
      } finally {
        this.loading = false;
      }
    },

    /**
     * 选中知识库并加载其文档列表（AC-005）
     */
    async selectKnowledgeBase(id: string) {
      this.currentKnowledgeBaseId = id;
      this.loading = true;
      try {
        this.currentDocuments = await apiListDocuments(id);
      } finally {
        this.loading = false;
      }
    },

    /**
     * 删除知识库（AC-006）
     * 业务含义：若删除的是当前选中项，清空 currentKnowledgeBaseId 和文档列表，
     * 避免组件继续引用已失效的知识库上下文。
     */
    async deleteKnowledgeBase(id: string) {
      this.loading = true;
      try {
        await apiDeleteKnowledgeBase(id);
        this.knowledgeBases = this.knowledgeBases.filter((k) => k.id !== id);
        if (this.currentKnowledgeBaseId === id) {
          this.currentKnowledgeBaseId = '';
          this.currentDocuments = [];
        }
      } finally {
        this.loading = false;
      }
    },

    /**
     * 上传文档到当前知识库，成功后插入文档列表头部（AC-007）
     * 业务含义：新文档通常处于 PENDING 状态，置顶便于用户观察处理进度。
     */
    async uploadDocument(file: File) {
      this.loading = true;
      try {
        const doc = await apiUploadDocument(this.currentKnowledgeBaseId, file);
        this.currentDocuments.unshift(doc);
      } finally {
        this.loading = false;
      }
    },

    /**
     * 删除文档（AC-010）
     */
    async deleteDocument(documentId: string) {
      this.loading = true;
      try {
        await apiDeleteDocument(documentId);
        this.currentDocuments = this.currentDocuments.filter(
          (d) => d.documentId !== documentId,
        );
      } finally {
        this.loading = false;
      }
    },

    /**
     * 更新文档状态（轮询用，AC-009）
     * 业务含义：组件轮询 getDocumentStatus 后，将最新状态回填到本地列表，
     * 驱动 UI 刷新处理进度。同步方法，不触发网络请求。
     */
    updateDocumentStatus(
      documentId: string,
      status: DocumentStatus,
      chunkCount: number,
      failReason: string | null,
    ) {
      const doc = this.currentDocuments.find((d) => d.documentId === documentId);
      if (doc) {
        doc.status = status;
        doc.chunkCount = chunkCount;
        doc.failReason = failReason;
      }
    },

    /**
     * 加载文档分块列表（CR-001 新增，AC-038）
     * 业务含义：供前端分块详情抽屉面板调用，加载失败时清空 currentChunks。
     */
    async loadDocumentChunks(documentId: string) {
      try {
        this.currentChunks = await apiGetDocumentChunks(documentId);
      } catch {
        this.currentChunks = [];
      }
    },
  },
});
