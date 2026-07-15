package com.paicli.platform.server.security;

import com.paicli.platform.server.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {
    @Test
    void rejectsProtectedApiWithoutKeyAndAcceptsHeaderOrBearer() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties("top-secret"));

        MockHttpServletResponse denied = execute(filter, null, null);
        assertThat(denied.getStatus()).isEqualTo(401);

        MockHttpServletResponse header = execute(filter, "top-secret", null);
        assertThat(header.getStatus()).isEqualTo(200);

        MockHttpServletResponse bearer = execute(filter, null, "Bearer top-secret");
        assertThat(bearer.getStatus()).isEqualTo(200);
    }

    @Test
    void failFastAndManagementProtectionAreConfigurable() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SecurityProperties("", true, true))
                .isInstanceOf(IllegalArgumentException.class);
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties("top-secret", false, true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static MockHttpServletResponse execute(ApiKeyFilter filter, String key, String authorization)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/system/info");
        if (key != null) request.addHeader("X-API-Key", key);
        if (authorization != null) request.addHeader("Authorization", authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
