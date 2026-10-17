package org.example.rss.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.example.rss.model.enums.MarkdownStatus;

import java.time.Instant;

@Entity
@Table(name = "articles")
@Data
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feed_source_id", nullable = false)
    private FeedSource feedSource;

    @Column(length = 2048)
    private String guid;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, length = 2048)
    private String link;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "full_text", columnDefinition = "text")
    private String fullText;

    @Column(name = "full_text_fetched_at")
    private Instant fullTextFetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "full_text_status", nullable = false, length = 16)
    private MarkdownStatus fullTextStatus = MarkdownStatus.PENDING;

    @Column(name = "full_text_attempted_at")
    private Instant fullTextAttemptedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
