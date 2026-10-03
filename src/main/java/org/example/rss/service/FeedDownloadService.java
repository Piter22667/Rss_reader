package org.example.rss.service;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.example.rss.dto.DownloadedFeed;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

@Service
public class FeedDownloadService {
    private static final int MAX_XML_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    public DownloadedFeed download(String url) {
        URI current = parseUri(url);
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicAddressResolver())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10))
                        .setSocketTimeout(Timeout.ofSeconds(15)).build())
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(Timeout.ofSeconds(15)).build())
                .build();
        var requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(15)).build();

        // Redirects are checked manually. No cookies, automatic retries or system proxy.
        try (var client = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .setUserAgent("AI-RSS-Reader/1.0")
                .build()) {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validateUri(current);
                URI requestedUri = current;
                HttpGet request = new HttpGet(current);
                request.setHeader("Accept",
                        "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.1");

                Step result = client.execute(request, response -> {
                    int status = response.getCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        var location = response.getFirstHeader("Location");
                        if (location == null || location.getValue().isBlank()) {
                            throw new FeedDownloadException("Сайт повернув перенаправлення без адреси");
                        }
                        URI next;
                        try {
                            next = requestedUri.resolve(new URI(location.getValue().strip()));
                        } catch (URISyntaxException | IllegalArgumentException ex) {
                            throw new FeedDownloadException("Сайт повернув некоректну адресу перенаправлення", ex);
                        }
                        validateUri(next);
                        if ("https".equalsIgnoreCase(requestedUri.getScheme())
                                && "http".equalsIgnoreCase(next.getScheme())) {
                            throw new FeedDownloadException("Перенаправлення з HTTPS на HTTP заборонене");
                        }
                        // Do not download an arbitrary redirect response body.
                        request.cancel();
                        return new Step(next, null);
                    }
                    if (status != 200) {
                        request.cancel();
                        throw new FeedDownloadException("Не вдалося завантажити стрічку: HTTP " + status);
                    }
                    var entity = response.getEntity();
                    if (entity == null) throw new FeedDownloadException("Сайт повернув порожню стрічку");
                    if (entity.getContentLength() > MAX_XML_BYTES) {
                        request.cancel();
                        throw new FeedDownloadException("Розмір стрічки перевищує 5 МБ");
                    }
                    try {
                        byte[] xml = readLimited(entity.getContent(), request);
                        if (xml.length == 0) throw new FeedDownloadException("Сайт повернув порожню стрічку");
                        return new Step(null, new DownloadedFeed(xml, requestedUri, entity.getContentType()));
                    } catch (IOException | RuntimeException ex) {
                        request.cancel();
                        throw ex;
                    }
                });
                if (result.feed() != null) return result.feed();
                current = result.redirect();
            }
            throw new FeedDownloadException("Стрічка має забагато перенаправлень");
        } catch (IOException ex) {
            throw new FeedDownloadException(
                    "Не вдалося завантажити стрічку. Перевірте адресу та доступність сайту", ex);
        }
    }

    private byte[] readLimited(InputStream input, HttpGet request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while ((count = input.read(buffer)) != -1) {
            if (System.nanoTime() > deadline) {
                request.cancel();
                throw new FeedDownloadException("Перевищено час завантаження стрічки");
            }
            if (output.size() + count > MAX_XML_BYTES) {
                request.cancel();
                throw new FeedDownloadException("Розмір стрічки перевищує 5 МБ");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private URI parseUri(String url) {
        if (url == null || url.isBlank()) throw new FeedDownloadException("Вкажіть URL стрічки");
        try {
            URI uri = new URI(url.strip());
            validateUri(uri);
            return uri;
        } catch (URISyntaxException ex) {
            throw new FeedDownloadException("Некоректний URL стрічки", ex);
        }
    }

    private static void validateUri(URI uri) {
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !(uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)) {
            throw new FeedDownloadException("Потрібен коректний HTTP або HTTPS URL стрічки");
        }
    }

    private record Step(URI redirect, DownloadedFeed feed) {}

    /**
     * Validate the addresses used by the actual connection, not a separate DNS lookup.
     * This also prevents redirects to localhost, private networks and metadata endpoints.
     */
    private static class PublicAddressResolver implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new FeedDownloadException("Завантаження з локальних або службових адрес заборонене");
                }
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            // Avoid a second lookup that might resolve the host to another address.
            return host;
        }

        private boolean isPublic(InetAddress address) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) return false;
            byte[] bytes = address.getAddress();
            int a = bytes[0] & 255;
            int b = bytes[1] & 255;
            if (bytes.length == 4) {
                int c = bytes[2] & 255;
                return !(a == 0 || a == 10 || a == 127 || a >= 224
                        || a == 100 && b >= 64 && b <= 127
                        || a == 169 && b == 254
                        || a == 172 && b >= 16 && b <= 31
                        || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2))
                        || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                        || a == 203 && b == 0 && c == 113);
            }
            // Only global unicast IPv6, excluding documentation and transition ranges.
            return bytes.length == 16 && (a & 224) == 32
                    && !(a == 32 && b == 2)
                    && !(a == 32 && b == 1 && bytes[2] == 0 && bytes[3] == 0)
                    && !(a == 32 && b == 1 && (bytes[2] & 255) == 13 && (bytes[3] & 255) == 184);
        }
    }
}