package com.paicli.platform.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void conflictDoesNotWriteJsonIntoAnSseResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);

        var response = handler.conflict(new IllegalStateException("client disconnected"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void conflictKeepsTheJsonEnvelopeForRegularApis() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        var response = handler.conflict(new IllegalStateException("active run"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(ApiDtos.ErrorResponse.class);
    }
}
