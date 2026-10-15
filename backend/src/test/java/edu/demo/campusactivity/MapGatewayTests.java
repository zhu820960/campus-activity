package edu.demo.campusactivity;

import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapGatewayTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPlaceSearchResults() throws Exception {
        var data = mapper.readTree("""
            {"pois":[{"id":"a","name":"Campus","address":"Road 1","location":"108.9,34.2"},
                     {"id":"b","name":"Broken","location":"bad"}]}
            """);
        var places = MapGateway.places(data);
        assertEquals(1, places.size());
        assertEquals("Campus", places.get(0).name());
        assertEquals(108.9, places.get(0).longitude());
    }

    @Test
    void usesNearbyPoiForReverseGeocoding() throws Exception {
        var data = mapper.readTree("""
            {"regeocode":{"formatted_address":"Road 1","pois":[
              {"name":"Far","distance":"50"},{"name":"Library","distance":"12"}]}}
            """);
        var place = MapGateway.resolvedPlace(data);
        assertEquals("Library", place.name());
        assertEquals("Road 1", place.address());
    }

    @Test
    void jsonpResponseIsExecutableJavaScript() {
        assertEquals("application/javascript; charset=UTF-8",
            MapGateway.contentType("jsonp_123", "application/json"));
    }

    @Test
    void ordinaryResponseKeepsUpstreamContentType() {
        assertEquals("application/json", MapGateway.contentType(null, "application/json"));
    }

    @Test
    void placeSearchUsesServerSideWebServiceKey() {
        URI target = MapGateway.targetUri("v3/place/text",
            Map.of("key", new String[]{"browser-js-key"}, "keywords", new String[]{"campus"},
                "callback", new String[]{"jsonp_1"}), "js-security-code", "server-web-key");
        assertTrue(target.getRawQuery().contains("key=server-web-key"));
        assertFalse(target.getRawQuery().contains("browser-js-key"));
        assertFalse(target.getRawQuery().contains("jscode="));
        assertTrue(target.getRawQuery().contains("callback=jsonp_1"));
    }

    @Test
    void reverseGeocodingUsesServerSideWebServiceKey() {
        URI target = MapGateway.targetUri("v3/geocode/regeo",
            Map.of("key", new String[]{"browser-js-key"}), "js-security-code", "server-web-key");
        assertTrue(target.getRawQuery().contains("key=server-web-key"));
        assertFalse(target.getRawQuery().contains("jscode="));
    }

    @Test
    void mapStyleKeepsJsApiSecurityCode() {
        URI target = MapGateway.targetUri("v4/map/styles",
            Map.of("key", new String[]{"browser-js-key"}), "js-security-code", "server-web-key");
        assertTrue(target.getRawQuery().contains("key=browser-js-key"));
        assertTrue(target.getRawQuery().contains("jscode=js-security-code"));
        assertFalse(target.getRawQuery().contains("server-web-key"));
    }
}
