package com.zkry.content.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.dto.xhsnote.XhsNoteImage;
import com.zkry.content.dto.xhsnote.XhsNoteLink;
import com.zkry.content.dto.xhsnote.XhsNoteRawContent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 解析小红书公开页面中的 {@code window.__INITIAL_STATE__}。
 *
 * <p>公开页面已经把笔记详情作为 JSON 状态嵌入 HTML。这里使用 Jackson 读取结构化数据，
 * 不通过正则分别抓取标题、正文和图片字段，降低页面字段中出现转义字符时的解析风险。
 */
@Component
public class XhsNotePageParser {

    /** 只负责定位完整状态对象；对象内部字段仍交给 JSON 解析器处理。 */
    private static final Pattern INITIAL_STATE_PATTERN = Pattern.compile(
        "window\\.__INITIAL_STATE__=(\\{.*?\\});?</script>",
        Pattern.DOTALL
    );

    /**
     * 从一篇公开笔记 HTML 中提取正文、作者和全部图片地址。
     */
    public XhsNoteRawContent parse(XhsNoteLink link, String html) {
        if (html == null || html.isBlank()) {
            throw new BizException("小红书公开页面为空，noteId=" + link.noteId());
        }
        Matcher matcher = INITIAL_STATE_PATTERN.matcher(html);
        if (!matcher.find()) {
            throw new BizException("公开页面中没有找到笔记状态，可能需要登录或安全验证。");
        }

        JsonNode state;
        try {
            // 页面脚本是 JavaScript 对象，个别字段可能写 undefined；替换为 null 后才是合法 JSON。
            state = JsonUtils.parseTree(matcher.group(1).replace("undefined", "null"));
        } catch (Exception ex) {
            throw new BizException("小红书公开页面状态解析失败：" + ex.getMessage());
        }
        // noteDetailMap 以 noteId 为 key；使用 path 可避免任一中间字段缺失时出现空指针。
        JsonNode note = state.path("note")
            .path("noteDetailMap")
            .path(link.noteId())
            .path("note");
        if (note.isMissingNode() || note.isNull() || note.isEmpty()) {
            throw new BizException("公开页面中没有找到笔记数据，noteId=" + link.noteId());
        }

        return new XhsNoteRawContent(
            link.noteId(),
            link.finalUrl(),
            text(note, "title"),
            text(note, "desc"),
            text(note.path("user"), "nickname"),
            images(note.path("imageList"))
        );
    }

    /**
     * 读取图片列表、选择每张图的最佳 URL，并按 URL 去重。
     */
    private List<XhsNoteImage> images(JsonNode imageList) {
        if (imageList == null || !imageList.isArray()) {
            return List.of();
        }
        List<XhsNoteImage> result = new ArrayList<>();
        // LinkedHashSet 保留原图顺序，Day01/Day02 这类图片顺序对多模态理解很重要。
        Set<String> seen = new LinkedHashSet<>();
        int index = 1;
        for (JsonNode image : imageList) {
            String url = bestImageUrl(image);
            if (url.isBlank() || !seen.add(url)) {
                continue;
            }
            // 此时只保存远程 URL；本地路径和 MIME 类型由 XhsNoteImageService 下载后补齐。
            result.add(new XhsNoteImage(index++, url, "", "", false, ""));
        }
        return List.copyOf(result);
    }

    /** 从多个清晰度地址中优先选择 infoList 最后一个可用 URL。 */
    private String bestImageUrl(JsonNode image) {
        JsonNode infoList = image.path("infoList");
        if (infoList.isArray()) {
            // 页面通常按清晰度从低到高排列，因此从末尾向前查找。
            for (int i = infoList.size() - 1; i >= 0; i--) {
                String url = text(infoList.get(i), "url");
                if (!url.isBlank()) {
                    return url;
                }
            }
        }
        // 兼容不同页面版本中 infoList 缺失时使用的备用字段名。
        for (String field : List.of("urlDefault", "urlPre", "url", "urlPattern")) {
            String value = text(image, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** 安全读取并清理文本字段。 */
    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String value = node.path(field).asText("");
        return value == null ? "" : value.trim();
    }
}
