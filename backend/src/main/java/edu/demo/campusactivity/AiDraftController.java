package edu.demo.campusactivity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/ai")
class AiDraftController {
    enum Kind { LEAD, INTRO, NOTICE, SUMMARY }
    enum Style { NATURAL, WARM, FORMAL }
    record DraftRequest(Long activityId, @NotNull Kind kind,
                        @Size(max = 500) String extra,
                        @Size(max = 160) String title,
                        @Size(max = 200) String location,
                        Instant startsAt, Instant endsAt,
                        @Size(max = 5000) String description,
                        @Size(max = 5000) String previousDraft,
                        @Size(max = 120) String revision,
                        Style style) {}

    private final ActivityService activities;
    private final CurrentUser current;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String model;

    AiDraftController(ActivityService activities, CurrentUser current, ObjectMapper json,
                      @Value("${app.deepseek.api-key:}") String apiKey,
                      @Value("${app.deepseek.model:deepseek-flash}") String model) {
        this.activities = activities;
        this.current = current;
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
    }

    @PostMapping("/draft")
    Map<String, String> draft(@Valid @RequestBody DraftRequest request) {
        current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        Activity activity = request.activityId() == null ? null : activities.owned(request.activityId());
        if ((request.title() == null || request.title().isBlank()) && activity == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请先填写活动名称");
        }
        if (apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置 DeepSeek API Key");
        }
        try {
            String body = json.writeValueAsString(Map.of(
                "model", model,
                "thinking", Map.of("type", "disabled"),
                "max_tokens", 600,
                "messages", List.of(
                    Map.of("role", "system", "content", "你是高校校园活动编辑。只依据提供的事实写中文纯文本；不得编造或暗示未确认的嘉宾、讲座、咨询、聊天、互动、练习、参与收获、学分、证书、报名规则或活动结果。可以用与主题相关的情绪或问题自然开场，但不要承诺现场会做什么。面向学生，语言自然、有节奏；避免照抄标题、反复罗列时间地点和‘本次活动旨在’等套话。信息少就写短，不靠空话凑篇幅。只输出可供人工修改的文案，不附解释或 Markdown。"),
                    Map.of("role", "user", "content", userPrompt(request, activity)))));
            HttpRequest call = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(call, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode());
            JsonNode choice = json.readTree(response.body()).path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "生成内容未完整返回，请缩短要求后重试");
            }
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "接口未返回可用文案");
            }
            return Map.of("draft", content.asText().trim());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "文案生成已中断");
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "连接文案服务失败");
        }
    }

    static void requireSuccess(int status) {
        if (status == 200) return;
        if (status == 401) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DeepSeek API Key 无效，请检查本机配置");
        if (status == 402) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DeepSeek 账户余额不足，暂无法生成文案");
        if (status == 429) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "文案请求过于频繁，请稍后重试");
        throw new ApiException(HttpStatus.BAD_GATEWAY, "文案生成暂时不可用，请稍后重试");
    }

    static String userPrompt(DraftRequest request, Activity activity) {
        String task = switch (request.kind()) {
            case LEAD -> "写推文导语：一到两句，40至80字。用与主题相关的自然切入或邀请，不以活动名称开头；不要像日程通知一样把时间、地点、时长逐项重复。未确认活动形式时，成稿只写主题、邀请和已知信息，不描写现场会聊、听、讲、看、练、体验、交流或分享什么，也不承诺收获。";
            case INTRO -> "写活动简介：说明已知主题与参与信息，段落简短；不要重复导语句式。素材不足时不强行扩写。";
            case NOTICE -> "写报名须知：只整理已经明确的时间、地点和要求；没有提供的报名限制或携带物品不要补造。";
            case SUMMARY -> "写活动总结草稿：如没有活动结果，只提供可供事后补充的简短框架，不得写成活动已经完成。";
        };
        String style = switch (request.style() == null ? Style.NATURAL : request.style()) {
            case NATURAL -> "自然、真诚，像校园编辑写给同学";
            case WARM -> "温暖、亲切，但不夸张煽情";
            case FORMAL -> "简洁、正式，但避免公文套话";
        };
        StringBuilder prompt = new StringBuilder(task).append("\n语气：").append(style)
            .append("\n已知事实：\n").append(facts(request, activity));
        if (request.previousDraft() != null && !request.previousDraft().isBlank()) {
            prompt.append("\n现有草稿（仅供改写，不要照抄）：\n").append(request.previousDraft().trim());
            appendIfPresent(prompt, "修改方向", request.revision());
            prompt.append("\n请保留事实，换一种表达方式。只输出改写后的完整草稿。");
        }
        return prompt.toString();
    }

    static String facts(DraftRequest request, Activity activity) {
        StringBuilder facts = new StringBuilder();
        appendIfPresent(facts, "活动名称", textOrSaved(request.title(), activity == null ? null : activity.title));
        appendIfPresent(facts, "地点", textOrSaved(request.location(), activity == null ? null : activity.location));
        appendIfPresent(facts, "开始时间", timeOrSaved(request.startsAt(), activity == null ? null : activity.startsAt));
        appendIfPresent(facts, "结束时间", timeOrSaved(request.endsAt(), activity == null ? null : activity.endsAt));
        appendIfPresent(facts, "活动说明（提取事实，不照抄句式）",
            textOrSaved(request.description(), activity == null ? null : activity.description));
        appendIfPresent(facts, "补充信息", request.extra());
        return facts.toString();
    }

    private static void appendIfPresent(StringBuilder target, String label, String value) {
        if (value != null && !value.isBlank()) target.append(label).append("：").append(value.trim()).append('\n');
    }

    private static String textOrSaved(String current, String saved) {
        return current == null || current.isBlank() ? saved : current.trim();
    }

    private static String timeOrSaved(Instant current, Instant saved) {
        Instant value = current == null ? saved : current;
        return value == null ? null : value.toString();
    }
}
