package edu.demo.campusactivity;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class WechatCodeExchange {
    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";

    private final String appId;
    private final String appSecret;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    WechatCodeExchange(@Value("${app.wechat.app-id:}") String appId,
                       @Value("${app.wechat.app-secret:}") String appSecret, ObjectMapper json) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.json = json;
    }

    String exchange(String code) {
        if (code == null || code.isBlank() || code.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "微信登录凭证无效");
        }
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录尚未配置");
        }
        String query = "appid=" + encode(appId) + "&secret=" + encode(appSecret)
            + "&js_code=" + encode(code) + "&grant_type=authorization_code";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "?" + query))
                .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "微信认证暂不可用");
            }
            return readOpenId(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "微信认证已中断");
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "微信认证连接失败");
        }
    }

    String readOpenId(String body) {
        try {
            JsonNode response = json.readTree(body);
            JsonNode openId = response.path("openid");
            if (!openId.isTextual() || openId.asText().isBlank() || response.path("errcode").isNumber()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "微信登录凭证已失效，请重试");
            }
            return openId.asText();
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "微信认证返回数据无效");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
