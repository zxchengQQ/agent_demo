package com.agentdemo.app.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 测试用 TokenStream 真实实现
 * <p>
 * 业务含义：避免 mock TokenStream 链式 stubbing 的 UnfinishedStubbing 问题，
 * 通过真实实现精确控制回调触发时机。
 * </p>
 */
public class TestTokenStream implements TokenStream {

    private final AtomicReference<Consumer<String>> partialHandler = new AtomicReference<>();
    private final AtomicReference<Consumer<ChatResponse>> completeHandler = new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> errorHandler = new AtomicReference<>();
    private final AtomicReference<Consumer<List<Content>>> retrievedHandler = new AtomicReference<>();
    private final AtomicReference<Consumer<ToolExecution>> toolExecutedHandler = new AtomicReference<>();
    private final List<String> queuedTokens = new ArrayList<>();
    private final AtomicReference<String> queuedFullText = new AtomicReference<>();
    private boolean started = false;

    @Override
    public TokenStream onPartialResponse(Consumer<String> tokenHandler) {
        partialHandler.set(tokenHandler);
        return this;
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> contentHandler) {
        retrievedHandler.set(contentHandler);
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecutedHandler) {
        this.toolExecutedHandler.set(toolExecutedHandler);
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> completionHandler) {
        completeHandler.set(completionHandler);
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> errorHandler) {
        this.errorHandler.set(errorHandler);
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        return this;
    }

    @Override
    public void start() {
        started = true;
        // 启动时立即触发已排队的 token 片段
        queuedTokens.forEach(t -> {
            Consumer<String> h = partialHandler.get();
            if (h != null) {
                h.accept(t);
            }
        });
        // 若已设置完整文本，立即触发完成回调
        String fullText = queuedFullText.get();
        if (fullText != null) {
            completeWith(fullText);
        }
    }

    /** 触发一个 token 片段 */
    public void emitToken(String token) {
        if (started) {
            Consumer<String> h = partialHandler.get();
            if (h != null) {
                h.accept(token);
            }
        } else {
            queuedTokens.add(token);
        }
    }

    /** 触发流式完成 */
    public void completeWith(String fullText) {
        Consumer<ChatResponse> h = completeHandler.get();
        if (h == null) {
            // 尚未注册回调，缓存文本待 start() 后触发
            queuedFullText.set(fullText);
            return;
        }
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(fullText))
                .build();
        h.accept(response);
    }

    /** 触发流式错误 */
    public void failWith(Throwable error) {
        Consumer<Throwable> h = errorHandler.get();
        if (h != null) {
            h.accept(error);
        }
    }

    /** 获取已注册的完成回调（供测试手动触发） */
    public Consumer<ChatResponse> getCompleteHandler() {
        return completeHandler.get();
    }

    /** 获取已注册的错误回调（供测试手动触发） */
    public Consumer<Throwable> getErrorHandler() {
        return errorHandler.get();
    }
}
