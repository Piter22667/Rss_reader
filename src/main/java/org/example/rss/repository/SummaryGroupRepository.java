package org.example.rss.repository;

import org.example.rss.model.SummaryGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SummaryGroupRepository extends JpaRepository<SummaryGroup, Long> {
    @Query("select g from SummaryGroup g join fetch g.category "
            + "where g.preferenceVersion.id = :preferenceVersionId "
            + "order by g.articleCount desc, g.title asc")
    List<SummaryGroup> findForVersionWithCategory(@Param("preferenceVersionId") Long preferenceVersionId);

    long countByPreferenceVersion_Id(Long preferenceVersionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SummaryGroup g where g.preferenceVersion.id = :preferenceVersionId")
    void deleteByPreferenceVersionId(@Param("preferenceVersionId") Long preferenceVersionId);
}
