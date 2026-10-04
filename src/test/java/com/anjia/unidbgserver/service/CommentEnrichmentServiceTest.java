package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import com.anjia.unidbgserver.dto.FQNovelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CommentEnrichmentServiceTest {

    private static final String BASE = "https://192.168.50.3:8099";

    @Mock
    private FQCommentService fqCommentService;

    @InjectMocks
    private CommentEnrichmentService service;

    // ========== generateBadgeSrc tests ==========

    @Test
    void generateBadgeSrc_usesAbsoluteUrl() throws Exception {
        String result = invokeGenerateBadgeSrc(5, "123", "456", 0, BASE);
        assertNotNull(result);
        assertTrue(result.startsWith(BASE + "/api/fqnovel/comment-badge/5,"),
                "Badge URL must be absolute (base URL + badge path + count)");
    }

    @Test
    void generateBadgeSrc_clickJsUsesNumericArgsOnly() throws Exception {
        String result = invokeGenerateBadgeSrc(5, "123", "456", 2, BASE);
        assertNotNull(result);
        assertTrue(result.contains(",{\"click\":\"showCmt2(123,456,2)\",\"style\":\"text\"}"),
                "Click JS should be pure numeric args in standard JSON");
        assertFalse(result.contains("\\"), "Badge src must not contain backslash escapes");
        assertFalse(result.contains("'"), "Single quotes would break mod imgPattern's URL part");
    }

    /**
     * 用阅读魔改版（喵公子 beta / LegadoTeam legado）正文管线的真实正则与解析步骤，
     * 端到端验证注入的 img 标记可被解析出图片 URL 与 click JS：
     * ① imgPattern（双引号 src 属性，可选组允许一段以 } 结尾的裸双引号 JSON）；
     * ② paramPattern ",\s*(?=\{)" 切出选项 JSON；
     * ③ JSON 解析为 Map 取 click/style。
     */
    @Test
    void badgeMarkup_survivesLegadoModParseChain() throws Exception {
        // 与 TextChapterLayout.kt 的 AppPattern.imgPattern 逐字一致
        java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile(
                "<img[^>]*src=\\\"([^\\\"]*(?:\\\"[^>]+\\})?)\\\"[^>]*>");
        java.util.regex.Pattern paramPattern = java.util.regex.Pattern.compile("\\s*,\\s*(?=\\{)");

        String content = invokeInjectCommentIcons("第一段\n第二段", new HashMap<>(Map.of(0, 5)), "123", "456");
        java.util.regex.Matcher imgMatcher = imgPattern.matcher(content);
        assertTrue(imgMatcher.find(), "img tag should match mod imgPattern");

        String src = imgMatcher.group(1);

        // ② paramPattern 切割
        java.util.regex.Matcher paramMatcher = paramPattern.matcher(src);
        assertTrue(paramMatcher.find(), "src should split at ,{ boundary");
        String urlPart = src.substring(0, paramMatcher.start());
        String optionJson = src.substring(paramMatcher.end());
        assertEquals(BASE + "/api/fqnovel/comment-badge/5", urlPart);

        // ③ 标准 JSON 解析（任何严格度的解析器都接受）
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> optionMap = om.readValue(optionJson,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertEquals("showCmt2(123,456,0)", optionMap.get("click"));
        assertEquals("text", optionMap.get("style"));
    }

    @Test
    void generateBadgeSrc_nonNumericIdReturnsNull() throws Exception {
        assertNull(invokeGenerateBadgeSrc(5, "book1", "456", 0, BASE),
                "Non-numeric bookId should not inject badge (keeps click JS quote-free)");
        assertNull(invokeGenerateBadgeSrc(5, "123", "chapter1", 0, BASE),
                "Non-numeric chapterId should not inject badge");
    }

    @Test
    void generateBadgeSrc_emptyBaseUrlReturnsNull() throws Exception {
        assertNull(invokeGenerateBadgeSrc(5, "123", "456", 0, ""));
        assertNull(invokeGenerateBadgeSrc(5, "123", "456", 0, null));
    }

    @Test
    void generateBadgeSrc_countZeroOrNegativeReturnsNull() throws Exception {
        assertNull(invokeGenerateBadgeSrc(0, "123", "456", 0, BASE));
        assertNull(invokeGenerateBadgeSrc(-1, "123", "456", 0, BASE));
    }

    // ========== injectCommentIcons tests ==========

    @Test
    void injectCommentIcons_hasComments_injectsImgTag() throws Exception {
        String content = "第一段\n第二段\n第三段";
        Map<Integer, Integer> commentCounts = new HashMap<>();
        commentCounts.put(0, 5);

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456");
        assertTrue(result.contains("<img src=\"" + BASE + "/api/fqnovel/comment-badge/5,"),
                "Should contain img tag with absolute badge URL in double-quoted src");
        assertTrue(result.contains("showCmt2(123,456,0)"),
                "Click JS should carry numeric book/chapter/para identifiers");
    }

    @Test
    void injectCommentIcons_noComments_noImgTag() throws Exception {
        String content = "第一段\n第二段\n第三段";
        Map<Integer, Integer> commentCounts = new HashMap<>();

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456");
        assertFalse(result.contains("<img"), "Should not contain img tags when no comments");
        assertEquals("<p>第一段</p>\n<p>第二段</p>\n<p>第三段</p>", result);
    }

    @Test
    void injectCommentIcons_htmlEscaped() throws Exception {
        String content = "文本 & 符号 <标签>";
        Map<Integer, Integer> commentCounts = new HashMap<>();
        commentCounts.put(0, 3);

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456");
        assertTrue(result.contains("&amp;"), "& should be escaped");
        assertTrue(result.contains("&lt;"), "< should be escaped");
        assertTrue(result.contains("&gt;"), "> should be escaped");
    }

    @Test
    void injectCommentIcons_emptyContent_returnsEmpty() throws Exception {
        String result = invokeInjectCommentIcons("", new HashMap<>(), "123", "456");
        assertEquals("", result, "Empty content should return empty string");
    }

    @Test
    void injectCommentIcons_trailingNewline_handledGracefully() throws Exception {
        String content = "段落1\n";
        Map<Integer, Integer> commentCounts = new HashMap<>();
        commentCounts.put(0, 5);

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456");
        assertTrue(result.contains("段落1"), "Should include paragraph text");
        assertTrue(result.contains("<img"), "Should include img tag");
    }

    @Test
    void injectCommentIcons_titleLine_noIconOnTitle() throws Exception {
        String content = "第一章 开始\n\n正文第一段\n\n正文第二段";
        Map<Integer, Integer> commentCounts = new HashMap<>();
        commentCounts.put(1, 5); // first content paragraph (index 1) has 5 comments
        commentCounts.put(3, 3); // second content paragraph (index 3) has 3 comments

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456", "第一章 开始");
        assertTrue(result.startsWith("<p>第一章 开始</p>"),
                "Title line should be rendered without icon");
        // 段落索引与 API para_index 对齐（实况验证：标题行不占索引）：
        // 标题行(不递增)、空行(1)、正文第一段(1)、空行(2)、正文第二段(3)
        assertTrue(result.contains("showCmt2(123,456,1)") && result.contains("showCmt2(123,456,3)"),
                "Content paragraphs should use paraIndex=1 and paraIndex=3 (title does not occupy index 0)");
        // Title is before first img; ensure no img between title and first content
        int titleEnd = result.indexOf("</p>") + 4;
        int firstImg = result.indexOf("<img");
        assertTrue(firstImg > titleEnd,
                "First img should appear after title paragraph, not on it");
    }

    @Test
    void injectCommentIcons_titleLine_notCountedInParaIndex() throws Exception {
        // 实况验证：API 的 para_index 0 对应首个正文段落（标题行不占索引）
        String content = "第一章 标题\n正文第一段\n正文第二段";
        Map<Integer, Integer> commentCounts = new HashMap<>();
        commentCounts.put(0, 7); // 首个正文段落 → para_index 0
        commentCounts.put(1, 3); // 第二个正文段落 → para_index 1

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456", "第一章 标题");
        assertTrue(result.contains("showCmt2(123,456,0)") && result.contains("showCmt2(123,456,1)"),
                "First content paragraph should map to para_index=0 (title line not counted)");
        assertFalse(result.contains("showCmt2(123,456,2)"),
                "No icon should be shifted one line up onto the previous paragraph");
    }

    @Test
    void injectCommentIcons_blankLines_keepIndexAligned() throws Exception {
        // 空段落也占用 para_index（与 API 统计对齐），图标不因空行错位
        String content = "第一段\n\n第三段";
        Map<Integer, Integer> commentCounts = new HashMap<>();
        commentCounts.put(2, 5); // 第三段的评论在 para_index=2

        String result = invokeInjectCommentIcons(content, commentCounts, "123", "456");
        assertTrue(result.contains("showCmt2(123,456,2)"),
                "Blank line should advance index so para 3 maps to index 2");
        assertFalse(result.contains("showCmt2(123,456,1)"),
                "No icon should be placed at the blank line index");
    }

    private String invokeGenerateBadgeSrc(int count, String bookId, String chapterId, int paraIndex, String baseUrl) throws Exception {
        Method method = CommentEnrichmentService.class.getDeclaredMethod(
                "generateBadgeSrc", int.class, String.class, String.class, int.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, count, bookId, chapterId, paraIndex, baseUrl);
    }

    private String invokeInjectCommentIcons(
            String content, Map<Integer, Integer> commentCounts,
            String bookId, String chapterId) throws Exception {
        return invokeInjectCommentIcons(content, commentCounts, bookId, chapterId, null);
    }

    private String invokeInjectCommentIcons(
            String content, Map<Integer, Integer> commentCounts,
            String bookId, String chapterId, String title) throws Exception {
        Method method = CommentEnrichmentService.class.getDeclaredMethod(
                "injectCommentIcons", String.class, Map.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, content, commentCounts, bookId, chapterId, title, BASE);
    }
}
