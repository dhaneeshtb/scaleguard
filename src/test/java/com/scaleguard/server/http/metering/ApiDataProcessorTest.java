package com.scaleguard.server.http.metering;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiDataProcessorTest {

    @Mock
    private FullHttpRequest mockRequest;

    @Test
    void testParseWithNullRequest() {
        assertThrows(RuntimeException.class, () -> new ApiDataProcessor().parse(null));
    }

    @Test
    void testParseWithEmptyUri() {
        when(mockRequest.uri()).thenReturn("");
        when(mockRequest.method()).thenReturn(HttpMethod.GET);
        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);
        assertEquals("", parsedApiData.getPath());
    }

    @Test
    void testParseWithUrlWithQueryParams() {
        when(mockRequest.uri()).thenReturn("/api?hello=world");
        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);
        assertEquals("/api", parsedApiData.getPath());

        when(mockRequest.uri()).thenReturn("/api");
        assertEquals("/api", new ApiDataProcessor().parse(mockRequest).getPath());
    }

    @Test
    void testParseWithNullMethod() {
        when(mockRequest.uri()).thenReturn("/api/resource");
        when(mockRequest.method()).thenReturn(null);

        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);
        assertNull(parsedApiData.getMethod());
    }

    @Test
    void testParseWithEmptyHeaders() {
        // Mock a request with empty headers
        when(mockRequest.uri()).thenReturn("/api/resource");
        when(mockRequest.method()).thenReturn(HttpMethod.GET);
        when(mockRequest.headers()).thenReturn(new DefaultHttpHeaders());

        // Parsing a request with empty headers should result in empty headers in ApiData
        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);
        assertNotNull(parsedApiData.getHeaders());
        assertTrue(parsedApiData.getHeaders().isEmpty());
    }

    @Test
    void testParseWithNullHeaders() {
        // Mock a request with null headers
        when(mockRequest.uri()).thenReturn("/api/resource");
        when(mockRequest.method()).thenReturn(HttpMethod.GET);
        when(mockRequest.headers()).thenReturn(null);

        // Parsing a request with null headers should result in null headers in ApiData
        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);
        assertTrue(parsedApiData.getHeaders().isEmpty());
    }


    @Test
    void testParseWithNullRequestId() {
        // Mock a request with null request ID (generateRequestId returns null)
        when(mockRequest.uri()).thenReturn("/api/resource");
        when(mockRequest.method()).thenReturn(HttpMethod.GET);
        when(mockRequest.headers()).thenReturn(new DefaultHttpHeaders());

        // Parsing a request with a null request ID should not throw an exception
        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);

        // Ensure that the request ID in ApiData is not null
        assertNotNull(parsedApiData.getId());
    }

    @Test
    void testParseWithNullQueryParameters() {
        // Mock a request with null query parameters
        when(mockRequest.uri()).thenReturn("/api/resource");
        when(mockRequest.method()).thenReturn(HttpMethod.GET);
        when(mockRequest.headers()).thenReturn(new DefaultHttpHeaders());

        // Parsing a request with null query parameters should result in empty requestParams
        ApiData parsedApiData = new ApiDataProcessor().parse(mockRequest);
        assertNotNull(parsedApiData.getRequestParams());
        assertTrue(parsedApiData.getRequestParams().isEmpty());
    }
}