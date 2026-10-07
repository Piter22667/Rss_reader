package org.example.rss.controller;

import org.example.rss.dto.FeedSourceForm;
import org.example.rss.model.FeedSource;
import org.example.rss.service.FeedSourceService;
import org.example.rss.service.ArticleImportService;
import org.example.rss.service.ArticleQueryService;
import org.springframework.data.domain.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import java.util.List;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeedSourceControllerTests {
    private final FeedSourceService service = mock(FeedSourceService.class);
    private final ArticleQueryService articleQuery = mock(ArticleQueryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        when(articleQuery.listOwned(anyLong(), anyString(), anyInt())).thenReturn(Page.empty());
        mvc = MockMvcBuilders.standaloneSetup(new FeedSourceController(service,
                mock(ArticleImportService.class), articleQuery))
                .setViewResolvers(views).build();
        views.setApplicationContext(mvc.getDispatcherServlet().getWebApplicationContext());
        when(service.list("owner@example.com")).thenReturn(List.of());
    }

    @Test
    void rendersEmptyHome() throws Exception {
        mvc.perform(get("/").principal(() -> "owner@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ваші новини в одному місці")))
                .andExpect(content().string(containsString("У вас ще немає RSS-джерел")));
    }

    @Test
    void rendersSelectedSourceAndEscapesItsName() throws Exception {
        FeedSource source = new FeedSource();
        source.setId(10L);
        source.setName("<script>alert(1)</script>");
        source.setUrl("https://example.com/feed");
        when(service.list("owner@example.com")).thenReturn(List.of(source));
        when(service.findOwned(10L, "owner@example.com")).thenReturn(source);
        mvc.perform(get("/").param("feedId", "10").principal(() -> "owner@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("Статті ще не завантажено")));
    }

    @Test
    void invalidUrlReturnsFormWithoutSaving() throws Exception {
        mvc.perform(post("/feeds").principal(() -> "owner@example.com")
                        .param("name", "Новини").param("url", "javascript:alert(1)"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Введіть коректний HTTP або HTTPS URL")));
        verify(service, never()).add(any(), anyString());
    }

    @Test
    void validSubmissionRedirectsToCreatedSource() throws Exception {
        FeedSource source = new FeedSource();
        source.setId(10L);
        when(service.add(any(FeedSourceForm.class), eq("owner@example.com"))).thenReturn(source);
        mvc.perform(post("/feeds").principal(() -> "owner@example.com")
                        .param("name", "Новини").param("url", "https://example.com/feed"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/?feedId=10"));
    }

    @Test
    void concurrentDuplicateReturnsFriendlyFormError() throws Exception {
        when(service.add(any(), anyString())).thenThrow(new DataIntegrityViolationException(
                "duplicate key violates unique constraint uq_feed_sources_user_url"));
        mvc.perform(post("/feeds").principal(() -> "owner@example.com")
                        .param("name", "Новини").param("url", "https://example.com/feed"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Це RSS-джерело вже є у вашому списку")));
    }
}
