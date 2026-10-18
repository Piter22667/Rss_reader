package org.example.rss.repository;

import org.example.rss.model.ArticleSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.example.rss.model.enums.SummaryStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArticleSummaryRepository extends JpaRepository<ArticleSummary, Long> {
    Optional<ArticleSummary> findByArticle_IdAndPreferenceVersion_Id(Long articleId, Long preferenceId);
    long countByPreferenceVersion_Id(Long preferenceId);

    @Query("select s from ArticleSummary s where s.preferenceVersion.id = :preferenceId and s.status = :status "
            + "order by coalesce(s.article.publishedAt, s.article.createdAt) desc, s.article.id desc")
    Page<ArticleSummary> findResults(@Param("preferenceId") Long preferenceId,
                                     @Param("status") SummaryStatus status, Pageable pageable);

    long countByPreferenceVersion_IdAndStatus(Long preferenceId, SummaryStatus status);

    List<ArticleSummary> findByPreferenceVersion_IdAndStatusOrderByCreatedAtDesc(Long preferenceId, SummaryStatus status);

    List<ArticleSummary> findByPreferenceVersion_IdAndArticle_IdIn(Long preferenceId, List<Long> articleIds);

    @Query("select count(s) from ArticleSummary s where s.preferenceVersion.id = :preferenceId "
            + "and s.status = :status and (coalesce(s.article.publishedAt, s.article.createdAt) > :date "
            + "or (coalesce(s.article.publishedAt, s.article.createdAt) = :date and s.article.id > :articleId))")
    long countBefore(@Param("preferenceId") Long preferenceId, @Param("status") SummaryStatus status,
                     @Param("date") Instant date, @Param("articleId") Long articleId);
}
