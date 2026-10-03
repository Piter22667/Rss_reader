package org.example.rss.service;

/** Invalid, unsupported or oversized feed; no database changes have been made. */
public class FeedParsingException extends RuntimeException {
    public FeedParsingException(String message) {
        super(message);
    }

    public FeedParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}