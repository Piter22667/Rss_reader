package org.example.rss.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "summary_categories",
        uniqueConstraints = @UniqueConstraint(name = "uq_summary_categories_source_key",
                columnNames = {"feed_source_id", "category_key"}))
@Data
public class SummaryCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feed_source_id", nullable = false)
    private FeedSource feedSource;

    @Column(name = "category_key", nullable = false, length = 80)
    private String categoryKey;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
