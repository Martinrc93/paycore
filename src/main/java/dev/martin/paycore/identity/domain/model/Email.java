package dev.martin.paycore.identity.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record Email(String value) {

    private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final Pattern LOCAL_PART = Pattern.compile(ATOM + "(?:\\." + ATOM + ")*");
    private static final Pattern DOMAIN = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

    public Email {
        Objects.requireNonNull(value, "value");
        if (!isValid(value)) {
            throw new IllegalArgumentException("Unsupported email address");
        }
    }

    public static Email of(String rawValue) {
        Objects.requireNonNull(rawValue, "rawValue");
        return new Email(rawValue.strip().toLowerCase(Locale.ROOT));
    }

    private static boolean isValid(String candidate) {
        if (candidate.isEmpty() || candidate.length() > 254 || !candidate.chars().allMatch(c -> c < 128)) {
            return false;
        }
        int separator = candidate.indexOf('@');
        if (separator < 1 || separator != candidate.lastIndexOf('@')) {
            return false;
        }
        String local = candidate.substring(0, separator);
        String domain = candidate.substring(separator + 1);
        return local.length() <= 64
                && LOCAL_PART.matcher(local).matches()
                && DOMAIN.matcher(domain).matches();
    }
}
