package com.agentdemo.common.utils;

/**
 * Token 估算工具类
 * <p>
 * 基于字符特征的简易 Token 估算：
 * - 中文字符（CJK 区间）：约 1.5 字符 / Token
 * - 其他字符（英文、数字、符号）：约 4 字符 / Token
 * - 混合文本：分别统计后求和再向上取整
 * <p>
 * 适用于不需要精确 Token 计数的场景（如文档分块大小估算、Token 消耗量估算标记）。
 */
public final class SimpleTokenEstimator {

    private SimpleTokenEstimator() {
    }

    /**
     * 估算文本的 Token 数量
     *
     * @param text 输入文本，可为 null
     * @return 估算的 Token 数量，null 或空文本返回 0
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (isCJK(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        return (int) Math.ceil(cjkChars / 1.5 + otherChars / 4.0);
    }

    /**
     * 判断字符是否属于 CJK（中日韩）字符区间
     */
    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
