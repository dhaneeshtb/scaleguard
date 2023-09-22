package com.scaleguard.server.http.utils;

import com.scaleguard.server.http.metering.MetricRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpUtilsTest {

    @Test
    void testGetParameters() {
        String uri = "/example?param1=value1&param2=value2&param2=value3";
        Map<String, List<String>> parameters = HttpUtils.getParameters(uri);

        assertEquals(2, parameters.size(), "Size of parameter map should be 2");
        assertTrue(parameters.get("param1").contains("value1"), "param1 should contain value1");
        assertTrue(parameters.get("param2").contains("value2"), "param2 should contain value2");
        assertTrue(parameters.get("param2").contains("value3"), "param2 should contain value3");
    }

    @Test
    void testGetParametersAsString() {
        String uri = "/example?param1=value1&param2=value2,value3";
        Map<String, String> parameters = HttpUtils.getParametersAsString(uri);

        assertEquals(2, parameters.size(), "Size of parameter map should be 2");
        assertEquals("value1", parameters.get("param1"), "param1 should be [value1]");
        assertEquals("value2,value3", parameters.get("param2"), "param2 should be [value2, value3]");
    }

    @Test
    void testGetParametersNoParams() {
        String uri = "/example";
        Map<String, List<String>> parameters = HttpUtils.getParameters(uri);
        assertTrue(parameters.isEmpty(), "Parameter map should be empty");
    }

    @Test
    void testGetParametersAsStringNoParams() {
        String uri = "/example";
        Map<String, String> parameters = HttpUtils.getParametersAsString(uri);
        assertTrue(parameters.isEmpty(), "Parameter map should be empty");
    }

    @Test
    void testGetParametersAs() {
        String uri = "/example?path=/test&target=targetValue&method=GET&status=200&time=1&timeUnit=minute";

        MetricRequest metricRequest = HttpUtils.getParametersAs(uri, MetricRequest.class);

        assertNotNull(metricRequest, "MetricRequest should not be null");
        assertEquals("/test", metricRequest.getPath(), "Path should be /test");
        assertEquals("targetValue", metricRequest.getTarget(), "Target should be targetValue");
        assertEquals("GET", metricRequest.getMethod(), "Method should be GET");
        assertEquals("200", metricRequest.getStatus(), "Status should be 200");
    }


}