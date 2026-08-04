package com.agentdemo.splitter.splitter.image;

import com.agentdemo.splitter.config.SplitterProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PDF 图片提取器（CR-002 新增，BUG 修复后改造）
 * <p>
 * 业务含义：从 PDF 中提取嵌入的图片对象（PDImageXObject）并保存到文件系统。
 * 返回 ImageInfo 列表（含路径、页码、索引），供 ImageDescriptor 生成描述后向量化入库。
 * </p>
 * <p>
 * 修复说明（BUG 修复）：
 * 原实现使用 {@link org.apache.pdfbox.rendering.PDFRenderer#renderImageWithDPI} 将整页
 * （含文字+图片）渲染为 PNG，导致文字页面被误判为图片。
 * 修复后改为遍历 PDF 每页的 {@link PDResources}，仅提取真正的嵌入图片对象（PDImageXObject），
 * 文字内容不再被错误地当作图片。Form XObject 内嵌套的图片会递归提取。
 * </p>
 * <p>
 * 碎片图片过滤（BUG 修复新增）：
 * 流程图软件（如 process.on）导出的 PDF 会在单页嵌入大量小图片（背景块、节点框、边框等），
 * 单张图片单独 OCR/摘要无意义且浪费 Token。当单页图片数量超过 {@code maxImagesPerPage} 阈值时，
 * 认为是碎片化场景，整页跳过所有图片处理并清理已保存的图片文件。
 * </p>
 * <p>
 * dpi 参数兼容性：方法签名保留 dpi 参数以兼容既有调用方（DocumentService 通过 SplitterProperties
 * 传入），但实际不再用于图片提取（嵌入图片按原始分辨率提取，无需渲染 DPI）。
 * </p>
 */
@Slf4j
@Component
public class ImageExtractor {

    private final SplitterProperties splitterProperties;

    public ImageExtractor(SplitterProperties splitterProperties) {
        this.splitterProperties = splitterProperties;
    }

    /**
     * 从 PDF 提取所有嵌入图片对象并保存为文件
     * <p>
     * 业务含义：遍历 PDF 每页的 PDResources，对每个 PDImageXObject 调用
     * {@link PDImageXObject#getImage()} 获取 BufferedImage 后通过 ImageIO 保存到
     * {imageDir}/{documentId}/page{N}_img{M}.png。
     * </p>
     * <p>
     * 碎片图片过滤：单页图片数量超过 maxImagesPerPage 时，认为是流程图导出碎片化场景，
     * 整页跳过图片处理，并清理已保存到磁盘的图片文件，避免残留。
     * </p>
     *
     * @param fileBytes  PDF 文件字节数组
     * @param documentId 文档 ID（用于构建存储路径子目录）
     * @param imageDir   图片存储根目录
     * @param dpi        （已废弃）原整页渲染 DPI，保留参数兼容既有调用，实际不使用
     * @return 提取的图片信息列表；空/null/非 PDF 输入返回空列表；碎片化页面返回空列表
     */
    public List<ImageInfo> extractImages(byte[] fileBytes, String documentId, Path imageDir, int dpi) {
        List<ImageInfo> images = new ArrayList<>();

        // 空输入校验
        if (fileBytes == null || fileBytes.length == 0) {
            return images;
        }

        // 读取单页图片数量阈值（业务含义：超过此值认为是流程图导出碎片化场景）
        int maxImagesPerPage = resolveMaxImagesPerPage();

        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            // 创建图片存储子目录：{imageDir}/{documentId}/
            Path docImageDir = imageDir.resolve(documentId);
            Files.createDirectories(docImageDir);

            int pageCount = document.getNumberOfPages();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = document.getPage(pageIndex);
                int pageNumber = pageIndex + 1;

                // 先收集该页所有图片到临时列表（图片已保存到磁盘）
                List<ImageInfo> pageImages = new ArrayList<>();
                int[] imageIndexHolder = {0};
                Set<COSName> visitedFormNames = new HashSet<>();
                extractImagesFromResources(
                        page.getResources(), docImageDir, documentId, pageNumber,
                        imageIndexHolder, visitedFormNames, pageImages);

                // 碎片图片过滤：单页图片数量超过阈值时整页跳过并清理已保存文件
                if (pageImages.size() > maxImagesPerPage) {
                    log.info("单页图片数量过多({}张)，疑似流程图导出碎片化PDF，跳过该页所有图片: " +
                                    "documentId={}, page={}, maxImagesPerPage={}",
                            pageImages.size(), documentId, pageNumber, maxImagesPerPage);
                    cleanupImageFiles(pageImages);
                    continue;
                }

                images.addAll(pageImages);
            }

            log.info("PDF 图片提取完成: documentId={}, pageCount={}, imageCount={}, maxImagesPerPage={}",
                    documentId, pageCount, images.size(), maxImagesPerPage);
        } catch (Exception e) {
            // PDF 加载失败（非 PDF 格式或损坏）
            log.warn("PDF 图片提取失败（无法加载文档）, documentId={}: {}", documentId, e.getMessage());
        }

        return images;
    }

    /**
     * 读取单页图片数量上限阈值
     * <p>
     * 业务含义：从 SplitterProperties.PdfChunkConfig 读取 maxImagesPerPage，
     * 配置缺失或异常时回退到默认值 15，确保过滤逻辑始终生效。
     * </p>
     */
    private int resolveMaxImagesPerPage() {
        try {
            SplitterProperties.PdfChunkConfig pdfConfig = splitterProperties.getPdf();
            if (pdfConfig != null && pdfConfig.getMaxImagesPerPage() > 0) {
                return pdfConfig.getMaxImagesPerPage();
            }
        } catch (Exception e) {
            log.warn("读取 maxImagesPerPage 配置失败，使用默认值 15: {}", e.getMessage());
        }
        return 15;
    }

    /**
     * 清理已保存的图片文件
     * <p>
     * 业务含义：碎片化页面被跳过时，删除已保存到磁盘的图片文件，避免残留占用存储。
     * 单个文件删除失败不影响其他文件清理。
     * </p>
     */
    private void cleanupImageFiles(List<ImageInfo> pageImages) {
        for (ImageInfo img : pageImages) {
            try {
                Files.deleteIfExists(Path.of(img.getImagePath()));
            } catch (IOException e) {
                log.warn("清理碎片图片文件失败: imagePath={}, error={}",
                        img.getImagePath(), e.getMessage());
            }
        }
    }

    /**
     * 递归从 PDResources 中提取嵌入图片
     * <p>
     * 业务含义：遍历 Resources 中的所有 XObject 名称：
     * - {@link PDImageXObject}：直接提取为图片文件
     * - {@link PDFormXObject}：递归处理其内部 Resources（Form 可能包含嵌套图片）
     * 同页多图通过 imageIndex 递增区分；Form 递归通过 visitedFormNames 去重避免重复。
     * </p>
     *
     * @param resources         当前层级的 PDF 资源
     * @param docImageDir       图片存储目录
     * @param documentId        文档 ID（仅用于日志）
     * @param pageNumber        当前页码
     * @param imageIndexHolder  当前页图片索引计数器（数组包装以支持递归修改）
     * @param visitedFormNames 已访问的 Form XObject 名称（避免递归重复提取）
     * @param images            提取结果累积列表
     */
    private void extractImagesFromResources(PDResources resources, Path docImageDir,
                                            String documentId, int pageNumber,
                                            int[] imageIndexHolder,
                                            Set<COSName> visitedFormNames,
                                            List<ImageInfo> images) {
        if (resources == null) {
            return;
        }

        // 遍历当前 Resources 中的所有 XObject 名称
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject == null) {
                    continue;
                }

                if (xobject instanceof PDImageXObject) {
                    // 嵌入图片对象：提取并保存
                    PDImageXObject imageObj = (PDImageXObject) xobject;
                    saveImageObject(imageObj, docImageDir, documentId, pageNumber,
                            imageIndexHolder, images);
                } else if (xobject instanceof PDFormXObject) {
                    // Form XObject：递归提取其内部图片，通过 visitedFormNames 避免重复
                    if (visitedFormNames.contains(name)) {
                        continue;
                    }
                    visitedFormNames.add(name);
                    PDFormXObject formObj = (PDFormXObject) xobject;
                    extractImagesFromResources(
                            formObj.getResources(), docImageDir, documentId, pageNumber,
                            imageIndexHolder, visitedFormNames, images);
                }
            } catch (Exception e) {
                // 单个图片对象提取失败不影响其他图片
                log.warn("PDF 图片对象提取失败: documentId={}, page={}, xobjectName={}, error={}",
                        documentId, pageNumber, name.getName(), e.getMessage());
            }
        }
    }

    /**
     * 将 PDImageXObject 保存为图片文件
     * <p>
     * 业务含义：调用 {@link PDImageXObject#getImage()} 获取 BufferedImage，
     * 通过 ImageIO 保存为 PNG 格式（保留无损质量，便于视觉模型识别）。
     * 文件名格式：page{N}_img{M}.png
     * </p>
     */
    private void saveImageObject(PDImageXObject imageObj, Path docImageDir,
                                  String documentId, int pageNumber,
                                  int[] imageIndexHolder, List<ImageInfo> images) {
        try {
            BufferedImage image = imageObj.getImage();
            if (image == null) {
                log.warn("PDF 图片对象 BufferedImage 为空，跳过: documentId={}, page={}",
                        documentId, pageNumber);
                return;
            }

            int imageIndex = imageIndexHolder[0];
            String fileName = String.format("page%d_img%d.png", pageNumber, imageIndex);
            Path imagePath = docImageDir.resolve(fileName);

            // 保存为 PNG（保留无损质量）
            ImageIO.write(image, "png", imagePath.toFile());

            images.add(ImageInfo.builder()
                    .imagePath(imagePath.toString())
                    .pageNumber(pageNumber)
                    .imageIndex(imageIndex)
                    .build());

            // 索引递增以区分同页多图
            imageIndexHolder[0]++;

            log.debug("PDF 图片提取成功: documentId={}, page={}, imageIndex={}",
                    documentId, pageNumber, imageIndex);
        } catch (Exception e) {
            log.warn("PDF 图片保存失败: documentId={}, page={}, error={}",
                    documentId, pageNumber, e.getMessage());
        }
    }
}
