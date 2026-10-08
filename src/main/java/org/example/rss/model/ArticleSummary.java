package org.example.rss.model;

import jakarta.persistence.*;
import lombok.Data;
import org.example.rss.model.enums.SummaryStatus;

import java.time.Instant;

@Entity
@Table(name = "article_summaries")
@Data
public class ArticleSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preference_version_id", nullable = false)
    private PreferenceVersion preferenceVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SummaryStatus status;

    @Column(name = "generated_title")
    private String generatedTitle;

    @Column(name = "summary_text")
    private String summaryText;

    @Column(name = "hidden_reason")
    private String hiddenReason;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}