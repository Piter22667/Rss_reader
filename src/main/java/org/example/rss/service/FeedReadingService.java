package org.example.rss.service;

import org.example.rss.dto.DownloadedFeed;
import org.example.rss.dto.ParsedFeed;
import org.springframework.stereotype.Service;

/** Connects downloading and parsing. Does not import articles into the database. */
@Service
public class FeedReadingService {
    private final FeedDownloadService downloader;
    private final RssAtomParser parser;

    public FeedReadingService(FeedDownloadService downloader, RssAtomParser parser) {
        this.downloader = downloader;
        this.parser = parser;
    }

    public ParsedFeed read(String feedUrl) {
        DownloadedFeed feed = downloader.download(feedUrl);
        return parser.parse(feed.xml(), feed.sourceUri(), feed.contentType());
    }
}