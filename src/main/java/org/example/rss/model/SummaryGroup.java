package org.example.rss.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "summary_groups")
@Data
public class SummaryGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feed_source_id", nullable = false)
    private FeedSource feedSource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preference_version_id", nullable = false)
    private PreferenceVersion preferenceVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private SummaryCategory category;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "summary_text", nullable = false, columnDefinition = "text")
    private String summaryText;

    @Column(name = "article_count", nullable = false)
    private Integer articleCount;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
