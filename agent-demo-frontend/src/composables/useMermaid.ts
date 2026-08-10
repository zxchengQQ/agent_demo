import { nextTick, type Ref } from 'vue';
import mermaid from 'mermaid';

/**
 * Mermaid 图表渲染 composable
 * <p>
 * 业务含义：检测助手消息中的 Mermaid 代码块（```mermaid），
 * 将其渲染为 SVG 图表，同时提供"渲染图/源码"切换功能。
 * 仅在消息流式传输完成后渲染，避免每个 token 触发重绘。
 * </p>
 */

/** Mermaid 是否已初始化 */
let initialized = false;

/** 渲染 ID 自增计数器，确保每个图表有唯一 ID */
let renderIdCounter = 0;

/** 初始化 Mermaid（暗色主题，匹配项目 "Refined Dark Tech" 风格） */
function initMermaid() {
  if (initialized) return;
  mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    themeVariables: {
      background: 'transparent',
      primaryColor: '#1a1a2e',
      primaryTextColor: '#e8e8f0',
      primaryBorderColor: '#00d9ff',
      lineColor: '#8888a0',
      secondaryColor: '#16162a',
      tertiaryColor: '#252540',
      fontFamily: 'JetBrains Mono, Fira Code, Consolas, monospace',
      fontSize: '14px',
    },
    flowchart: { curve: 'basis', useMaxWidth: true },
    sequence: { useMaxWidth: true },
    gantt: { useMaxWidth: true },
  });
  initialized = true;
}

/** 切换按钮 HTML */
const TOOLBAR_HTML = `
  <div class="mermaid-toolbar">
    <button class="mermaid-btn mermaid-btn--active" data-mode="render">渲染图</button>
    <button class="mermaid-btn" data-mode="source">源码</button>
  </div>
`;

/**
 * 增强容器内的所有 Mermaid 代码块
 * <p>
 * 查找 <pre><code class="language-mermaid">...</code></pre>，
 * 替换为带工具栏的容器，支持渲染图/源码切换。
 * </p>
 *
 * @param container 包含渲染后 Markdown 的 DOM 容器
 */
export async function enhanceMermaidBlocks(container: HTMLElement): Promise<void> {
  initMermaid();

  // 查找所有 Mermaid 代码块
  const mermaidBlocks = container.querySelectorAll<HTMLPreElement>(
    'pre > code.language-mermaid'
  );

  for (const codeEl of mermaidBlocks) {
    const preEl = codeEl.closest('pre');
    if (!preEl) continue;
    // 跳已增强的块
    if (preEl.parentElement?.classList.contains('mermaid-block')) continue;

    const source = codeEl.textContent || '';
    const blockId = `mermaid-${++renderIdCounter}`;

    // 创建容器
    const wrapper = document.createElement('div');
    wrapper.className = 'mermaid-block';
    wrapper.innerHTML = `
      ${TOOLBAR_HTML}
      <div class="mermaid-content">
        <div class="mermaid-diagram" data-mermaid-id="${blockId}">
          <div class="mermaid-loading">渲染中...</div>
        </div>
        <pre class="mermaid-source" style="display:none"><code>${escapeHtml(source)}</code></pre>
      </div>
    `;

    // 替换原始 <pre>
    preEl.parentElement?.replaceChild(wrapper, preEl);

    // 显式设置初始显示状态（比 HTML style 属性更可靠）
    const diagramEl = wrapper.querySelector('.mermaid-diagram') as HTMLElement;
    const sourceEl = wrapper.querySelector('.mermaid-source') as HTMLElement;
    if (diagramEl && sourceEl) {
      sourceEl.style.display = 'none';
    }

    // 渲染 Mermaid 图表
    try {
      const { svg } = await mermaid.render(blockId, source);
      if (diagramEl) {
        diagramEl.innerHTML = svg;
      }
    } catch (err: unknown) {
      // 渲染失败：显示错误提示 + 自动切换到源码
      if (diagramEl) {
        const errMsg = err instanceof Error ? err.message : String(err);
        diagramEl.innerHTML = `<div class="mermaid-error">图表渲染失败: ${escapeHtml(errMsg)}</div>`;
      }
      // 自动切换到源码模式
      switchMode(wrapper, 'source');
    }

    // 绑定切换按钮事件
    const buttons = wrapper.querySelectorAll<HTMLButtonElement>('.mermaid-btn');
    buttons.forEach((btn) => {
      btn.addEventListener('click', () => {
        const mode = (btn.dataset.mode || 'render') as 'render' | 'source';
        switchMode(wrapper, mode);
      });
    });
  }
}

/** 切换渲染图/源码模式 */
function switchMode(wrapper: HTMLElement, mode: 'render' | 'source') {
  const diagram = wrapper.querySelector('.mermaid-diagram') as HTMLElement;
  const source = wrapper.querySelector('.mermaid-source') as HTMLElement;
  const buttons = wrapper.querySelectorAll<HTMLButtonElement>('.mermaid-btn');

  buttons.forEach((btn) => {
    btn.classList.toggle('mermaid-btn--active', btn.dataset.mode === mode);
  });

  if (diagram && source) {
    diagram.style.display = mode === 'render' ? '' : 'none';
    source.style.display = mode === 'source' ? '' : 'none';
  }
}

/** HTML 转义 */
function escapeHtml(text: string): string {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

/**
 * Vue composable：监听内容变化，自动增强 Mermaid 块
 *
 * @param containerRef 容器元素引用
 * @param isStreaming 是否正在流式传输（流式时跳过渲染）
 */
export function useMermaid(
  containerRef: Ref<HTMLElement | null>,
  isStreaming: () => boolean
) {
  const triggerEnhance = async () => {
    if (isStreaming()) return;
    await nextTick();
    const container = containerRef.value;
    if (!container) return;
    await enhanceMermaidBlocks(container);
  };

  return { triggerEnhance };
}
