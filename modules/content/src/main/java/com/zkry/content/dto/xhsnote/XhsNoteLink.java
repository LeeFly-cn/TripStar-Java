package com.zkry.content.dto.xhsnote;

/**
 * 解析并展开后的公开小红书笔记链接。
 *
 * @param originalInput 用户提交的完整分享文本，用于保留原始来源
 * @param extractedUrl 从分享文本中提取出的长链或短链
 * @param finalUrl 短链展开后的公开笔记地址
 * @param noteId 从最终地址路径中提取出的笔记 ID
 * @param xsecToken 页面查询参数中的访问 token，公开页面存在时原样保留
 * @param xsecSource 页面查询参数中的来源标识
 */
public record XhsNoteLink(
    String originalInput,
    String extractedUrl,
    String finalUrl,
    String noteId,
    String xsecToken,
    String xsecSource
) {
    /** 页面读取至少需要有效的最终地址和笔记 ID。 */
    public boolean readable() {
        return noteId != null && !noteId.isBlank()
            && finalUrl != null && !finalUrl.isBlank();
    }
}
