package org.example.rss.dto;

public record ArticleListItem(Long id, String title, String link, String description, String publishedAtLabel, String fullText) {}
