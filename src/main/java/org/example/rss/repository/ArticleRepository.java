package org.example.rss.repository;

import org.example.rss.model.Article;
import org.example.rss.model.enums.MarkdownStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    @Query("select count(a) > 0 from Article a where a.feedSource.id = :sourceId "
            + "and (a.link = :link or (:guid is not null and a.guid = :guid))")
    boolean existsInSource(@Param("sourceId") Long sourceId, @Param("guid") String guid,
                           @Param("link") String link);

    @Query("select a from Article a where a.feedSource.id = :sourceId "
            + "and (a.link = :link or (:guid is not null and a.guid = :guid))")
    java.util.Optional<Article> findInSource(@Param("sourceId") Long sourceId, @Param("guid") String guid,
                                            @Param("link") String link);

    @Query(value = "select a from Article a where a.feedSource.id = :sourceId "
            + "and a.feedSource.user.email = :email order by coalesce(a.publishedAt, a.createdAt) desc, a.id desc",
            countQuery = "select count(a) from Article a where a.feedSource.id = :sourceId "
                    + "and a.feedSource.user.email = :email")
    Page<Article> findOwned(@Param("sourceId") Long sourceId, @Param("email") String email, Pageable pageable);

    long countByFeedSourceId(Long feedSourceId);

    @Query("select a from Article a where a.feedSource.id = :sourceId "
            + "and a.fullText is null and a.fullTextStatus <> :failed "
            + "order by coalesce(a.publishedAt, a.createdAt) desc, a.id desc")
    List<Article> findMarkdownCandidates(@Param("sourceId") Long sourceId,
                                         @Param("failed") MarkdownStatus failed,
                                         Pageable pageable);
}
