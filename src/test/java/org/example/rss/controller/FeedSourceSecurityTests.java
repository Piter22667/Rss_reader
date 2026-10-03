package org.example.rss.controller;

import org.example.rss.config.SecurityConfig;
import org.example.rss.dto.FeedSourceForm;
import org.example.rss.model.FeedSource;
import org.example.rss.service.FeedSourceService;
import org.example.rss.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeedSourceSecurityTests {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FeedSourceService service;

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, FeedSourceController.class})
    static class TestConfig {
        @Bean FeedSourceService feedSourceService() { return mock(FeedSourceService.class); }
        @Bean UserService userService() { return mock(UserService.class); }
        @Bean ViewResolver viewResolver() {
            return new org.springframework.web.servlet.view.InternalResourceViewResolver();
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        service = context.getBean(FeedSourceService.class);
        when(service.list("owner@example.com")).thenReturn(List.of());
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void tearDown() { context.close(); }

    private MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of())));
        return session;
    }

    @Test
    void anonymousVisitorIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verify(service, never()).list(anyString());
    }

    @Test
    void mutationWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/feeds/10/delete").session(authenticatedSession()))
                .andExpect(status().isForbidden());
        verify(service, never()).delete(anyLong(), anyString());
    }

    @Test
    void authenticatedSubmissionWithCsrfIsAccepted() throws Exception {
        MockHttpSession session = authenticatedSession();
        var page = mvc.perform(get("/").session(session)).andExpect(status().isOk()).andReturn();
        CsrfToken token = (CsrfToken) page.getRequest().getAttribute(CsrfToken.class.getName());
        String value = token.getToken();
        FeedSource source = new FeedSource();
        source.setId(10L);
        when(service.add(any(FeedSourceForm.class), eq("owner@example.com"))).thenReturn(source);
        mvc.perform(post("/feeds").session(session).param(token.getParameterName(), value)
                        .param("name", "Новини").param("url", "https://example.com/feed"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/?feedId=10"));
        verify(service).add(any(FeedSourceForm.class), eq("owner@example.com"));
    }
}