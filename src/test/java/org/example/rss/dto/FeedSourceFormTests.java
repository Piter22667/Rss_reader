package org.example.rss.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedSourceFormTests {
    @Test
    void onlyValidHttpUrlsAreAccepted() {
        FeedSourceForm form = new FeedSourceForm();
        for (String url : new String[]{"https://example.com/feed", "http://example.com:8080/rss?topic=java"}) {
            form.setUrl(url);
            assertTrue(form.isValidUrl(), url);
        }
        for (String url : new String[]{"ftp://example.com/feed", "https:///feed", "https://",
                "javascript:alert(1)", "https://user:password@example.com/feed", "https://example.com:99999/feed",
                "https://example.com/with space", "https://example.com/feed#fragment"}) {
            form.setUrl(url);
            assertFalse(form.isValidUrl(), url);
        }
    }

    @Test
    void blankAndOversizedFieldsAreRejected() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            FeedSourceForm form = new FeedSourceForm();
            form.setName("   ");
            form.setUrl("   ");
            assertEquals(1, validator.validate(form).size());
            form.setName("a".repeat(256));
            form.setUrl("https://example.com/" + "a".repeat(2048));
            assertEquals(2, validator.validate(form).size());
        }
    }
}