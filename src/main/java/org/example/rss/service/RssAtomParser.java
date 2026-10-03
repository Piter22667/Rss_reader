package org.example.rss.service;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndLink;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.rometools.rome.io.impl.Atom10Parser;
import org.example.rss.dto.ParsedArticle;
import org.example.rss.dto.ParsedFeed;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
public class RssAtomParser {
    private static final int MAX_XML_BYTES = 5 * 1024 * 1024;

    public RssAtomParser() {
        // ROME disables Atom xml:base resolution by default. Enable it once
        // for the application, rather than toggling shared state per request.
        Atom10Parser.setResolveURIs(true);
    }

    /**
     * Parses already downloaded XML. sourceUri is used only to resolve relative
     * article links; this component never makes HTTP requests or writes to the DB.
     * A future HTTP fetcher must also enforce its size limit while downloading.
     */
    public ParsedFeed parse(byte[] xml, URI sourceUri) {
        if (xml == null || xml.length == 0) {
            throw new FeedParsingException("Стрічка порожня");
        }
        if (xml.length > MAX_XML_BYTES) {
            throw new FeedParsingException("Розмір стрічки перевищує 5 МБ");
        }
        if (!isHttpUri(sourceUri)) {
            throw new IllegalArgumentException("sourceUri must be an absolute HTTP or HTTPS URL");
        }

        SyndFeed feed;
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(xml))) {
            // A new instance for each invocation avoids sharing mutable parser state.
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(false);

            InputSource source = new InputSource(reader);
            source.setSystemId(sourceUri.toASCIIString());

            feed = input.build(source);
        } catch (FeedException | IOException | IllegalArgumentException ex) {
            throw new FeedParsingException("Не вдалося прочитати RSS або Atom стрічку", ex);
        }

        List<ParsedArticle> articles = new ArrayList<>();
        int skipped = 0;
        for (SyndEntry entry : feed.getEntries()) { // обробляємо кожну прочитану статттю
            String link = articleLink(entry, sourceUri);
            // Article.link is required by the current database model.
            if (link == null || link.length() > 2048) {
                skipped++;
                continue;
            }

            String title = entry.getTitleEx() == null
                    ? htmlToText(entry.getTitle()) : contentToText(entry.getTitleEx());

            if (title == null){
                title = "Без назви";
            }

            Date published = entry.getPublishedDate() != null
                    ? entry.getPublishedDate() : entry.getUpdatedDate();

            articles.add(new ParsedArticle(
                    trimToNull(entry.getUri()),
                    title,
                    link,
                    contentToText(entry.getDescription()),
                    published == null ? null : published.toInstant(),
                    entryContent(entry)
            ));
        }

        return new ParsedFeed(htmlToText(feed.getTitle()), feed.getFeedType(), articles, skipped);
    }

    private String articleLink(SyndEntry entry, URI sourceUri) {
        // Prefer the human-readable Atom alternate link, not its self/edit/enclosure link.
        for (SyndLink link : entry.getLinks()) {
            String rel = link.getRel();
            String type = link.getType();
            if ((rel == null || rel.isBlank() || "alternate".equalsIgnoreCase(rel))
                    && (type == null || type.isBlank() || "text/html".equalsIgnoreCase(type)
                    || "application/xhtml+xml".equalsIgnoreCase(type))) {
                String resolved = resolveHttpLink(link.getHref(), sourceUri);
                if (resolved != null) return resolved;
            }
        }
        return resolveHttpLink(entry.getLink(), sourceUri);
    }

    private String resolveHttpLink(String value, URI sourceUri) {
        String link = trimToNull(value);
        if (link == null) return null;
        try {
            URI resolved = sourceUri.resolve(new URI(link));
            return isHttpUri(resolved) ? resolved.toASCIIString() : null;
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isHttpUri(URI uri) {
        return uri != null && uri.getHost() != null && uri.getUserInfo() == null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
    }

    private String entryContent(SyndEntry entry) {
        List<String> parts = new ArrayList<>();
        for (SyndContent content : entry.getContents()) {
            String text = contentToText(content);
            if (text != null) parts.add(text);
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private String contentToText(SyndContent content) {
        if (content == null) return null;
        String type = content.getType();
        if (type == null || type.isBlank() || "html".equalsIgnoreCase(type)
                || "xhtml".equalsIgnoreCase(type) || "text/html".equalsIgnoreCase(type)
                || "application/xhtml+xml".equalsIgnoreCase(type)) {
            return htmlToText(content.getValue());
        }
        if ("text".equalsIgnoreCase(type) || "text/plain".equalsIgnoreCase(type)) {
            return trimToNull(content.getValue());
        }
        // Do not treat XML/binary content as article text.
        return null;
    }

    private String htmlToText(String value) {
        if (value == null) return null;
        var document = Jsoup.parseBodyFragment(value);
        document.select("script, style, template, noscript").remove();
        return trimToNull(document.body().text());
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}