package io.hl7sender.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Application name and build version. */
public final class AppInfo {

    public static final String NAME = "HL7 Sender";
    /** Source code, releases and documentation. */
    public static final String HOMEPAGE =
            "https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported";

    private static final String VERSION = loadVersion();

    private AppInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }
}
