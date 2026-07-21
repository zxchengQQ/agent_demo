package com.agentdemo.common.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 分页查询返回结果
 * <p>
 * 业务含义：在 Result 基础上携带分页信息，用于列表查询场景（如 RAG 知识库文档列表）。
 * </p>
 *
 * @param <T> 列表元素类型
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageResult<T> extends Result<List<T>> {

    private static final long serialVersionUID = 1L;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码（从 1 开始）
     */
    private int page;

    /**
     * 每页大小
     */
    private int size;

    /**
     * 成功返回（带分页数据）
     *
     * @param list  列表数据
     * @param total 总记录数
     * @param page  当前页码
     * @param size  每页大小
     * @param <T>   元素类型
     * @return 分页结果
     */
    public static <T> PageResult<T> success(List<T> list, long total, int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setSuccess(true);
        result.setCode(200);
        result.setMessage("成功");
        result.setData(list);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
