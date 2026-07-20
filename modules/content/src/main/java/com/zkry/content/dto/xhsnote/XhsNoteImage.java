package com.zkry.content.dto.xhsnote;

/**
 * 小红书笔记中的一张图片及其本地下载状态。
 *
 * @param index 图片在原笔记中的顺序，从 1 开始
 * @param sourceUrl 页面解析出的远程图片地址
 * @param localPath 下载成功后的本地临时文件路径
 * @param mimeType HTTP 响应返回的图片 MIME 类型，供 Spring AI Media 使用
 * @param downloaded 是否下载成功
 * @param error 下载失败原因，成功时为空字符串
 */
public record XhsNoteImage(
    int index,
    String sourceUrl,
    String localPath,
    String mimeType,
    boolean downloaded,
    String error
) {
}
