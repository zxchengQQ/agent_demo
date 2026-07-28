// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import DocumentUploader from '@/components/DocumentUploader.vue';

// Mock rag store，仅 mock uploadDocument action（AC-007）
const mockUploadDocument = vi.fn();
vi.mock('@/stores/rag', () => ({
  useRagStore: () => ({
    uploadDocument: mockUploadDocument,
  }),
}));

/**
 * DocumentUploader 组件测试（Task-14）
 * 验证标准来源：Task-14 验证标准
 * 关联 AC：AC-007、AC-008、AC-022、AC-023、AC-025、AC-033、AC-034
 */

/** 辅助：构造指定大小的 File 对象（用 Object.defineProperty 避免分配大内存） */
function createFile(name: string, size: number = 1024): File {
  const file = new File(['x'], name);
  Object.defineProperty(file, 'size', { value: size, configurable: true });
  return file;
}

/** 10MB 边界值 */
const MAX_SIZE = 10 * 1024 * 1024;

describe('DocumentUploader', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mockUploadDocument.mockClear();
    mockUploadDocument.mockResolvedValue(undefined);
  });

  it('拖拽文件到上传区域时区域高亮反馈', async () => {
    const wrapper = mount(DocumentUploader);
    const area = wrapper.find('.upload-area');
    // 初始无高亮
    expect(area.classes()).not.toContain('dragover');
    // 拖拽进入时高亮
    await area.trigger('dragenter');
    expect(area.classes()).toContain('dragover');
  });

  it('拖拽离开后取消高亮', async () => {
    const wrapper = mount(DocumentUploader);
    const area = wrapper.find('.upload-area');
    await area.trigger('dragenter');
    expect(area.classes()).toContain('dragover');
    await area.trigger('dragleave');
    expect(area.classes()).not.toContain('dragover');
  });

  it('拖拽 .txt 文件释放后调用 uploadDocument', async () => {
    const file = createFile('test.txt', 100);
    const wrapper = mount(DocumentUploader);
    await wrapper.find('.upload-area').trigger('drop', {
      dataTransfer: { files: [file] },
    });
    await flushPromises();
    expect(mockUploadDocument).toHaveBeenCalledWith(file);
  });

  it('同时选择 3 个文件（txt/md/pdf）时逐个上传，各自独立', async () => {
    const txtFile = createFile('a.txt', 100);
    const mdFile = createFile('b.md', 100);
    const pdfFile = createFile('c.pdf', 100);
    const wrapper = mount(DocumentUploader);
    const input = wrapper.find('input[type="file"]');
    Object.defineProperty(input.element, 'files', {
      value: [txtFile, mdFile, pdfFile],
      configurable: true,
    });
    await input.trigger('change');
    await flushPromises();
    expect(mockUploadDocument).toHaveBeenCalledTimes(3);
    expect(mockUploadDocument).toHaveBeenNthCalledWith(1, txtFile);
    expect(mockUploadDocument).toHaveBeenNthCalledWith(2, mdFile);
    expect(mockUploadDocument).toHaveBeenNthCalledWith(3, pdfFile);
  });

  it('文件大小 15MB 时 Toast 提示"文件大小不能超过 10MB"，不发起请求', async () => {
    const bigFile = createFile('big.txt', 15 * 1024 * 1024);
    const wrapper = mount(DocumentUploader);
    await wrapper.find('.upload-area').trigger('drop', {
      dataTransfer: { files: [bigFile] },
    });
    await flushPromises();
    expect(mockUploadDocument).not.toHaveBeenCalled();
    expect(wrapper.emitted('notify')).toBeTruthy();
    expect(wrapper.emitted('notify')![0][0]).toBe('文件大小不能超过 10MB');
  });

  it('文件扩展名 .docx 时 Toast 提示"仅支持 txt、md、pdf 格式"，不发起请求', async () => {
    const docxFile = createFile('doc.docx', 100);
    const wrapper = mount(DocumentUploader);
    await wrapper.find('.upload-area').trigger('drop', {
      dataTransfer: { files: [docxFile] },
    });
    await flushPromises();
    expect(mockUploadDocument).not.toHaveBeenCalled();
    expect(wrapper.emitted('notify')).toBeTruthy();
    expect(wrapper.emitted('notify')![0][0]).toBe('仅支持 txt、md、pdf 格式');
  });

  it('批量上传 3 个文件（1 个超大、1 个不支持格式、1 个合法）时，仅合法文件上传成功', async () => {
    const bigFile = createFile('big.txt', 15 * 1024 * 1024);
    const docxFile = createFile('doc.docx', 100);
    const validFile = createFile('valid.txt', 100);
    const wrapper = mount(DocumentUploader);
    await wrapper.find('.upload-area').trigger('drop', {
      dataTransfer: { files: [bigFile, docxFile, validFile] },
    });
    await flushPromises();
    // 仅合法文件被上传
    expect(mockUploadDocument).toHaveBeenCalledTimes(1);
    expect(mockUploadDocument).toHaveBeenCalledWith(validFile);
  });

  it('点击上传区域可触发文件选择对话框', async () => {
    const wrapper = mount(DocumentUploader);
    const input = wrapper.find('input[type="file"]');
    const clickSpy = vi.spyOn(input.element, 'click');
    await wrapper.find('.upload-area').trigger('click');
    expect(clickSpy).toHaveBeenCalled();
  });

  it('渲染隐藏的 input[type=file] 且配置 multiple 和 accept', () => {
    const wrapper = mount(DocumentUploader);
    const input = wrapper.find('input[type="file"]');
    expect(input.exists()).toBe(true);
    expect(input.attributes('multiple')).toBeDefined();
    expect(input.attributes('accept')).toBe('.txt,.md,.pdf');
  });

  it('上传失败时 Toast 提示错误信息', async () => {
    mockUploadDocument.mockRejectedValue(new Error('网络异常'));
    const file = createFile('test.txt', 100);
    const wrapper = mount(DocumentUploader);
    await wrapper.find('.upload-area').trigger('drop', {
      dataTransfer: { files: [file] },
    });
    await flushPromises();
    expect(wrapper.emitted('notify')).toBeTruthy();
    expect(wrapper.emitted('notify')![0][0]).toBe('网络异常');
  });
});
