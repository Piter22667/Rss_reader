package org.example.rss.service;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;

@Service
public class FeedMetadataService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private record Download(byte[] bytes, URI uri) {}

    public String resolveTitle(String feedUrl) {
        URI feedUri = URI.create(feedUrl);
        String title = null;
        try {
            Download download = download(feedUri);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
                @Override public void fatalError(org.xml.sax.SAXParseException ex) throws org.xml.sax.SAXException { throw ex; }
            });
            InputSource source = new InputSource(new ByteArrayInputStream(download.bytes()));
            source.setSystemId(download.uri().toString());
            Element root = builder.parse(source).getDocumentElement();
            Element channel = null;
            if ("feed".equals(root.getLocalName()) && "http://www.w3.org/2005/Atom".equals(root.getNamespaceURI())) {
                channel = root;
            } else if ("rss".equals(root.getNodeName()) || "RDF".equals(root.getLocalName())) {
                channel = child(root, "channel");
            }
            title = text(child(channel, "title"));
            if (title != null) title = Jsoup.parseBodyFragment(title).text().strip();
        } catch (Exception ignored) {
            // An unavailable feed falls back to its domain.
        }
        if (title == null || title.isBlank()) title = feedUri.getHost();
        return title.substring(0, Math.min(title.length(), 255));
    }
    private Download download(URI uri) throws IOException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        for (int redirect = 0; redirect <= 3; redirect++) {
            if (System.nanoTime() > deadline) throw new IOException("Metadata timeout");
            validatePublicHttp(uri);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("User-Agent", "RSSReader/1.0");
            connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/html;q=0.8, */*;q=0.1");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect location");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Unavailable or oversized metadata");
                try (var stream = connection.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_BYTES || System.nanoTime() > deadline) throw new IOException("Metadata limit exceeded");
                        bytes.write(buffer, 0, count);
                    }
                    return new Download(bytes.toByteArray(), uri);
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }

    private void validatePublicHttp(URI uri) throws IOException {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) throw new IOException("Unsupported metadata URL");
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                    || (bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127))
                throw new IOException("Private network metadata URL");
        }
    }

    private Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        return null;
    }

    private String text(Element element) {
        if (element == null) return null;
        String text = element.getTextContent().strip();
        return text.isEmpty() ? null : text;
    }

}
