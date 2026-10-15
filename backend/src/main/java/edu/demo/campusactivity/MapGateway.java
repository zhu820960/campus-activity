package edu.demo.campusactivity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MapGateway {
    private static final String PREFIX = "/_AMapService/";
    private final String jsKey;
    private final String securityJsCode;
    private final String webServiceKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    MapGateway(@Value("${app.amap.js-key:}") String jsKey,
               @Value("${app.amap.security-js-code:}") String securityJsCode,
               @Value("${app.amap.web-service-key:}") String webServiceKey) {
        this.jsKey = jsKey;
        this.securityJsCode = securityJsCode;
        this.webServiceKey = webServiceKey;
    }

    @GetMapping("/api/map/config")
    Map<String, String> config() {
        requireConfigured();
        return Map.of("key", jsKey);
    }

    record Place(String id, String name, String address, double longitude, double latitude) {}
    record ResolvedPlace(String name, String address) {}

    @GetMapping("/api/map/search")
    List<Place> search(@RequestParam String keyword) {
        requireWebService();
        String query = keyword.trim();
        if (query.isEmpty() || query.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请输入有效的地点关键词");
        }
        URI uri = UriComponentsBuilder.fromUriString("https://restapi.amap.com/v3/place/text")
            .queryParam("keywords", query).queryParam("offset", 8)
            .queryParam("extensions", "base").queryParam("key", webServiceKey)
            .build().encode().toUri();
        return places(fetchJson(uri));
    }

    @GetMapping("/api/map/reverse")
    ResolvedPlace reverse(@RequestParam double longitude, @RequestParam double latitude) {
        requireWebService();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
            || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无效的地图坐标");
        }
        URI uri = UriComponentsBuilder.fromUriString("https://restapi.amap.com/v3/geocode/regeo")
            .queryParam("location", longitude + "," + latitude)
            .queryParam("extensions", "all").queryParam("radius", 100)
            .queryParam("key", webServiceKey).build().encode().toUri();
        return resolvedPlace(fetchJson(uri));
    }

    private JsonNode fetchJson(URI uri) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "地点服务暂时不可用");
            }
            JsonNode data = mapper.readTree(response.body());
            if (!"1".equals(data.path("status").asText())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "地点服务暂时不可用");
            }
            return data;
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "地点服务暂时不可用");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "地点服务请求已中断");
        }
    }

    static List<Place> places(JsonNode data) {
        List<Place> places = new ArrayList<>();
        for (JsonNode poi : data.path("pois")) {
            String[] coordinates = poi.path("location").asText("").split(",");
            if (coordinates.length != 2) continue;
            try {
                places.add(new Place(poi.path("id").asText(""), poi.path("name").asText(""),
                    poi.path("address").asText(""), Double.parseDouble(coordinates[0]),
                    Double.parseDouble(coordinates[1])));
            } catch (NumberFormatException ignored) {
                // Skip malformed upstream POIs without dropping valid results.
            }
        }
        return places;
    }

    static ResolvedPlace resolvedPlace(JsonNode data) {
        JsonNode geocode = data.path("regeocode");
        String address = geocode.path("formatted_address").asText("");
        if (address.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "未能解析该位置");
        }
        for (JsonNode poi : geocode.path("pois")) {
            if (poi.path("distance").asDouble(Double.POSITIVE_INFINITY) <= 30
                && !poi.path("name").asText("").isBlank()) {
                return new ResolvedPlace(poi.path("name").asText(), address);
            }
        }
        return new ResolvedPlace(address, address);
    }

    @GetMapping("/_AMapService/**")
    ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        requireConfigured();
        String path = request.getRequestURI().substring(PREFIX.length());
        if (!path.matches("v[345]/[A-Za-z0-9/_-]+") || path.contains("//")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无效的地图服务路径");
        }
        String query = request.getQueryString();
        if ((query != null && query.length() > 4096)
            || request.getParameterMap().keySet().stream().anyMatch(key -> key.equalsIgnoreCase("jscode"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无效的地图服务参数");
        }
        String callback = request.getParameter("callback");
        if (callback != null && !callback.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无效的地图服务回调");
        }
        if (usesWebServiceKey(path) && webServiceKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "地点服务尚未配置");
        }
        URI target = targetUri(path, request.getParameterMap(), securityJsCode, webServiceKey);
        try {
            HttpRequest upstream = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = client.send(upstream, HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(response.statusCode())
                .header("Content-Type", contentType(callback,
                    response.headers().firstValue("Content-Type").orElse("application/octet-stream")))
                .body(response.body());
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "地图服务暂时不可用");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "地图服务请求已中断");
        }
    }

    static String contentType(String callback, String upstreamContentType) {
        return callback == null ? upstreamContentType : "application/javascript; charset=UTF-8";
    }

    static URI targetUri(String path, Map<String, String[]> parameters, String securityJsCode,
                         String webServiceKey) {
        boolean webService = usesWebServiceKey(path);
        String host = path.equals("v4/map/styles") ? "https://webapi.amap.com/" : "https://restapi.amap.com/";
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(host + path);
        parameters.forEach((name, values) -> {
            if (name.equalsIgnoreCase("jscode") || (webService && name.equalsIgnoreCase("key"))) return;
            for (String value : values) uri.queryParam(name, value);
        });
        if (webService) uri.queryParam("key", webServiceKey);
        else uri.queryParam("jscode", securityJsCode);
        return uri.build().encode().toUri();
    }

    private static boolean usesWebServiceKey(String path) {
        return path.startsWith("v3/place/") || path.startsWith("v3/geocode/");
    }

    private void requireConfigured() {
        if (jsKey.isBlank() || securityJsCode.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "地图服务尚未配置");
        }
    }

    private void requireWebService() {
        if (webServiceKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "地点服务尚未配置");
        }
    }
}
