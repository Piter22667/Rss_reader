package org.example.rss.dto;

import java.net.URI;

/** Raw response bytes and the final URL after redirects. */
public record DownloadedFeed(byte[] xml, URI sourceUri, String contentType) {}