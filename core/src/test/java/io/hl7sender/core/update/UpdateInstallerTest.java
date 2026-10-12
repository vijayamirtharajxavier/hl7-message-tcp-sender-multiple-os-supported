package io.hl7sender.core.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.update.UpdateInstaller.Detection;
import io.hl7sender.core.update.UpdateInstaller.Installation;
import io.hl7sender.core.update.UpdateInstaller.Kind;
import io.hl7sender.core.update.UpdateInstaller.Prepared;
import io.hl7sender.core.update.UpdateInstaller.Unsupported;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateInstallerTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final Map<String, byte[]> files = new HashMap<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = files.get(ex.getRequestURI().getPath().substring(1));
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static Detection linux(String appPath, Map<String, String> env, boolean root, Set<Path> writable,
                                   Set<String> programs, Set<String> packages) {
        return UpdateInstaller.detect("Linux", "amd64", appPath, env, root, writable::contains, programs::contains,
                cmd -> packages.contains(cmd.get(0)));
    }

    @Test
    void detectsHowThisCopyWasInstalled() {
        Map<String, String> none = Map.of();
        assertThat(UpdateInstaller.detect("Linux", "amd64", "", none, false, p -> true, p -> true, c -> true)
                .unsupported()).contains(Unsupported.DEVELOPMENT_BUILD);

        Installation win = UpdateInstaller.detect("Windows 11", "amd64",
                "C:\\Users\\jane\\AppData\\Local\\HL7 Sender\\hl7send.exe", none, false, p -> false, p -> false,
                c -> false).installation().orElseThrow();
        assertThat(win.kind()).isEqualTo(Kind.WINDOWS_MSI);
        assertThat(win.location().toString()).endsWith("HL7 Sender");
        assertThat(win.launcher().toString()).isEqualTo("C:\\Users\\jane\\AppData\\Local\\HL7 Sender\\HL7 Sender.exe");

        String mac = "/Applications/HL7 Sender.app/Contents/MacOS/HL7 Sender";
        Installation app = UpdateInstaller.detect("Mac OS X", "aarch64", mac, none, false, p -> true, p -> false,
                c -> false).installation().orElseThrow();
        assertThat(app.kind()).isEqualTo(Kind.MAC_APP);
        assertThat(app.location()).isEqualTo(Path.of("/Applications/HL7 Sender.app"));
        assertThat(UpdateInstaller.detect("Mac OS X", "aarch64", mac, none, false, p -> false, p -> false,
                c -> false).installation().orElseThrow().kind()).isEqualTo(Kind.MAC_PKG);
        assertThat(UpdateInstaller.detect("Mac OS X", "x86_64", mac, none, false, p -> true, p -> false,
                c -> false).unsupported()).contains(Unsupported.PLATFORM);

        String opt = "/opt/hl7-sender/bin/hl7-sender";
        assertThat(linux(opt, none, false, Set.of(), Set.of("pkexec"), Set.of("dpkg-query")).installation()
                .orElseThrow().kind()).isEqualTo(Kind.LINUX_DEB);
        assertThat(linux(opt, none, false, Set.of(), Set.of("pkexec"), Set.of("rpm")).installation()
                .orElseThrow().kind()).isEqualTo(Kind.LINUX_RPM);
        assertThat(linux(opt, none, true, Set.of(), Set.of(), Set.of("dpkg-query")).installation()
                .orElseThrow().kind()).isEqualTo(Kind.LINUX_DEB);
        // A system package needs pkexec (or root) to install over it.
        assertThat(linux(opt, none, false, Set.of(), Set.of(), Set.of("dpkg-query")).unsupported())
                .contains(Unsupported.INSTALLATION);

        Path image = Path.of("/home/jane/Apps/HL7_Sender-1.2.2-x86_64.AppImage");
        Installation appImage = linux("/tmp/.mount_x/usr/hl7-sender/bin/hl7-sender",
                Map.of("APPIMAGE", image.toString()), false, Set.of(image, image.getParent()), Set.of(),
                Set.of()).installation().orElseThrow();
        assertThat(appImage.kind()).isEqualTo(Kind.APPIMAGE);
        assertThat(appImage.location()).isEqualTo(image);

        Path portable = Path.of("/home/jane/tools/hl7-sender");
        Installation folder = linux(portable + "/bin/hl7send", none, false, Set.of(portable, portable.getParent()),
                Set.of(), Set.of()).installation().orElseThrow();
        assertThat(folder.kind()).isEqualTo(Kind.PORTABLE);
        assertThat(folder.location()).isEqualTo(portable);
        assertThat(folder.launcher()).isEqualTo(portable.resolve("bin").resolve("hl7-sender"));
        assertThat(linux(portable + "/bin/hl7send", none, false, Set.of(), Set.of(), Set.of()).unsupported())
                .contains(Unsupported.INSTALLATION);
        assertThat(UpdateInstaller.detect("Linux", "aarch64", opt, none, true, p -> true, p -> true, c -> true)
                .unsupported()).contains(Unsupported.PLATFORM);
    }

    @Test
    void picksTheInstallerAndItsChecksum() {
        List<UpdateChecker.Asset> assets = new ArrayList<>();
        for (String name : List.of("hl7-sender-1.2.2-1.x86_64.rpm", "hl7-sender-1.2.2-linux-x64.tar.gz",
                "hl7-sender_1.2.2_amd64.deb", "HL7.Sender-1.2.2.dmg", "HL7.Sender-1.2.2.msi", "HL7.Sender-1.2.2.pkg",
                "HL7_Sender-1.2.2-x86_64.AppImage", "SHA256SUMS-Linux.txt", "SHA256SUMS-macOS.txt",
                "SHA256SUMS-Windows.txt")) {
            assets.add(new UpdateChecker.Asset(name, "https://example.org/" + name, 1));
        }
        UpdateChecker.Release r = new UpdateChecker.Release("1.2.2", "", "", Optional.empty(), assets);
        Map<Kind, String> expected = Map.of(Kind.WINDOWS_MSI, "HL7.Sender-1.2.2.msi", Kind.MAC_APP,
                "HL7.Sender-1.2.2.dmg", Kind.MAC_PKG, "HL7.Sender-1.2.2.pkg", Kind.LINUX_DEB,
                "hl7-sender_1.2.2_amd64.deb", Kind.LINUX_RPM, "hl7-sender-1.2.2-1.x86_64.rpm", Kind.APPIMAGE,
                "HL7_Sender-1.2.2-x86_64.AppImage", Kind.PORTABLE, "hl7-sender-1.2.2-linux-x64.tar.gz");
        expected.forEach((kind, name) ->
                assertThat(UpdateInstaller.installer(r, kind).orElseThrow().name()).isEqualTo(name));

        // The checksum files list the names as built, with spaces; text and binary mode lines.
        String windows = "049666db784c794b345f979e3783cf34fd01984f505115a267075a9eeb4e6118 *HL7 Sender-1.2.2.msi\n"
                + "6bc00a0c662a940d50a5328492075179a7346b7007c3fcb67882fe75a5365727 *SHA256SUMS-Windows.txt\n";
        assertThat(UpdateInstaller.expectedSha256(windows, "HL7.Sender-1.2.2.msi"))
                .contains("049666db784c794b345f979e3783cf34fd01984f505115a267075a9eeb4e6118");
        String linux = "87ad8a260a4aca0d94b37773f5262b5f5c19568d416e0254f9f55bda63eeafff"
                + "  hl7-sender_1.2.2_amd64.deb\r\n";
        assertThat(UpdateInstaller.expectedSha256(linux, "hl7-sender_1.2.2_amd64.deb"))
                .contains("87ad8a260a4aca0d94b37773f5262b5f5c19568d416e0254f9f55bda63eeafff");
        assertThat(UpdateInstaller.expectedSha256(linux, "hl7-sender_1.2.3_amd64.deb")).isEmpty();
        assertThat(UpdateInstaller.expectedSha256("not a checksum file", "x")).isEmpty();
    }

    private UpdateChecker.Release release(String base, String version, String installer, String sums) {
        return new UpdateChecker.Release(version, "", "", Optional.empty(), List.of(
                new UpdateChecker.Asset(installer, base + installer, files.get(installer).length),
                new UpdateChecker.Asset(sums, base + sums, 0)));
    }

    @Test
    void downloadsAndChecksTheInstaller() throws Exception {
        String base = serve();
        byte[] tar = "pretend tar.gz".getBytes(StandardCharsets.UTF_8);
        String name = "hl7-sender-1.3.0-linux-x64.tar.gz";
        files.put(name, tar);
        Path probe = dir.resolve("probe");
        Files.write(probe, tar);
        files.put("SHA256SUMS-Linux.txt", (UpdateInstaller.sha256(probe) + "  " + name + "\n")
                .getBytes(StandardCharsets.UTF_8));
        Installation inst = new Installation(Kind.PORTABLE, dir.resolve("hl7-sender"),
                dir.resolve("hl7-sender/bin/hl7-sender"));
        UpdateInstaller installer = new UpdateInstaller(inst, dir.resolve("updates"));
        UpdateChecker.Release r = release(base, "1.3.0", name, "SHA256SUMS-Linux.txt");
        assertThat(installer.canInstall(r)).isTrue();

        List<Double> progress = new ArrayList<>();
        Prepared p = installer.download(r, progress::add);
        assertThat(Files.readAllBytes(p.file())).isEqualTo(tar);
        assertThat(p.version()).isEqualTo("1.3.0");
        assertThat(progress).last().isEqualTo(1.0);
        assertThat(installer.pending("1.2.2")).contains(p);
        assertThat(installer.pending("1.3.0")).isEmpty();
        // A second download reuses the verified file.
        files.remove(name);
        assertThat(installer.download(r, x -> { }).file()).isEqualTo(p.file());

        // A changed file is not offered for installing.
        Files.writeString(p.file(), "tampered");
        assertThat(installer.pending("1.2.2")).isEmpty();

        // Once 1.3.0 runs, its download is deleted.
        installer.deleteInstalled("1.3.0");
        assertThat(dir.resolve("updates").resolve("1.3.0")).doesNotExist();
    }

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsDeleted() throws Exception {
        String base = serve();
        String name = "hl7-sender-1.3.0-linux-x64.tar.gz";
        files.put(name, "the real file".getBytes(StandardCharsets.UTF_8));
        files.put("SHA256SUMS-Linux.txt", ("0".repeat(64) + "  " + name + "\n").getBytes(StandardCharsets.UTF_8));
        UpdateInstaller installer = new UpdateInstaller(new Installation(Kind.PORTABLE, dir.resolve("a"),
                dir.resolve("a/bin/hl7-sender")), dir.resolve("updates"));
        assertThatThrownBy(() -> installer.download(release(base, "1.3.0", name, "SHA256SUMS-Linux.txt"), x -> { }))
                .hasMessageContaining("does not match its checksum");
        try (var left = Files.list(dir.resolve("updates").resolve("1.3.0"))) {
            assertThat(left).isEmpty();
        }
        assertThat(installer.pending("1.2.2")).isEmpty();

        // No checksum file: nothing is downloaded.
        UpdateChecker.Release noSums = new UpdateChecker.Release("1.3.0", "", "", Optional.empty(),
                List.of(new UpdateChecker.Asset(name, base + name, 1)));
        assertThat(installer.canInstall(noSums)).isFalse();
        assertThatThrownBy(() -> installer.download(noSums, x -> { })).hasMessageContaining("cannot be checked");

        // Plain HTTP is refused except from this computer.
        UpdateChecker.Release remote = new UpdateChecker.Release("1.3.0", "", "", Optional.empty(), List.of(
                new UpdateChecker.Asset(name, "http://example.org/" + name, 1),
                new UpdateChecker.Asset("SHA256SUMS-Linux.txt", "http://example.org/SHA256SUMS-Linux.txt", 1)));
        assertThatThrownBy(() -> installer.download(remote, x -> { })).hasMessageContaining("plain HTTP");
    }

    @Test
    void windowsScriptInstallsIntoTheSameFolderAndReopensTheApp() {
        Installation win = new Installation(Kind.WINDOWS_MSI, Path.of("C:\\Users\\o'neil\\HL7 Sender"),
                Path.of("C:\\Users\\o'neil\\HL7 Sender\\HL7 Sender.exe"));
        Prepared p = new Prepared("1.3.0", win, Path.of("C:\\Users\\o'neil\\updates\\1.3.0\\HL7.Sender-1.3.0.msi"));
        String s = UpdateInstaller.script(p, 4242, true, Path.of("C:\\Users\\o'neil\\updates\\1.3.0\\install.log"));
        assertThat(s).contains("Wait-Process -Id 4242")
                .contains("'/passive', '/norestart'")
                .contains("INSTALLDIR=\"C:\\Users\\o''neil\\HL7 Sender\"")
                .contains("Start-Process -FilePath 'C:\\Users\\o''neil\\HL7 Sender\\HL7 Sender.exe'");
        assertThat(UpdateInstaller.script(p, 4242, false, Path.of("install.log"))).doesNotContain("HL7 Sender.exe'");
    }

    @Test
    void portableAndAppImageScriptsReplaceTheInstallation() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "POSIX shell");
        // Portable folder: the old folder is replaced by the archive's top folder, whatever it is called.
        Path target = dir.resolve("my tools").resolve("hl7");
        Files.createDirectories(target.resolve("bin"));
        Files.writeString(target.resolve("bin").resolve("old-only"), "old");
        Path build = dir.resolve("build").resolve("hl7-sender");
        Files.createDirectories(build.resolve("bin"));
        Files.writeString(build.resolve("bin").resolve("hl7-sender"), "new");
        Path tgz = dir.resolve("update").resolve("hl7-sender-1.3.0-linux-x64.tar.gz");
        Files.createDirectories(tgz.getParent());
        run(List.of("tar", "-czf", tgz.toString(), "-C", build.getParent().toString(), "hl7-sender"));
        Prepared portable = new Prepared("1.3.0", new Installation(Kind.PORTABLE, target,
                target.resolve("bin").resolve("hl7-sender")), tgz);
        runScript(UpdateInstaller.script(portable, 0, false, dir.resolve("install.log")));
        assertThat(target.resolve("bin").resolve("hl7-sender")).hasContent("new");
        assertThat(target.resolve("bin").resolve("old-only")).doesNotExist();
        try (var siblings = Files.list(target.getParent())) {
            assertThat(siblings.map(p -> p.getFileName().toString())).containsExactly("hl7");
        }

        // AppImage: the file is replaced in place and stays executable.
        Path image = dir.resolve("HL7_Sender-1.2.2-x86_64.AppImage");
        Files.writeString(image, "old");
        Path newImage = dir.resolve("update").resolve("HL7_Sender-1.3.0-x86_64.AppImage");
        Files.writeString(newImage, "new");
        Prepared appImage = new Prepared("1.3.0", new Installation(Kind.APPIMAGE, image, image), newImage);
        runScript(UpdateInstaller.script(appImage, 0, false, dir.resolve("install.log")));
        assertThat(image).hasContent("new");
        assertThat(Files.isExecutable(image)).isTrue();
    }

    @Test
    void launchedInstallerWaitsForTheAppToExit() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "POSIX shell");
        Path image = dir.resolve("HL7_Sender.AppImage");
        Files.writeString(image, "old");
        Path newImage = dir.resolve("update").resolve("HL7_Sender-1.3.0-x86_64.AppImage");
        Files.createDirectories(newImage.getParent());
        Files.writeString(newImage, "new");
        // Stands in for the app: the installer must not touch anything until it has exited.
        Process app = new ProcessBuilder("sleep", "2").start();
        Process installer = UpdateInstaller.launch(new Prepared("1.3.0",
                new Installation(Kind.APPIMAGE, image, image), newImage), app.pid(), false);
        Thread.sleep(800);
        assertThat(image).as("still running").hasContent("old");
        assertThat(installer.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(app.isAlive()).isFalse();
        assertThat(image).hasContent("new");
        assertThat(Files.readString(newImage.resolveSibling("install.log"))).contains("installed 1.3.0");
    }

    @Test
    void macScriptReplacesTheAppBundleFromTheDiskImage() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("mac"), "macOS: hdiutil");
        Path bundle = dir.resolve("Applications").resolve("HL7 Sender.app");
        Files.createDirectories(bundle.resolve("Contents"));
        Files.writeString(bundle.resolve("Contents").resolve("version"), "old");
        Path content = dir.resolve("dmg-content").resolve("HL7 Sender.app").resolve("Contents");
        Files.createDirectories(content);
        Files.writeString(content.resolve("version"), "new");
        Path dmg = dir.resolve("update").resolve("HL7.Sender-1.3.0.dmg");
        Files.createDirectories(dmg.getParent());
        run(List.of("hdiutil", "create", "-quiet", "-volname", "HL7 Sender", "-srcfolder",
                dir.resolve("dmg-content").toString(), "-format", "UDZO", dmg.toString()));
        Prepared p = new Prepared("1.3.0", new Installation(Kind.MAC_APP, bundle, bundle), dmg);
        runScript(UpdateInstaller.script(p, 0, false, dir.resolve("install.log")));
        assertThat(bundle.resolve("Contents").resolve("version")).hasContent("new");
        try (var apps = Files.list(bundle.getParent())) {
            assertThat(apps.map(x -> x.getFileName().toString())).containsExactly("HL7 Sender.app");
        }
    }

    @Test
    void windowsScriptWaitsForTheAppAndRunsMsiexec() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "Windows: PowerShell, msiexec");
        Path folder = dir.resolve("HL7 Sender");
        Files.createDirectories(folder);
        // A package that does not exist: msiexec runs and reports 1619 (package could not be opened).
        Path msi = dir.resolve("update").resolve("HL7.Sender-1.3.0.msi");
        Files.createDirectories(msi.getParent());
        Process app = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 2").start();
        // No user interface, so an error cannot leave a dialog open on the test machine.
        System.setProperty("hl7sender.updateMsiUi", "/qn");
        Process installer;
        try {
            installer = UpdateInstaller.launch(new Prepared("1.3.0", new Installation(Kind.WINDOWS_MSI, folder,
                    folder.resolve("HL7 Sender.exe")), msi), app.pid(), false);
        } finally {
            System.clearProperty("hl7sender.updateMsiUi");
        }
        assertThat(installer.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(app.isAlive()).as("waited for the app").isFalse();
        String log = Files.readString(msi.resolveSibling("install.log"));
        assertThat(log).contains("msiexec exit code: 1619");
        assertThat(installer.exitValue()).isEqualTo(1619);
    }

    private void runScript(String script) throws Exception {
        Path file = dir.resolve("install-test.sh");
        Files.writeString(file, script);
        run(List.of("/bin/sh", file.toString()));
    }

    private static void run(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }
}
