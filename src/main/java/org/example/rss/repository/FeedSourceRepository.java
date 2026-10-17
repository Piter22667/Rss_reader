package org.example.rss.repository;

import org.example.rss.model.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FeedSource s where s.id = :id and s.user.email = :email")
    Optional<FeedSource> findOwnedForImport(@Param("id") Long id, @Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FeedSource s where s.id = :id")
    Optional<FeedSource> findByIdForImport(@Param("id") Long id);

    List<FeedSource> findAllByActiveForSchedulerTrue();

    List<FeedSource> findAllByUser_IdOrderByCreatedAtDesc(Long userId);

    Optional<FeedSource> findByIdAndUser_Id(Long id, Long userId);

    boolean existsByUser_IdAndUrl(Long userId, String url);
}
