package org.example.rss.repository;

import org.example.rss.model.SummaryCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SummaryCategoryRepository extends JpaRepository<SummaryCategory, Long> {
    List<SummaryCategory> findByFeedSource_IdOrderByLabelAsc(Long feedSourceId);

    Optional<SummaryCategory> findByFeedSource_IdAndCategoryKey(Long feedSourceId, String categoryKey);
}
