package com.personaltrader.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class LoginThrottleFilterTest {
    private MockHttpServletRequest form(String address) {
        var request=new MockHttpServletRequest("POST","/login");
        request.setServletPath("/login");request.setRemoteAddr(address);return request;
    }

    @Test void fiveFailuresBlockFurtherLoginWithoutEchoingInput()throws Exception {
        var filter=new LoginThrottleFilter();var request=form("127.0.0.2");
        request.setParameter("password","synthetic-form-marker");
        for(int i=0;i<5;i++)filter.failed(request);
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("600");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("LOGIN_RETRY_LATER").doesNotContain("synthetic-form-marker");
        assertThat(chain.getRequest()).isNull();
    }

    @Test void successfulLoginClearsOnlyItsAddress()throws Exception {
        var filter=new LoginThrottleFilter();var first=form("127.0.0.2");var other=form("127.0.0.3");
        for(int i=0;i<5;i++){filter.failed(first);filter.failed(other);}
        filter.succeeded(first);
        var firstChain=new MockFilterChain();filter.doFilter(first,new MockHttpServletResponse(),firstChain);
        var otherResponse=new MockHttpServletResponse();filter.doFilter(other,otherResponse,new MockFilterChain());
        assertThat(firstChain.getRequest()).isSameAs(first);
        assertThat(otherResponse.getStatus()).isEqualTo(429);
    }

    @Test void basicAttemptsUseTheSameFailureLimit()throws Exception {
        var filter=new LoginThrottleFilter();var request=new MockHttpServletRequest("GET","/api/orders");
        request.setRemoteAddr("127.0.0.2");request.addHeader("Authorization","Basic "+"synthetic-marker");
        for(int i=0;i<5;i++)filter.failed(request);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).doesNotContain("synthetic-marker");
    }

    @Test void blockedAddressCanStillReadTheLoginPage()throws Exception {
        var filter=new LoginThrottleFilter();var failed=form("127.0.0.2");
        for(int i=0;i<5;i++)filter.failed(failed);
        var request=new MockHttpServletRequest("GET","/login");request.setRemoteAddr("127.0.0.2");
        var chain=new MockFilterChain();filter.doFilter(request,new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
