package org.example.rss.service;

public class OpenRouterException extends RuntimeException {
    public OpenRouterException(String message) {
        super(message);
    }

    public OpenRouterException(String message, Throwable cause) {
        super(message, cause);
    }
}