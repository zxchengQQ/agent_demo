import { describe, it, expect } from 'vitest';
import { renderMarkdown } from './markdown';

/**
 * Markdown 渲染封装测试
 * 验证标准来源：T-25 验证标准
 * 关联 AC：AC-023（助手消息 Markdown 渲染 + XSS 防护）
 */

describe('renderMarkdown', () => {
  it('标题语法渲染为 h1 标签（AC-023）', () => {
    const html = renderMarkdown('# 标题');
    expect(html).toContain('<h1>标题</h1>');
  });

  it('代码块渲染为 pre>code 结构（AC-023）', () => {
    const html = renderMarkdown('```python\nprint("x")\n```');
    expect(html).toContain('<pre><code');
  });

  it('无序列表渲染为 ul>li 结构（AC-023）', () => {
    const html = renderMarkdown('- a\n- b');
    expect(html).toContain('<ul>');
    expect(html).toContain('<li>');
  });

  it('XSS 防护：script 标签被清除（AC-023）', () => {
    const html = renderMarkdown('<script>alert(1)</script>');
    expect(html).not.toContain('<script>');
  });

  it('XSS 防护：事件处理器属性被清除（AC-023）', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)>');
    expect(html).not.toContain('onerror');
  });
});
