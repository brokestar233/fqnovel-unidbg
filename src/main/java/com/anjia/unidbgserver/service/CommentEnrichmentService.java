package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.FQCommentIdeaRequest;
import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import com.anjia.unidbgserver.dto.FQNovelResponse;
import com.anjia.unidbgserver.utils.CommonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 段评内容增强服务
 * 在章节正文中插入段评图标，点击图标跳转到段评展示页
 */
@Slf4j
@Service
public class CommentEnrichmentService {

    @Resource
    private FQCommentService fqCommentService;

    /** 段评页面路径 */
    private static final String COMMENT_PAGE_PATH = "/api/ssr/comment-page";

    /** 评论数徽章图片路径（PNG，见 CommentBadgeController） */
    private static final String BADGE_PATH = "/api/fqnovel/comment-badge/";

    /**
     * 增强章节内容：查询段评统计，在有评论的段落后插入段评图标
     *
     * @param chapterResponse 原始章节响应
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @param baseUrl 服务对外访问基址（如 https://192.168.50.3:8099），用于生成徽章绝对 URL
     * @return 增强后的章节内容
     */
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> enrichChapter(
            FQNovelResponse<FQNovelChapterInfo> chapterResponse,
            String bookId,
            String chapterId,
            String baseUrl) {

        // 如果原始响应失败，直接返回
        if (chapterResponse.getCode() != 0 || chapterResponse.getData() == null) {
            return CompletableFuture.completedFuture(chapterResponse);
        }

        String txtContent = chapterResponse.getData().getTxtContent();
        if (txtContent == null || txtContent.trim().isEmpty()) {
            return CompletableFuture.completedFuture(chapterResponse);
        }

        // 异步调用段评统计 API
        FQCommentIdeaRequest ideaRequest = new FQCommentIdeaRequest();
        ideaRequest.setChapterId(chapterId);
        ideaRequest.setBookId(bookId);

        return fqCommentService.getCommentIdeaList(ideaRequest)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenApply(ideaResponse -> {
                    try {
                        Map<Integer, Integer> commentCounts = parseIdeaResponse(ideaResponse);
                        if (commentCounts.isEmpty()) {
                            log.debug("章节 {} 无段评数据，不注入图标", chapterId);
                            return chapterResponse;
                        }

                        String title = chapterResponse.getData().getTitle();
                        String enrichedContent = injectCommentIcons(
                            txtContent, commentCounts, bookId, chapterId, title, baseUrl);
                        chapterResponse.getData().setTxtContent(enrichedContent);
                        log.info("章节 {} 段评增强完成，共 {} 个段落有评论",
                                chapterId, commentCounts.size());
                    } catch (Exception e) {
                        log.warn("段评增强失败，返回原始内容 - chapterId: {}", chapterId, e);
                    }
                    return chapterResponse;
                })
                .exceptionally(e -> {
                    log.warn("段评统计接口调用失败，返回原始内容 - chapterId: {}", chapterId, e);
                    return chapterResponse;
                });
    }

    /**
     * 解析段评统计接口响应，提取段落索引 -> 评论数 映射
     *
     * 实际返回格式:
     * {
     *   "data": {
     *     "data": {
     *       "data": {
     *         "0": { "count": 221, ... },   // key=段落索引, value.count=评论数
     *         "1": { "count": 175, ... },
     *       }
     *     }
     *   }
     * }
     */
    private Map<Integer, Integer> parseIdeaResponse(FQNovelResponse<JsonNode> ideaResponse) {
        Map<Integer, Integer> result = new HashMap<>();
        if (ideaResponse == null || ideaResponse.getData() == null) {
            return result;
        }

        try {
            JsonNode data = ideaResponse.getData();
            // 路径: data.data.data -> { "0": {...}, "1": {...}, ... }
            JsonNode paraMap = null;
            if (data.has("data") && data.get("data").has("data")) {
                paraMap = data.get("data").get("data");
            } else if (data.has("data")) {
                paraMap = data.get("data");
            }

            if (paraMap == null || !paraMap.isObject()) {
                return result;
            }

            java.util.Iterator<Map.Entry<String, JsonNode>> fields = paraMap.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                try {
                    int paraIndex = Integer.parseInt(entry.getKey());
                    JsonNode value = entry.getValue();
                    int count = 0;
                    if (value.has("count")) {
                        count = value.get("count").asInt(0);
                    }
                    if (count > 0) {
                        result.put(paraIndex, count);
                    }
                } catch (NumberFormatException ignored) {
                    // 跳过非数字 key
                }
            }
        } catch (Exception e) {
            log.warn("解析段评统计响应失败", e);
        }

