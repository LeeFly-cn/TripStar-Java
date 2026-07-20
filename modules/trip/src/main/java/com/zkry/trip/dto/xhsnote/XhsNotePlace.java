package com.zkry.trip.dto.xhsnote;

/**
 * 多模态模型从指定笔记正文或图片中识别出的地点。
 *
 * @param name 地点名称
 * @param city 所属城市
 * @param type attraction、hotel 或 restaurant
 * @param source_note_id 来源笔记 ID，粘贴正文可填写 pasted-content
 * @param reason 笔记推荐或提及这个地点的原因
 * @param day 笔记建议安排在第几天
 * @param time_hint 适合到访的时间提示
 */
public record XhsNotePlace(
    String name,
    String city,
    String type,
    String source_note_id,
    String reason,
    Integer day,
    String time_hint
) {
}
