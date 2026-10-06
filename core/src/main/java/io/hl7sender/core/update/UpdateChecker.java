package io.hl7sender.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hl7sender.core.AppInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub Releases whether a newer version exists. It only reads the latest release's version, page link and
 * notes: nothing is downloaded or installed, and no data about the user or their messages is sent (the request
 * carries the app version in its User-Agent). Pre-releases and drafts are ignored.
 *
 * <p>The URL can be changed with {@code -Dhl7sender.updateUrl=...}, for example to an internal mirror.
 */
public final class UpdateChecker {

    public static final String DEFAULT_URL =
            "https://api.github.com/repos/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported"
                    + "/releases/latest";
    private static final Pattern SEMVER =
            Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A published release.
     *
     * @param version     version without the leading {@code v}
     * @param pageUrl     release page with the downloads
     * @param notes       release notes (Markdown)
     * @param publishedAt when it was published, if known
     */
    public record Release(String version, String pageUrl, String notes, Optional<Instant> publishedAt) {
    }

    /**
     * The outcome of a check.
     *
     * @param current running version
     * @param latest  the latest release
     * @param newer   whether {@code latest} is newer than {@code current}
     */
    public record Result(String current, Release latest, boolean newer) {
    }

    private final URI uri;
    private final String currentVersion;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public UpdateChecker(String url, String currentVersion) {
        this.uri = URI.create(url);
        this.currentVersion = currentVersion;
    }

    /** The checker for this build: GitHub Releases, or {@code hl7sender.updateUrl} if set. */
    public static UpdateChecker forThisBuild() {
        return new UpdateChecker(System.getProperty("hl7sender.updateUrl", DEFAULT_URL), AppInfo.version());
    }

    public Result check() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HL7-Sender/" + currentVersion)
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() == 404) {
            throw new IOException("No release has been published yet");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Update server returned HTTP " + response.statusCode());
        }
        JsonNode n = JSON.readTree(response.body());
        String tag = n.path("tag_name").asText("");
        if (!SEMVER.matcher(tag).matches()) {
            throw new IOException("Unexpected release version: " + tag);
        }
        Optional<Instant> published = Optional.empty();
        if (n.hasNonNull("published_at")) {
            try {
                published = Optional.of(Instant.parse(n.path("published_at").asText()));
            } catch (java.time.format.DateTimeParseException ignored) {
                // Informational only.
            }
        }
        Release latest = new Release(tag.startsWith("v") ? tag.substring(1) : tag,
                n.path("html_url").asText(AppInfo.HOMEPAGE + "/releases"), n.path("body").asText(""), published);
        return new Result(currentVersion, latest, isNewer(latest.version(), currentVersion));
    }

    /**
     * Whether {@code candidate} is a later version than {@code current}, by Semantic Versioning precedence
     * (1.0.0-SNAPSHOT &lt; 1.0.0 &lt; 1.0.1). A current version that is not a version at all (a development
     * build) is never considered out of date.
     */
    public static boolean isNewer(String candidate, String current) {
        return SEMVER.matcher(current).matches() && compare(candidate, current) > 0;
    }

    /** Semantic Versioning 2.0 precedence; a leading {@code v} is ignored. */
    public static int compare(String a, String b) {
        Matcher x = SEMVER.matcher(a);
        Matcher y = SEMVER.matcher(b);
        if (!x.matches() || !y.matches()) {
            throw new IllegalArgumentException("Not a version: " + (x.matches() ? b : a));
        }
        for (int i = 1; i <= 3; i++) {
            int c = Long.compare(Long.parseLong(x.group(i)), Long.parseLong(y.group(i)));
            if (c != 0) {
                return c;
            }
        }
        String pa = x.group(4);
        String pb = y.group(4);
        if (pa == null || pb == null) {
            return pa == null ? (pb == null ? 0 : 1) : -1;
        }
        String[] ia = pa.split("\\.");
        String[] ib = pb.split("\\.");
        for (int i = 0; i < Math.min(ia.length, ib.length); i++) {
            boolean na = ia[i].matches("\\d+");
            boolean nb = ib[i].matches("\\d+");
            int c = na && nb ? Long.compare(Long.parseLong(ia[i]), Long.parseLong(ib[i]))
                    : na ? -1 : nb ? 1 : ia[i].compareTo(ib[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(ia.length, ib.length);
    }
}
