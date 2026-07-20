package com.zkry.trip.dto.xhsnote;

/**
 * 指定笔记规划接口的完整请求。
 *
 * @param share_text 一篇或多篇小红书长链接、短链或 App 完整分享内容
 * @param note_content 用户直接粘贴的攻略笔记正文
 * @param requirement 用户额外要求，例如“不去滇池”“带老人不要太累”
 * @param start_date 出发日期，格式 yyyy-MM-dd
 */
public record XhsNotePlanRequest(
        String share_text,
        String note_content,
    String requirement,
    String start_date
) {
    /** 返回清理空格后的分享文本，避免业务代码重复判 null。 */
    public String safeShareText() {
        return share_text == null ? "" : share_text.trim();
    }

    /** 返回清理空格后的用户粘贴攻略正文。 */
    public String safeNoteContent() {
        return note_content == null ? "" : note_content.trim();
    }

    /** 返回清理空格后的额外要求；该字段允许为空。 */
    public String safeRequirement() {
        return requirement == null ? "" : requirement.trim();
    }

    /** 返回清理空格后的出发日期字符串，具体格式由任务服务校验。 */
    public String safeStartDate() {
        return start_date == null ? "" : start_date.trim();
    }

    /** 链接和粘贴正文至少填写一类；两类同时填写时会一起交给模型理解。 */
    public boolean hasAnyNoteContent() {
        return !safeShareText().isBlank() || !safeNoteContent().isBlank();
    }
}
