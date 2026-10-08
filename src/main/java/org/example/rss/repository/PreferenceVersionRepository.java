package org.example.rss.repository;

import org.example.rss.model.PreferenceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PreferenceVersionRepository extends JpaRepository<PreferenceVersion, Long> {
    List<PreferenceVersion> findByFeedSourceIdOrderByVersionNumberAsc(Long feedSourceId);

    @Query("select max(p.versionNumber) from PreferenceVersion p where p.feedSource.id = :feedSourceId")
    Integer findMaxVersionNumberByFeedSourceId(@Param("feedSourceId") Long feedSourceId);

    Optional<PreferenceVersion> findByFeedSourceIdAndVersionNumber(Long feedSourceId, Integer versionNumber);
}
