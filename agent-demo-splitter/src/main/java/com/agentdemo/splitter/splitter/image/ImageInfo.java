package com.agentdemo.splitter.splitter.image;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片信息数据结构（CR-002 新增）
 * <p>
 * 业务含义：记录从 PDF 提取的每张图片的存储路径、所属页码和页面内索引。
 * 由 ImageExtractor 生成，供 ImageDescriptor 和 DocumentService 使用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageInfo {

    /** 图片文件存储路径（绝对路径） */
    private String imagePath;

    /** 图片所属页码（从 1 开始） */
    private int pageNumber;

    /** 图片在页面中的索引（同页多图时区分，从 0 开始） */
    private int imageIndex;
}
