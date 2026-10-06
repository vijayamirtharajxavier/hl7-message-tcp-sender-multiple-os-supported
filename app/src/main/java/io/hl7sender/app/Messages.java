package io.hl7sender.app;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * User-interface text from the {@code messages} resource bundles. Patterns use {@link MessageFormat}, so
 * {@code {0}} is the first argument and a literal apostrophe is written {@code ''}.
 */
final class Messages {

    private static final String BUNDLE = "io.hl7sender.app.messages";
    /** Missing translations fall back to the base (English) bundle, not to the system language. */
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static volatile Locale locale = Locale.getDefault();
    private static volatile ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale, NO_FALLBACK);

    private Messages() {
    }

    /** Switches language; takes effect for text created afterwards. Empty = the system language. */
    static void setLanguage(String languageTag) {
        Locale l = languageTag == null || languageTag.isBlank() ? Locale.getDefault()
                : Locale.forLanguageTag(languageTag);
        locale = l;
        bundle = ResourceBundle.getBundle(BUNDLE, l, NO_FALLBACK);
    }

    static Locale locale() {
        return locale;
    }

    static String get(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
        return new MessageFormat(pattern, locale).format(args);
    }
}
