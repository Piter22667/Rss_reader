package org.example.rss.model;

import jakarta.persistence.*;
import lombok.Data;
import org.example.rss.model.enums.Language;
import org.example.rss.model.enums.SummaryLength;
import org.example.rss.model.enums.SummaryStyle;

import java.time.Instant;

@Entity
@Table(name = "preference_versions")
@Data
public class PreferenceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feed_source_id", nullable = false)
    private FeedSource feedSource;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Language language;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_length", nullable = false)
    private SummaryLength summaryLength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SummaryStyle style;

    @Column(name = "original_content")
    private String originalContent;

    @Column(name = "processed_content")
    private String processedContent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}