package com.agentdemo.splitter.splitter.image;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;

/**
 * 图片描述生成器（CR-002 新增）
 * <p>
 * 业务含义：调用视觉 ChatModel 为 PDF 提取的图片生成文本描述，描述内容涵盖图片中可见的信息
 * （文字、图表、图形等）。生成的描述作为独立分块向量化入库，使 PDF 中的图片信息可通过文字检索命中。
 * </p>
 * <p>
 * 容错策略（AC-024）：视觉模型调用失败、图片读取失败、参数缺失等场景均返回 null，
 * 不向上抛出异常，确保图片处理不影响主文档流程（文本分块、向量化、入库）。
 * </p>
 * <p>
 * 依赖设计：遵循依赖倒置原则，splitter 模块不直接依赖 agent-demo-llm 模块。
 * ChatModel 实例由调用方（DocumentService）通过 ModelFactory.getVisionChatModel() 获取后传入，
 * 使 ImageDescriptor 可与任意 ChatModel 实现解耦。
 * </p>
 */
@Slf4j
@Component
public class ImageDescriptor {

    /**
     * 图片描述提示词
     * 业务含义：引导视觉模型生成详尽且面向检索的描述（包含文字、图表、图形等可见信息）
     */
    private static final String DESCRIBE_PROMPT =
            "请详细描述这张图片的内容，包括图片中的文字、图表数据、图形元素等所有可见信息。" +
                    "描述应简洁准确，便于后续通过文字检索到这张图片。";

    /**
     * 为图片生成文本描述
     * <p>
     * 业务含义：读取图片文件，转 base64 编码，构造包含文本提示词和图片的 UserMessage，
     * 调用视觉 ChatModel，返回模型生成的描述文本。
     * </p>
     * <p>
     * 容错行为：以下场景返回 null（不抛异常）：
     * - ImageInfo 为 null
     * - imagePath 为 null 或空
     * - 图片文件不存在或为目录
     * - ChatModel 为 null（视觉模型未配置）
     * - 图片读取失败
     * - 视觉模型调用失败
     * </p>
     *
     * @param imageInfo       图片信息（含路径、页码、索引）
     * @param visionChatModel 视觉对话模型实例（由调用方通过 ModelFactory.getVisionChatModel() 获取）
     * @return 图片描述文本；任何异常场景返回 null
     */
    public String describe(ImageInfo imageInfo, ChatModel visionChatModel) {
        // 防御式校验：参数缺失
        if (imageInfo == null) {
            log.debug("ImageInfo 为 null，跳过图片描述生成");
            return null;
        }
        String imagePath = imageInfo.getImagePath();
        if (imagePath == null || imagePath.isEmpty()) {
            log.debug("imagePath 为空，跳过图片描述生成: pageNumber={}", imageInfo.getPageNumber());
            return null;
        }
        if (visionChatModel == null) {
            log.debug("视觉 ChatModel 为 null（未配置），跳过图片描述生成: imagePath={}", imagePath);
            return null;
        }

        // 校验文件存在性
        Path path = Path.of(imagePath);
        if (!Files.isRegularFile(path)) {
            log.warn("图片文件不存在或不是文件，跳过图片描述生成: imagePath={}", imagePath);
            return null;
        }

        try {
            // 读取图片并转 base64
            String base64Data = encodeImageToBase64(path);
            String mimeType = resolveMimeType(imagePath);

            // 构造包含文本提示和图片的 UserMessage
            Image image = Image.builder()
                    .base64Data(base64Data)
                    .mimeType(mimeType)
                    .build();
            UserMessage userMessage = UserMessage.from(
                    TextContent.from(DESCRIBE_PROMPT),
                    ImageContent.from(image)
            );

            // 调用视觉模型
            ChatResponse response = visionChatModel.chat(Collections.singletonList(userMessage));
            return extractDescription(response);
        } catch (Exception e) {
            // 视觉模型调用失败、图片读取失败等场景统一降级（AC-024）
            log.warn("图片描述生成失败，跳过该图片: imagePath={}, pageNumber={}, error={}",
                    imagePath, imageInfo.getPageNumber(), e.getMessage());
            return null;
        }
    }

    /**
     * 将图片文件编码为 Base64 字符串
     */
    private String encodeImageToBase64(Path path) throws IOException {
        byte[] imageBytes = Files.readAllBytes(path);
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * 根据文件扩展名推断 MIME 类型
     * 业务含义：视觉模型 API 需要 mimeType 字段标识图片格式
     */
    private String resolveMimeType(String imagePath) {
        String lowerPath = imagePath.toLowerCase();
        if (lowerPath.endsWith(".png")) {
            return "image/png";
        }
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerPath.endsWith(".gif")) {
            return "image/gif";
        }
        if (lowerPath.endsWith(".webp")) {
            return "image/webp";
        }
        // 默认使用 PNG（与 ImageExtractor 输出一致）
        return "image/png";
    }

    /**
     * 从 ChatResponse 提取描述文本
     */
    private String extractDescription(ChatResponse response) {
        if (response == null) {
            return null;
        }
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage == null) {
            return null;
        }
        String text = aiMessage.text();
        return (text == null || text.isEmpty()) ? null : text;
    }
}
