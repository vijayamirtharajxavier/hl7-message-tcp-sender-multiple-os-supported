package io.hl7sender.core.alert;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;

/** Sends alerts by SMTP. */
public final class EmailSink implements AlertSink {

    private static final String TIMEOUT_MS = "15000";

    private final AlertSettings.EmailSettings settings;
    private final String password;

    /** @param password SMTP password, or null when the server needs no login */
    public EmailSink(AlertSettings.EmailSettings settings, String password) {
        if (settings.host().isEmpty()) {
            throw new IllegalArgumentException("SMTP server is required");
        }
        if (settings.from().isEmpty() || settings.to().isEmpty()) {
            throw new IllegalArgumentException("E-mail sender and recipient are required");
        }
        this.settings = settings;
        this.password = password;
    }

    @Override
    public String name() {
        return "E-mail";
    }

    @Override
    public void send(Alert alert) throws IOException {
        boolean ssl = settings.security() == AlertSettings.EmailSettings.Security.SSL;
        String protocol = ssl ? "smtps" : "smtp";
        Properties p = new Properties();
        p.put("mail.transport.protocol", protocol);
        p.put("mail." + protocol + ".host", settings.host());
        p.put("mail." + protocol + ".port", String.valueOf(settings.port()));
        p.put("mail." + protocol + ".connectiontimeout", TIMEOUT_MS);
        p.put("mail." + protocol + ".timeout", TIMEOUT_MS);
        p.put("mail." + protocol + ".writetimeout", TIMEOUT_MS);
        if (settings.security() == AlertSettings.EmailSettings.Security.STARTTLS) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        if (settings.security() != AlertSettings.EmailSettings.Security.NONE) {
            p.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        }
        boolean auth = !settings.username().isEmpty();
        p.put("mail." + protocol + ".auth", String.valueOf(auth));
        Session session = Session.getInstance(p, auth ? new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(settings.username(), password == null ? "" : password);
            }
        } : null);
        try {
            MimeMessage m = new MimeMessage(session);
            m.setFrom(new InternetAddress(settings.from(), true));
            m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(settings.to(), true));
            m.setSubject("HL7 Sender " + alert.summary(), "UTF-8");
            m.setSentDate(Date.from(alert.at()));
            m.setText(alert.message() + "\n\nDestination: "
                    + (alert.destinationName().isEmpty() ? "-" : alert.destinationName())
                    + "\nTime: " + alert.at() + "\n\nThis alert contains no message content.", "UTF-8");
            Transport.send(m);
        } catch (MessagingException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            throw new IOException("E-mail failed: " + (root.getMessage() == null ? e.getMessage()
                    : root.getMessage()), e);
        }
    }
}
