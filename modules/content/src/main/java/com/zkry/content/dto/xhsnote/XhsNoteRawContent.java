package com.zkry.content.dto.xhsnote;

import java.util.List;

/**
 * 从公开页面读取出的原始笔记正文和图片。
 *
 * @param noteId 笔记 ID；用户粘贴正文使用任务内生成的虚拟 ID
 * @param sourceUrl 公开页面地址；粘贴正文使用 pasted-content
 * @param title 笔记标题
 * @param description 笔记正文
 * @param author 作者昵称
 * @param images 图片列表，下载前只有远程 URL，下载后会带本地路径和状态
 */
public record XhsNoteRawContent(
    String noteId,
    String sourceUrl,
    String title,
    String description,
    String author,
    List<XhsNoteImage> images
) {
    /** 对模型可能返回或调用方可能传入的 null 集合做只读空列表归一化。 */
    public List<XhsNoteImage> safeImages() {
        return images == null ? List.of() : images;
    }

    /** 返回真正可以加入多模态消息的图片。 */
    public List<XhsNoteImage> downloadedImages() {
        return safeImages().stream().filter(XhsNoteImage::downloaded).toList();
    }

    /** 标题、正文或成功下载的图片任一存在，这篇笔记就仍具有研究价值。 */
    public boolean hasUsableContent() {
        return title != null && !title.isBlank()
            || description != null && !description.isBlank()
            || !downloadedImages().isEmpty();
    }
}
