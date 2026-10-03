package org.example.rss.service;

import org.example.rss.dto.FeedSourceForm;
import org.example.rss.model.FeedSource;
import org.example.rss.model.User;
import org.example.rss.repository.FeedSourceRepository;
import org.example.rss.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class FeedSourceService {
    private final FeedSourceRepository feedSourceRepository;
    private final UserRepository userRepository;
    private final FeedMetadataService feedMetadataService;

    public FeedSourceService(FeedSourceRepository feedSourceRepository, UserRepository userRepository, FeedMetadataService feedMetadataService) {
        this.feedSourceRepository = feedSourceRepository;
        this.userRepository = userRepository;
        this.feedMetadataService = feedMetadataService;
    }

    public List<FeedSource> list(String email) {
        return feedSourceRepository.findAllByUser_IdOrderByCreatedAtDesc(currentUser(email).getId());
    }

    public FeedSource findOwned(Long id, String email) {
        return feedSourceRepository.findByIdAndUser_Id(id, currentUser(email).getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Джерело не знайдено"));
    }

    @Transactional
    public FeedSource add(FeedSourceForm form, String email) {
        User user = currentUser(email);
        if (feedSourceRepository.existsByUser_IdAndUrl(user.getId(), form.getUrl())) {
            throw new DuplicateFeedSourceException();
        }
        FeedSource source = new FeedSource();
        source.setUser(user);
        String name = form.getName();
        if (name == null || name.isBlank()) name = feedMetadataService.resolveTitle(form.getUrl());
        source.setName(name);
        source.setUrl(form.getUrl());
        source.setCreatedAt(Instant.now());
        return feedSourceRepository.saveAndFlush(source);
    }

    @Transactional
    public void delete(Long id, String email) {
        feedSourceRepository.delete(findOwned(id, email));
        feedSourceRepository.flush();
    }

    private User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public static class DuplicateFeedSourceException extends RuntimeException {
        public DuplicateFeedSourceException() {
            super("Це RSS-джерело вже є у вашому списку");
        }
    }
}