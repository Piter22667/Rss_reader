package org.example.rss.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "articles")
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feed_source_id", nullable = false)
    private FeedSource feedSource;

    @Column(length = 2048)
    private String guid;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 2048)
    private String link;

    private String description;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "full_text")
    private String fullText;

    @Column(name = "full_text_fetched_at")
    private Instant fullTextFetchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}