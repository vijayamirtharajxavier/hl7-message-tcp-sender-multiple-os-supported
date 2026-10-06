package io.hl7sender.core.config;

import io.hl7sender.core.alert.AlertSettings;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.ResponseRule;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.AckMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * User preferences saved between sessions. Missing or invalid values fall back to defaults, so a
 * settings file from an older version always loads.
 */
public record AppSettings(Destination destination, Sender sender, Listener listener, Security security,
                          AlertSettings alerts, Ui ui, Api api, Updates updates, Database database) {

    public AppSettings(Destination destination, Sender sender, Listener listener) {
        this(destination, sender, listener, Security.defaults(), AlertSettings.defaults(), Ui.defaults(),
                Api.defaults(), Updates.defaults(), Database.defaults());
    }

    /** Settings from before the database choice existed: a local SQLite queue. */
    public AppSettings(Destination destination, Sender sender, Listener listener, Security security,
                       AlertSettings alerts, Ui ui, Api api, Updates updates) {
        this(destination, sender, listener, security, alerts, ui, api, updates, Database.defaults());
    }

    public AppSettings withDatabase(Database db) {
        return new AppSettings(destination, sender, listener, security, alerts, ui, api, updates, db);
    }

    /**
     * Where the queue database is: a SQLite file on this computer (the default), or a PostgreSQL server shared by
     * several computers. The PostgreSQL password is kept in the OS keychain, not here.
     *
     * @param type     {@code SQLITE} or {@code POSTGRES}
     * @param url      for PostgreSQL, e.g. {@code jdbc:postgresql://db.example.org:5432/hl7sender}
     * @param username for PostgreSQL
     */
    public record Database(Type type, String url, String username) {

        /** Kinds of queue database. */
        public enum Type { SQLITE, POSTGRES }

        public static Database defaults() {
            return new Database(Type.SQLITE, "", "");
        }

        public static Database postgres(String url, String username) {
            return new Database(Type.POSTGRES, url, username);
        }

        public boolean postgres() {
            return type == Type.POSTGRES;
        }

        Database normalized() {
            return new Database(type == null ? Type.SQLITE : type, url == null ? "" : url.trim(),
                    username == null ? "" : username.trim());
        }
    }

    public AppSettings withApi(Api a) {
        return new AppSettings(destination, sender, listener, security, alerts, ui, a, updates, database);
    }

    public AppSettings withUpdates(Updates u) {
        return new AppSettings(destination, sender, listener, security, alerts, ui, api, u, database);
    }

    /**
     * Update checks (opt-in).
     *
     * @param checkAutomatically check GitHub Releases for a newer version at most once a day
     * @param lastCheckedMillis  when the last automatic check ran (epoch milliseconds), or 0
     * @param skippedVersion     a version the user chose not to be reminded about, or empty
     */
    public record Updates(boolean checkAutomatically, long lastCheckedMillis, String skippedVersion) {

        public static Updates defaults() {
            return new Updates(false, 0, "");
        }

        Updates normalized() {
            return new Updates(checkAutomatically, Math.max(0, lastCheckedMillis),
                    skippedVersion == null ? "" : skippedVersion.trim());
        }
    }

    /**
     * The local REST API, served by whichever process delivers from the queue.
     *
     * @param enabled serve the API
     * @param port    TCP port on 127.0.0.1
     */
    public record Api(boolean enabled, int port) {

        public static final int DEFAULT_PORT = 8742;

        public static Api defaults() {
            return new Api(false, DEFAULT_PORT);
        }

        Api normalized() {
            return new Api(enabled, port < 1 || port > 65_535 ? DEFAULT_PORT : port);
        }
    }

    public static AppSettings defaults() {
        return new AppSettings(Destination.defaults(), Sender.defaults(), Listener.defaults()).normalized();
    }

    /** Replaces nulls and out-of-range values with defaults. */
    public AppSettings normalized() {
        return new AppSettings(
                destination == null ? Destination.defaults() : destination.normalized(),
                sender == null ? Sender.defaults() : sender,
                listener == null ? Listener.defaults() : listener.normalized(),
                security == null ? Security.defaults() : security,
                alerts == null ? AlertSettings.defaults() : alerts,
                ui == null ? Ui.defaults() : ui.normalized(),
                api == null ? Api.defaults() : api.normalized(),
                updates == null ? Updates.defaults() : updates.normalized(),
                database == null ? Database.defaults() : database.normalized());
    }

    public AppSettings withDestination(Destination d) {
        return new AppSettings(d, sender, listener, security, alerts, ui, api, updates, database);
    }

    public AppSettings withSender(Sender s) {
        return new AppSettings(destination, s, listener, security, alerts, ui, api, updates, database);
    }

    public AppSettings withListener(Listener l) {
        return new AppSettings(destination, sender, l, security, alerts, ui, api, updates, database);
    }

    public AppSettings withSecurity(Security s) {
        return new AppSettings(destination, sender, listener, s, alerts, ui, api, updates, database);
    }

    public AppSettings withAlerts(AlertSettings a) {
        return new AppSettings(destination, sender, listener, security, a, ui, api, updates, database);
    }

    public AppSettings withUi(Ui u) {
        return new AppSettings(destination, sender, listener, security, alerts, u, api, updates, database);
    }

    /**
     * Look and feel.
     *
     * @param theme         colour theme
     * @param language      IETF language tag for the user interface, or empty for the system language
     * @param firstRunDone  the welcome wizard has been completed or dismissed
     */
    public record Ui(Theme theme, String language, boolean firstRunDone) {

        public enum Theme { LIGHT, DARK }

        public static Ui defaults() {
            return new Ui(Theme.LIGHT, "", false);
        }

        Ui normalized() {
            return new Ui(theme == null ? Theme.LIGHT : theme, language == null ? "" : language.trim(),
                    firstRunDone);
        }

        public Ui withTheme(Theme t) {
            return new Ui(t, language, firstRunDone);
        }

        public Ui withFirstRunDone(boolean done) {
            return new Ui(theme, language, done);
        }
    }

    /**
     * Security preferences.
     *
     * @param encryptQueue keep the queue database encrypted at rest; changes are applied at the next start
     */
    public record Security(boolean encryptQueue) {

        public static Security defaults() {
            return new Security(false);
        }
    }

    /** Where and how to send. */
    public record Destination(String host, int port, int connectTimeoutMs, int ackTimeoutMs, String charset,
                              AckMode ackMode) {

        public static Destination defaults() {
            return new Destination("localhost", 2575, MllpClientConfig.DEFAULT_CONNECT_TIMEOUT_MS,
                    MllpClientConfig.DEFAULT_RESPONSE_TIMEOUT_MS, StandardCharsets.UTF_8.name(), AckMode.EXPECT_ACK);
        }

        Destination normalized() {
            Destination d = defaults();
            return new Destination(
                    host == null || host.isBlank() ? d.host : host,
                    port < 1 || port > 65_535 ? d.port : port,
                    connectTimeoutMs <= 0 ? d.connectTimeoutMs : connectTimeoutMs,
                    ackTimeoutMs <= 0 ? d.ackTimeoutMs : ackTimeoutMs,
                    charset == null || !Charset.isSupported(charset) ? d.charset : charset,
                    ackMode == null ? d.ackMode : ackMode);
        }

        public MllpClientConfig toClientConfig() {
            return MllpClientConfig.of(host, port)
                    .withTimeouts(connectTimeoutMs, ackTimeoutMs)
                    .withCharset(Charset.forName(charset));
        }
    }

    /** Message preparation preferences. */
    public record Sender(boolean generateControlId, boolean generateTimestamp) {

        public static Sender defaults() {
            return new Sender(true, true);
        }
    }

    /** Test listener preferences. */
    /**
     * The Test Listener tab.
     *
     * @param bindAddress interface to listen on
     * @param port        TCP port
     * @param mode        default response
     * @param delayMs     default delay before responding
     * @param commitCodes answer with CA/CE/CR
     * @param rules       responder rules, checked before the default response
     * @param saveFolder  folder to save received messages to, or empty
     */
    public record Listener(String bindAddress, int port, ResponseMode mode, int delayMs, boolean commitCodes,
                           List<ResponseRule> rules, String saveFolder) {

        public Listener(String bindAddress, int port, ResponseMode mode, int delayMs, boolean commitCodes) {
            this(bindAddress, port, mode, delayMs, commitCodes, List.of(), "");
        }

        public static Listener defaults() {
            return new Listener("0.0.0.0", 2575, ResponseMode.ACCEPT, 0, false);
        }

        public Listener withRules(List<ResponseRule> newRules, String newSaveFolder) {
            return new Listener(bindAddress, port, mode, delayMs, commitCodes, newRules, newSaveFolder);
        }

        Listener normalized() {
            Listener d = defaults();
            return new Listener(
                    bindAddress == null || bindAddress.isBlank() ? d.bindAddress : bindAddress,
                    port < 1 || port > 65_535 ? d.port : port,
                    mode == null || mode == ResponseMode.CUSTOM ? d.mode : mode,
                    Math.max(0, delayMs),
                    commitCodes,
                    rules == null ? List.of() : rules.stream().filter(java.util.Objects::nonNull).toList(),
                    saveFolder == null ? "" : saveFolder.trim());
        }
    }
}
