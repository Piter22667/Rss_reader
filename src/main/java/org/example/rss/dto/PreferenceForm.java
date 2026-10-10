package org.example.rss.dto;

public class PreferenceForm {
    private String content;
    private String language;
    private String summaryLength;
    private String style;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getSummaryLength() { return summaryLength; }
    public void setSummaryLength(String summaryLength) { this.summaryLength = summaryLength; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
}