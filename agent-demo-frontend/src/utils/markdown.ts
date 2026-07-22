import { marked } from 'marked';
import DOMPurify from 'dompurify';

/**
 * Markdown 渲染封装
 * 关联 AC：AC-023（助手消息 Markdown 渲染 + XSS 防护）
 *
 * 业务含义：助手正式回复按 Markdown 格式渲染（代码块、列表、表格等），
 * 渲染后经 DOMPurify 净化防止 XSS 攻击。用户消息保持纯文本不渲染。
 */

/** 允许的 HTML 标签白名单（覆盖常用 Markdown 语法） */
const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'em', 'code', 'pre',
  'ul', 'ol', 'li',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'a', 'blockquote',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'hr',
];

/** 允许的 HTML 属性白名单 */
const ALLOWED_ATTR = ['href', 'src', 'alt', 'class', 'language'];

/**
 * 将 Markdown 文本渲染为安全的 HTML
 *
 * @param content Markdown 原文
 * @returns 净化后的 HTML 字符串（已过滤 XSS 风险）
 */
export function renderMarkdown(content: string): string {
  const html = marked.parse(content, { async: false }) as string;
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR });
}
