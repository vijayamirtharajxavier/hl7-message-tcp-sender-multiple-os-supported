package io.hl7sender.core.update;

import io.hl7sender.core.AppInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Downloads a newer release and installs it over this copy of HL7 Sender.
 *
 * <p>It works out how this copy was installed ({@link #detect()}), downloads the matching installer from the
 * release, checks it against the release's {@code SHA256SUMS} file, and writes a small script that waits for the
 * app to exit and then installs it:
 *
 * <ul>
 *   <li>Windows: the {@code .msi}, with {@code msiexec /passive} into the same folder (no administrator rights,
 *       as the installer is per user)</li>
 *   <li>macOS: the app from the {@code .dmg} replaces the app bundle; if the bundle is not writable (installed from
 *       the {@code .pkg}), the {@code .pkg} opens in Installer, which asks for a password</li>
 *   <li>Linux: the {@code .deb} or {@code .rpm} through the package manager, with {@code pkexec} asking for a
 *       password; an AppImage or the portable folder is replaced in place</li>
 * </ul>
 *
 * <p>Settings, destinations and the queue live outside the installation and are kept; the queue database is
 * migrated when the new version starts. Administrators can turn installing off with
 * {@code -Dhl7sender.updateInstall=false}. Downloads must use HTTPS, except from this computer (a test mirror).
 */
public final class UpdateInstaller {

    /** System property: {@code false} turns installing updates off (checking still works). */
    public static final String ENABLED_PROPERTY = "hl7sender.updateInstall";

    /** How this copy was installed, which decides the installer to download and how to run it. */
    public enum Kind {
        WINDOWS_MSI(".msi", "SHA256SUMS-Windows.txt"),
        MAC_APP(".dmg", "SHA256SUMS-macOS.txt"),
        MAC_PKG(".pkg", "SHA256SUMS-macOS.txt"),
        LINUX_DEB("_amd64.deb", "SHA256SUMS-Linux.txt"),
        LINUX_RPM(".x86_64.rpm", "SHA256SUMS-Linux.txt"),
        APPIMAGE("-x86_64.AppImage", "SHA256SUMS-Linux.txt"),
        PORTABLE("-linux-x64.tar.gz", "SHA256SUMS-Linux.txt");

        private final String suffix;
        private final String checksums;

        Kind(String suffix, String checksums) {
            this.suffix = suffix;
            this.checksums = checksums;
        }
    }

    /** Why this copy cannot install updates itself. */
    public enum Unsupported {
        /** Turned off with {@code -Dhl7sender.updateInstall=false}. */
        DISABLED,
        /** Not started from an installed copy (a development build). */
        DEVELOPMENT_BUILD,
        /** No installer is published for this system, e.g. an Intel Mac or ARM Linux. */
        PLATFORM,
        /** Installed in a way it cannot update, e.g. a system package without {@code pkexec}. */
        INSTALLATION
    }

    /**
     * This copy of HL7 Sender.
     *
     * @param kind     how it was installed
     * @param location what the update replaces: the install folder, app bundle or AppImage file
     * @param launcher what opens the desktop app again after the update
     */
    public record Installation(Kind kind, Path location, Path launcher) {
    }

    /** The outcome of {@link #detect()}: an installation it can update, or why not. */
    public record Detection(Optional<Installation> installation, Optional<Unsupported> unsupported) {

        static Detection of(Installation i) {
            return new Detection(Optional.of(i), Optional.empty());
        }

        static Detection not(Unsupported why) {
            return new Detection(Optional.empty(), Optional.of(why));
        }
    }

    /**
     * A downloaded and verified installer, ready to install.
     *
     * @param version      the version it installs
     * @param installation the copy it updates
     * @param file         the installer
     */
    public record Prepared(String version, Installation installation, Path file) {
    }

    private static final String READY = "ready.sha256";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Installation installation;
    private final Path updatesDir;

    /**
     * @param installation the copy to update
     * @param updatesDir   where downloads are kept until they are installed
     */
    public UpdateInstaller(Installation installation, Path updatesDir) {
        this.installation = installation;
        this.updatesDir = updatesDir;
    }

    public Installation installation() {
        return installation;
    }

    // --- Detection -------------------------------------------------------------------------------------

    /** How the running copy was installed. */
    public static Detection detect() {
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "").trim())) {
            return Detection.not(Unsupported.DISABLED);
        }
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                System.getProperty("jpackage.app-path", ""), System.getenv(),
                "root".equals(System.getProperty("user.name")), UpdateInstaller::isWritable,
                UpdateInstaller::onPath, UpdateInstaller::succeeds);
    }

    /**
     * How a copy was installed, from the operating system, the launcher's path (set by the jpackage launcher) and
     * the environment. The checks for writable paths, programs and commands are passed in so this can be tested.
     */
    static Detection detect(String osName, String arch, String appPath, Map<String, String> env, boolean root,
                            Predicate<Path> writable, Predicate<String> onPath, Predicate<List<String>> succeeds) {
        if (appPath == null || appPath.isBlank()) {
            return Detection.not(Unsupported.DEVELOPMENT_BUILD);
        }
        Path launcher = Path.of(appPath);
        String os = osName.toLowerCase(Locale.ROOT);
        String a = arch.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            int cut = Math.max(appPath.lastIndexOf('\\'), appPath.lastIndexOf('/'));
            if (cut <= 0) {
                return Detection.not(Unsupported.INSTALLATION);
            }
            String dir = appPath.substring(0, cut);
            return Detection.of(new Installation(Kind.WINDOWS_MSI, Path.of(dir),
                    Path.of(dir + appPath.charAt(cut) + AppInfo.NAME + ".exe")));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            if (!a.equals("aarch64") && !a.equals("arm64")) {
                return Detection.not(Unsupported.PLATFORM);
            }
            Path bundle = launcher;
            while (bundle != null && !fileName(bundle).endsWith(".app")) {
                bundle = bundle.getParent();
            }
            if (bundle == null || bundle.getParent() == null) {
                return Detection.not(Unsupported.INSTALLATION);
            }
            boolean replaceable = writable.test(bundle) && writable.test(bundle.getParent());
            return Detection.of(new Installation(replaceable ? Kind.MAC_APP : Kind.MAC_PKG, bundle, bundle));
        }
        if (!a.equals("amd64") && !a.equals("x86_64")) {
            return Detection.not(Unsupported.PLATFORM);
        }
        String appImage = env.get("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) {
            Path file = Path.of(appImage);
            return file.getParent() != null && writable.test(file) && writable.test(file.getParent())
                    ? Detection.of(new Installation(Kind.APPIMAGE, file, file))
                    : Detection.not(Unsupported.INSTALLATION);
        }
        Path bin = launcher.getParent();
        Path dir = bin == null ? null : bin.getParent();
        if (dir == null || dir.getParent() == null) {
            return Detection.not(Unsupported.INSTALLATION);
        }
        Path gui = dir.resolve("bin").resolve("hl7-sender");
        boolean elevate = root || onPath.test("pkexec");
        if (dir.equals(Path.of("/opt/hl7-sender"))) {
            if (succeeds.test(List.of("dpkg-query", "-W", "hl7-sender"))) {
                return elevate ? Detection.of(new Installation(Kind.LINUX_DEB, dir, gui))
                        : Detection.not(Unsupported.INSTALLATION);
            }
            if (succeeds.test(List.of("rpm", "-q", "hl7-sender"))) {
                return elevate ? Detection.of(new Installation(Kind.LINUX_RPM, dir, gui))
                        : Detection.not(Unsupported.INSTALLATION);
            }
        }
        return writable.test(dir) && writable.test(dir.getParent())
                ? Detection.of(new Installation(Kind.PORTABLE, dir, gui))
                : Detection.not(Unsupported.INSTALLATION);
    }

    private static boolean isWritable(Path p) {
        return Files.isWritable(p);
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, program))) {
                return true;
            }
        }
        return false;
    }

    private static boolean succeeds(List<String> command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // --- Choosing and downloading ----------------------------------------------------------------------

    /** The installer {@code release} publishes for {@code kind}, if any. */
    public static Optional<UpdateChecker.Asset> installer(UpdateChecker.Release release, Kind kind) {
        return release.assets().stream().filter(a -> a.name().endsWith(kind.suffix)).findFirst();
    }

    /** Whether {@code release} has an installer this copy can use. */
    public boolean canInstall(UpdateChecker.Release release) {
        return installer(release, installation.kind()).isPresent()
                && release.assets().stream().anyMatch(a -> a.name().equals(installation.kind().checksums));
    }

    /**
     * Downloads the installer for {@code release} (or reuses an earlier download), checks it against the release's
     * checksum file, and returns it ready to install.
     *
     * @param progress called with the fraction downloaded, from 0 to 1, on the calling thread
     * @throws IOException if the download fails, or the file does not match its checksum
     */
    public Prepared download(UpdateChecker.Release release, DoubleConsumer progress) throws IOException {
        UpdateChecker.Asset asset = installer(release, installation.kind()).orElseThrow(() ->
                new IOException("Release " + release.version() + " has no " + installation.kind().suffix + " file"));
        UpdateChecker.Asset sums = release.assets().stream()
                .filter(a -> a.name().equals(installation.kind().checksums)).findFirst()
                .orElseThrow(() -> new IOException("Release " + release.version() + " has no "
                        + installation.kind().checksums + ", so its download cannot be checked"));
        String expected = expectedSha256(new String(fetch(sums.url()), StandardCharsets.UTF_8), asset.name())
                .orElseThrow(() -> new IOException(sums.name() + " has no checksum for " + asset.name()));

        Path dir = updatesDir.resolve(safeName(release.version()));
        Files.createDirectories(dir);
        Path file = dir.resolve(safeName(asset.name()));
        Optional<Prepared> earlier = ready(dir, release.version());
        if (earlier.isPresent() && earlier.get().file().equals(file) && expected.equals(sha256(file))) {
            progress.accept(1);
            return earlier.get();
        }
        Files.deleteIfExists(dir.resolve(READY));
        Path part = dir.resolve(file.getFileName() + ".part");
        String actual = downloadTo(asset, part, progress);
        if (!actual.equals(expected)) {
            Files.deleteIfExists(part);
            throw new IOException("The download of " + asset.name() + " does not match its checksum in "
                    + sums.name() + ", so it was deleted and not installed");
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(dir.resolve(READY), expected + "  " + file.getFileName() + "\n");
        return new Prepared(release.version(), installation, file);
    }

    /**
     * An installer downloaded earlier for a version newer than {@code currentVersion}, and still intact; the newest
     * if there are several.
     */
    public Optional<Prepared> pending(String currentVersion) {
        if (!Files.isDirectory(updatesDir)) {
            return Optional.empty();
        }
        Optional<Prepared> best = Optional.empty();
        try (Stream<Path> dirs = Files.list(updatesDir)) {
            for (Path dir : dirs.toList()) {
                String version = fileName(dir);
                if (!isVersion(version) || !UpdateChecker.isNewer(version, currentVersion)) {
                    continue;
                }
                Optional<Prepared> p = ready(dir, version).filter(this::intact);
                if (p.isPresent() && (best.isEmpty()
                        || UpdateChecker.compare(version, best.get().version()) > 0)) {
                    best = p;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return best;
    }

    /** Deletes downloads for {@code currentVersion} and older: they are installed, or superseded. */
    public void deleteInstalled(String currentVersion) {
        if (!Files.isDirectory(updatesDir) || !isVersion(currentVersion)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(updatesDir)) {
            for (Path dir : dirs.toList()) {
                String version = fileName(dir);
                if (isVersion(version) && !UpdateChecker.isNewer(version, currentVersion)) {
                    deleteTree(dir);
                }
            }
        } catch (IOException ignored) {
            // Only disk space: they are retried next time.
        }
    }

    private Optional<Prepared> ready(Path dir, String version) {
        try {
            String line = Files.readString(dir.resolve(READY)).strip();
            int space = line.indexOf("  ");
            if (space < 0) {
                return Optional.empty();
            }
            Path file = dir.resolve(line.substring(space + 2));
            String name = fileName(file);
            if (!dir.equals(file.getParent()) || !name.endsWith(installation.kind().suffix)
                    || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(new Prepared(version, installation, file));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private boolean intact(Prepared p) {
        try {
            String line = Files.readString(p.file().getParent().resolve(READY)).strip();
            return line.startsWith(sha256(p.file()) + "  ");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The SHA-256 for {@code assetName} in a checksum file ({@code sha256sum} output, text or binary mode). The
     * files list the names the release workflow built, which may have spaces where GitHub's asset names have dots.
     */
    static Optional<String> expectedSha256(String sumsFile, String assetName) {
        String wanted = assetName.replace(' ', '.');
        for (String line : sumsFile.split("\\R")) {
            String l = line.strip();
            int sp = l.indexOf(' ');
            if (sp != 64) {
                continue;
            }
            String hash = l.substring(0, 64).toLowerCase(Locale.ROOT);
            String name = l.substring(sp).strip();
            if (name.startsWith("*")) {
                name = name.substring(1);
            }
            if (hash.matches("[0-9a-f]{64}") && name.replace(' ', '.').equals(wanted)) {
                return Optional.of(hash);
            }
        }
        return Optional.empty();
    }

    private static byte[] fetch(String url) throws IOException {
        HttpResponse<byte[]> r = send(url, HttpResponse.BodyHandlers.ofByteArray());
        return r.body();
    }

    private static String downloadTo(UpdateChecker.Asset asset, Path target, DoubleConsumer progress)
            throws IOException {
        HttpResponse<InputStream> r = send(asset.url(), HttpResponse.BodyHandlers.ofInputStream());
        long total = r.headers().firstValueAsLong("Content-Length").orElse(asset.size());
        MessageDigest sha = sha256Digest();
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                if (total > 0) {
                    progress.accept(Math.min(1.0, (double) done / total));
                }
            }
        }
        progress.accept(1);
        return HexFormat.of().formatHex(sha.digest());
    }

    private static <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri)) {
            throw new IOException("Not downloading over plain HTTP: " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                .header("User-Agent", "HL7-Sender/" + AppInfo.version()).GET().build();
        HttpResponse<T> r;
        try {
            r = HTTP.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (r.statusCode() / 100 != 2) {
            if (r.body() instanceof InputStream in) {
                in.close();
            }
            throw new IOException("Download of " + url + " failed: HTTP " + r.statusCode());
        }
        // A redirect must not downgrade to plain HTTP either.
        URI last = r.uri();
        if (!"https".equalsIgnoreCase(last.getScheme()) && !isLoopback(last)) {
            throw new IOException("Not downloading over plain HTTP: " + last);
        }
        return r;
    }

    private static boolean isLoopback(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        try {
            return InetAddress.getByName(uri.getHost()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest sha = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isVersion(String v) {
        try {
            UpdateChecker.compare(v, v);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String safeName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            throw new IllegalArgumentException("Bad file name: " + name);
        }
        return s;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // --- Installing ------------------------------------------------------------------------------------

    /**
     * Starts installing {@code update} in a separate process that first waits for process {@code waitForPid} (the
     * app or {@code hl7send}) to exit, so files in use are not replaced. Its output goes to {@code install.log} next
     * to the installer.
     *
     * @param relaunch open the desktop app again when the update is installed
     * @return the installer process
     */
    public static Process launch(Prepared update, long waitForPid, boolean relaunch) throws IOException {
        boolean windows = update.installation().kind() == Kind.WINDOWS_MSI;
        Path dir = folderOf(update.file());
        Path script = dir.resolve(windows ? "install.ps1" : "install.sh");
        Path log = dir.resolve("install.log");
        Files.writeString(script, script(update, waitForPid, relaunch, log), StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        if (windows) {
            command.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", script.toString()));
        } else {
            command.addAll(List.of("nohup", "/bin/sh", script.toString()));
        }
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }

    /**
     * Installs {@code update} now, in this process's terminal, and returns the installer's exit code. For
     * {@code hl7send} on macOS and Linux, where files in use can be replaced; on Windows use {@link #launch}.
     */
    public static int installNow(Prepared update) throws IOException {
        if (update.installation().kind() == Kind.WINDOWS_MSI) {
            throw new IllegalStateException("On Windows the installer runs after the program exits");
        }
        Path dir = folderOf(update.file());
        Path script = dir.resolve("install.sh");
        Files.writeString(script, script(update, 0, false, dir.resolve("install.log")), StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("/bin/sh", script.toString()).inheritIO().start();
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** The install script: PowerShell for Windows, POSIX shell otherwise. */
    static String script(Prepared update, long waitForPid, boolean relaunch, Path log) {
        Installation i = update.installation();
        String file = update.file().toString();
        String location = i.location().toString();
        if (i.kind() == Kind.WINDOWS_MSI) {
            StringBuilder s = new StringBuilder();
            s.append("# Installs HL7 Sender ").append(update.version()).append(", written by HL7 Sender.\r\n");
            s.append("$ErrorActionPreference = 'Stop'\r\n");
            if (waitForPid > 0) {
                s.append("Wait-Process -Id ").append(waitForPid).append(" -ErrorAction SilentlyContinue\r\n");
            }
            s.append("$msiLog = ").append(ps(log.resolveSibling("msiexec.log").toString())).append("\r\n");
            // Progress bar only; tests run it with no user interface at all.
            String ui = "/qn".equals(System.getProperty("hl7sender.updateMsiUi")) ? "/qn" : "/passive";
            s.append("$p = Start-Process -FilePath 'msiexec.exe' -Wait -PassThru -ArgumentList @('/i', ")
                    .append(ps("\"" + file + "\"")).append(", '").append(ui).append("', '/norestart', ")
                    .append(ps("INSTALLDIR=\"" + location + "\"")).append(", '/l*v', ('\"' + $msiLog + '\"'))\r\n");
            s.append("Write-Output \"msiexec exit code: $($p.ExitCode)\"\r\n");
            s.append("if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { exit $p.ExitCode }\r\n");
            if (relaunch) {
                s.append("Start-Process -FilePath ").append(ps(i.launcher().toString())).append("\r\n");
            }
            return s.toString();
        }
        StringBuilder s = new StringBuilder("#!/bin/sh\n");
        s.append("# Installs HL7 Sender ").append(update.version()).append(", written by HL7 Sender.\n");
        if (waitForPid > 0) {
            s.append("while kill -0 ").append(waitForPid).append(" 2>/dev/null; do sleep 1; done\n");
        }
        s.append("set -eu\n");
        s.append("echo \"$(date): installing ").append(update.version()).append("\"\n");
        s.append("new=").append(sh(file)).append('\n');
        s.append("target=").append(sh(location)).append('\n');
        String elevate = "root".equals(System.getProperty("user.name")) ? "" : "pkexec ";
        switch (i.kind()) {
            case MAC_APP -> s.append("""
                    mnt=$(mktemp -d)
                    hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$mnt" "$new"
                    app=$(find "$mnt" -maxdepth 1 -name '*.app' | head -n 1)
                    rm -rf "$target.update"
                    ditto "$app" "$target.update"
                    hdiutil detach "$mnt" -quiet || true
                    xattr -dr com.apple.quarantine "$target.update" 2>/dev/null || true
                    rm -rf "$target.old"
                    mv "$target" "$target.old"
                    mv "$target.update" "$target"
                    rm -rf "$target.old"
                    launchctl kickstart -k "gui/$(id -u)/io.hl7sender.hl7send" 2>/dev/null || true
                    """);
            case MAC_PKG -> s.append("open -W \"$new\"\n");
            case LINUX_DEB -> s.append("if command -v apt-get >/dev/null; then ").append(elevate)
                    .append("apt-get install -y \"$new\"; else ").append(elevate).append("dpkg -i \"$new\"; fi\n");
            case LINUX_RPM -> s.append("if command -v dnf >/dev/null; then ").append(elevate)
                    .append("dnf install -y \"$new\"; elif command -v zypper >/dev/null; then ").append(elevate)
                    .append("zypper --non-interactive install --allow-unsigned-rpm \"$new\"; else ").append(elevate)
                    .append("rpm -U \"$new\"; fi\n");
            case APPIMAGE -> s.append("""
                    cp "$new" "$target.update"
                    chmod +x "$target.update"
                    mv -f "$target.update" "$target"
                    """);
            case PORTABLE -> s.append("""
                    tmp=$(mktemp -d "$(dirname "$target")/.hl7-sender-update.XXXXXX")
                    tar -xzf "$new" -C "$tmp"
                    src=$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)
                    rm -rf "$target.old"
                    mv "$target" "$target.old"
                    mv "$src" "$target"
                    rm -rf "$target.old" "$tmp"
                    """);
            default -> throw new IllegalStateException(i.kind().name());
        }
        if (i.kind() != Kind.MAC_APP && i.kind() != Kind.MAC_PKG) {
            s.append("systemctl --user try-restart hl7send.service >/dev/null 2>&1 || true\n");
        }
        s.append("echo \"$(date): installed ").append(update.version()).append("\"\n");
        if (relaunch && i.kind() != Kind.MAC_PKG) {
            if (i.kind() == Kind.MAC_APP) {
                s.append("open \"$target\"\n");
            } else {
                s.append("nohup ").append(sh(i.launcher().toString())).append(" >/dev/null 2>&1 &\n");
            }
        }
        return s.toString();
    }

    private static String fileName(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }

    private static Path folderOf(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("No folder for " + file);
        }
        return dir;
    }

    /** A POSIX shell single-quoted string. */
    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** A PowerShell single-quoted string. */
    private static String ps(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
