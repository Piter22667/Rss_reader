package org.example.rss.repository;

import org.example.rss.model.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {
    List<FeedSource> findAllByUser_IdOrderByCreatedAtDesc(Long userId);

    Optional<FeedSource> findByIdAndUser_Id(Long id, Long userId);

    boolean existsByUser_IdAndUrl(Long userId, String url);
}
