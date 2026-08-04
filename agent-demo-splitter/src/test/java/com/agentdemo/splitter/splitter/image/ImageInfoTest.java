package com.agentdemo.splitter.splitter.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImageInfo 数据结构单元测试（CR-002 新增）
 * <p>
 * 验证图片信息数据结构（path/pageNumber/imageIndex）的字段读写。
 */
class ImageInfoTest {

    @Test
    void 构建ImageInfo包含所有字段() {
        ImageInfo info = ImageInfo.builder()
                .imagePath("/data/rag/temp/images/doc123/page1_img0.png")
                .pageNumber(1)
                .imageIndex(0)
                .build();

        assertEquals("/data/rag/temp/images/doc123/page1_img0.png", info.getImagePath());
        assertEquals(1, info.getPageNumber());
        assertEquals(0, info.getImageIndex());
    }

    @Test
    void 无参构造字段为默认值() {
        ImageInfo info = new ImageInfo();
        assertNull(info.getImagePath());
        assertEquals(0, info.getPageNumber());
        assertEquals(0, info.getImageIndex());
    }

    @Test
    void setter可以修改字段值() {
        ImageInfo info = new ImageInfo();
        info.setImagePath("/tmp/img.png");
        info.setPageNumber(5);
        info.setImageIndex(2);

        assertEquals("/tmp/img.png", info.getImagePath());
        assertEquals(5, info.getPageNumber());
        assertEquals(2, info.getImageIndex());
    }
}
