package org.example.rss.service;

public class FeedDownloadException extends RuntimeException {
    public FeedDownloadException(String message) { super(message); }
    public FeedDownloadException(String message, Throwable cause) { super(message, cause); }
}