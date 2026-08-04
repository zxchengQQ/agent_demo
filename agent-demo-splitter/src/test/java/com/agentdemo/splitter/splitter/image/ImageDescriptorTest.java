package com.agentdemo.splitter.splitter.image;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ImageDescriptor 单元测试（CR-002 新增）
 * <p>
 * 验证图片描述生成器调用视觉 ChatModel 生成文本描述，并在失败时降级返回 null。
 * 对应 AC：AC-022（生成描述）、AC-024（失败跳过不影响文档处理）
 * </p>
 */
class ImageDescriptorTest {

    private ImageDescriptor descriptor;
    private ChatModel visionChatModel;

    @TempDir
    Path tempDir;

    /** Logback ListAppender 用于捕获 WARN 日志（验证失败场景的日志输出） */
    private ListAppender<ILoggingEvent> logAppender;
    private Logger descriptorLogger;

    @BeforeEach
    void setUp() {
        descriptor = new ImageDescriptor();
        visionChatModel = mock(ChatModel.class);

        // 注入 Logback Appender 捕获日志
        logAppender = new ListAppender<>();
        logAppender.start();
        descriptorLogger = (Logger) LoggerFactory.getLogger(ImageDescriptor.class);
        descriptorLogger.addAppender(logAppender);
    }

    /** 辅助：在临时目录创建一个 PNG 图片用于测试 */
    private Path createTestImage(String fileName) throws Exception {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Path imagePath = tempDir.resolve(fileName);
        ImageIO.write(img, "png", imagePath.toFile());
        return imagePath;
    }

    // ========== 正常流程（Happy Path）==========

    /** AC-022：给定有效图片路径和可用的 ChatModel，describe 返回非空文本描述 */
    @Test
    void 有效图片路径和可用ChatModel返回非空描述() throws Exception {
        Path imagePath = createTestImage("page1.png");
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(imagePath.toString())
                .pageNumber(1)
                .imageIndex(0)
                .build();
        // Mock ChatModel 返回包含描述的响应
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("这是一张包含图表的图片，展示 2024 年销售数据"))
                .build();
        when(visionChatModel.chat(any(List.class))).thenReturn(mockResponse);

        String description = descriptor.describe(imageInfo, visionChatModel);

        assertNotNull(description, "有效图片应返回非空描述");
        assertFalse(description.isEmpty(), "描述不应为空字符串");
        verify(visionChatModel, times(1)).chat(any(List.class));
    }

    /** AC-022：视觉模型返回的描述涵盖图片可见信息（验证描述内容） */
    @Test
    void 描述内容应反映视觉模型返回的文本() throws Exception {
        Path imagePath = createTestImage("page2.png");
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(imagePath.toString())
                .pageNumber(2)
                .imageIndex(0)
                .build();
        String expectedDescription = "图片包含一个柱状图，显示 Q1-Q4 季度销售额，最高值为 500 万";
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from(expectedDescription))
                .build();
        when(visionChatModel.chat(any(List.class))).thenReturn(mockResponse);

        String description = descriptor.describe(imageInfo, visionChatModel);

        assertEquals(expectedDescription, description, "描述应反映视觉模型返回的文本");
    }

    // ========== 边界与异常（Edge & Error Cases）==========

    /** AC-024：视觉模型抛异常时 describe 返回 null（不向上抛出） */
    @Test
    void 视觉模型抛异常时返回null不抛出() throws Exception {
        Path imagePath = createTestImage("page3.png");
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(imagePath.toString())
                .pageNumber(3)
                .imageIndex(0)
                .build();
        when(visionChatModel.chat(any(List.class)))
                .thenThrow(new RuntimeException("视觉模型服务不可用"));

        String description = descriptor.describe(imageInfo, visionChatModel);

        assertNull(description, "视觉模型异常时应返回 null，不向上抛出");
    }

    /** AC-024：视觉模型失败时记录 WARN 日志 */
    @Test
    void 视觉模型失败时记录WARN日志() throws Exception {
        Path imagePath = createTestImage("page4.png");
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(imagePath.toString())
                .pageNumber(4)
                .imageIndex(0)
                .build();
        when(visionChatModel.chat(any(List.class)))
                .thenThrow(new RuntimeException("API 超时"));

        descriptor.describe(imageInfo, visionChatModel);

        // 验证至少有一条 WARN 级别日志
        List<ILoggingEvent> warnLogs = logAppender.list.stream()
                .filter(e -> e.getLevel().toString().equals("WARN"))
                .toList();
        assertFalse(warnLogs.isEmpty(), "视觉模型失败时应记录 WARN 日志");
    }

    /** AC-024：图片文件不存在时返回 null（不抛异常） */
    @Test
    void 图片文件不存在时返回null() {
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath("/nonexistent/path/page5.png")
                .pageNumber(5)
                .imageIndex(0)
                .build();

        String description = descriptor.describe(imageInfo, visionChatModel);

        assertNull(description, "图片文件不存在时应返回 null");
        // 不应调用 ChatModel
        verifyNoInteractions(visionChatModel);
    }

    /** 边界：ImageInfo 为 null 时返回 null（防御式编程） */
    @Test
    void imageInfo为null时返回null() {
        String description = descriptor.describe(null, visionChatModel);

        assertNull(description, "ImageInfo 为 null 时应返回 null");
        verifyNoInteractions(visionChatModel);
    }

    /** 边界：imagePath 为 null 时返回 null */
    @Test
    void imagePath为null时返回null() {
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(null)
                .pageNumber(1)
                .imageIndex(0)
                .build();

        String description = descriptor.describe(imageInfo, visionChatModel);

        assertNull(description, "imagePath 为 null 时应返回 null");
        verifyNoInteractions(visionChatModel);
    }

    /** 边界：ChatModel 为 null 时返回 null（视觉模型未配置场景） */
    @Test
    void chatModel为null时返回null() throws Exception {
        Path imagePath = createTestImage("page6.png");
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(imagePath.toString())
                .pageNumber(6)
                .imageIndex(0)
                .build();

        String description = descriptor.describe(imageInfo, null);

        assertNull(description, "ChatModel 为 null 时应返回 null（视觉模型未配置）");
    }

    /** 边界：图片文件为目录而非文件时返回 null */
    @Test
    void imagePath为目录时返回null() {
        ImageInfo imageInfo = ImageInfo.builder()
                .imagePath(tempDir.toString())
                .pageNumber(1)
                .imageIndex(0)
                .build();

        String description = descriptor.describe(imageInfo, visionChatModel);

        assertNull(description, "imagePath 指向目录时应返回 null");
        verifyNoInteractions(visionChatModel);
    }
}
