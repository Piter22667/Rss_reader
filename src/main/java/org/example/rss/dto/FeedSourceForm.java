package org.example.rss.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.URISyntaxException;

public class FeedSourceForm {
    @NotBlank(message = "Вкажіть назву джерела")
    @Size(max = 255, message = "Назва має містити не більше 255 символів")
    private String name;

    @NotBlank(message = "Вкажіть RSS URL")
    @Size(max = 2048, message = "URL має містити не більше 2048 символів")
    private String url;

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.strip(); }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url == null ? null : url.strip(); }

    @AssertTrue(message = "Введіть коректний HTTP або HTTPS URL, наприклад https://example.com/feed")
    public boolean isValidUrl() {
        if (url == null || url.isBlank()) return true;
        try {
            URI uri = new URI(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}