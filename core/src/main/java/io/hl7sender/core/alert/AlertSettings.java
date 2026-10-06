package io.hl7sender.core.alert;

/**
 * What to alert on and where to send alerts.
 *
 * @param desktop             show desktop notifications
 * @param deadLetter          alert when messages are dead-lettered
 * @param deadLetterThreshold alert once this many new messages have been dead-lettered for a destination
 * @param circuitOpen         alert when a destination's circuit breaker opens
 * @param certificateExpiry   alert when a TLS certificate is expired or expires within 30 days
 * @param webhookUrl          HTTP(S) URL to POST alerts to as JSON (Slack, Teams, ...); empty = off
 * @param email               SMTP settings; the password is kept in the secret store
 */
public record AlertSettings(boolean desktop, boolean deadLetter, int deadLetterThreshold, boolean circuitOpen,
                            boolean certificateExpiry, String webhookUrl, EmailSettings email) {

    /** Secret-store key of the SMTP password. */
    public static final String SMTP_PASSWORD_KEY = "alerts/smtp-password";

    public AlertSettings {
        deadLetterThreshold = Math.max(1, deadLetterThreshold);
        webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        email = email == null ? EmailSettings.DISABLED : email;
    }

    public static AlertSettings defaults() {
        return new AlertSettings(true, true, 1, true, true, "", EmailSettings.DISABLED);
    }

    public boolean webhookEnabled() {
        return !webhookUrl.isEmpty();
    }

    /**
     * SMTP settings for e-mail alerts.
     *
     * @param enabled  send e-mail alerts
     * @param host     SMTP server
     * @param port     SMTP port (587 for STARTTLS, 465 for SMTPS, 25 for plain)
     * @param security how the connection is protected
     * @param username login name, or empty for no authentication
     * @param from     sender address
     * @param to       recipients, comma-separated
     */
    public record EmailSettings(boolean enabled, String host, int port, Security security, String username,
                                String from, String to) {

        public static final EmailSettings DISABLED =
                new EmailSettings(false, "", 587, Security.STARTTLS, "", "", "");

        public enum Security { NONE, STARTTLS, SSL }

        public EmailSettings {
            host = host == null ? "" : host.trim();
            port = port < 1 || port > 65_535 ? 587 : port;
            security = security == null ? Security.STARTTLS : security;
            username = username == null ? "" : username.trim();
            from = from == null ? "" : from.trim();
            to = to == null ? "" : to.trim();
        }
    }
}