        return result;
    }

    private String injectCommentIcons(
            String content,
            Map<Integer, Integer> commentCounts,
            String bookId,
            String chapterId,
            String title,
            String baseUrl) {

        String[] paragraphs = content.split("\n", -1);
        StringBuilder enriched = new StringBuilder();
        // 段落索引与 API 段评统计的 para_index 对齐（实况验证）：
        // API 的 para_index 从 0 开始计数"首个正文段落"，标题行不占索引
        // （实测 para 0 的评论对应第一章正文第一段，而非标题行）。
        // 因此标题行不注入图标、也不递增 paraIndex；空段则照常递增。
        int paraIndex = 0;
        boolean firstNonEmpty = true;

        for (int i = 0; i < paragraphs.length; i++) {
            String para = paragraphs[i];
            if (para.isEmpty() && i == paragraphs.length - 1) {
                continue;
            }

            // 空段落（纯空白）不渲染 <p>，但索引仍然递增
            String trimmedPara = escapeHtml(para.trim());
            if (trimmedPara.isEmpty()) {
                paraIndex++;
                continue;
            }

            enriched.append("<p>").append(trimmedPara);

            boolean isTitleLine = firstNonEmpty
                    && title != null && !title.isEmpty()
                    && para.trim().equals(title);
            firstNonEmpty = false;

            // 标题行不注入图标、不递增索引；正文行按 paraIndex 注入徽章后递增
            if (!isTitleLine) {
                Integer count = commentCounts.get(paraIndex);
                if (count != null && count > 0) {
                    String badgeSrc = generateBadgeSrc(count, bookId, chapterId, paraIndex, baseUrl);
                    if (badgeSrc != null) {
                        enriched.append(" <img src=\"").append(badgeSrc)
                                .append("\" style=\"display:inline-block;vertical-align:middle\"/>");
                    }
                }
                paraIndex++;
            }

            enriched.append("</p>\n");
        }

        return enriched.toString().trim();
    }

    private String escapeHtml(String s) {
        return CommonUtils.escapeHtml(s);
    }

    /**
     * 生成段评徽章图片引用。
     * <p>
     * 输出形如：{@code {baseUrl}/api/fqnovel/comment-badge/{count},{"click":"showCmt2(bookId,chapterId,paraIndex)","style":"text"}}。
     * <p>
     * 格式依据阅读魔改版（喵公子 beta / LegadoTeam legado）正文管线的真实实现逆向确定，
     * 其 imgPattern 为 {@code <img[^>]*src="([^"]*(?:"[^>]+\})?)"[^>]*>}——src 为双引号属性，
     * 内部允许一段以 } 结尾的裸双引号 JSON（正则的可选组 {@code "[^>]+\}} 专为此设计）；
     * 提取后按 {@code ,\s*(?=\{} } 切出选项，Gson 解析 {@code Map<String,String>} 取 click/style/width。
     * 因此：
     * <ul>
     *   <li>src 属性必须用<b>双引号</b>包裹，选项 JSON 用<b>裸双引号</b>（不能用 &quot; 实体，
     *       实体不会在正则阶段被解码，Gson 解析失败会导致整段图片被丢弃）；</li>
     *   <li>click 值只传纯数字参数，避免任何引号转义在链路中丢失；</li>
     *   <li>URL 必须是绝对地址，阅读端对正文内相对路径解析不可靠。</li>
     * </ul>
     *
     * @param count      评论数
     * @param bookId     书籍ID（数字串）
     * @param chapterId  章节ID（数字串）
     * @param paraIndex  段落索引
     * @param baseUrl    服务对外基址
     * @return 徽章图片引用；count &le; 0 或 ID 非纯数字时返回 null（保证 click JS 无字符串）
     */
    private String generateBadgeSrc(int count, String bookId, String chapterId, int paraIndex, String baseUrl) {
        if (count <= 0 || baseUrl == null || baseUrl.isEmpty()) {
            return null;
        }
        if (!isNumeric(bookId) || !isNumeric(chapterId)) {
            log.warn("bookId/chapterId 非纯数字，跳过徽章注入以避免 click JS 转义问题: {}, {}", bookId, chapterId);
            return null;
        }

        String clickJs = "showCmt2(" + bookId + "," + chapterId + "," + paraIndex + ")";
        String clickMeta = ",{\"click\":\"" + clickJs + "\",\"style\":\"text\"}";

        return baseUrl + BADGE_PATH + count + clickMeta;
    }

    private boolean isNumeric(String s) {
        return s != null && s.matches("\\d+");
    }
}
