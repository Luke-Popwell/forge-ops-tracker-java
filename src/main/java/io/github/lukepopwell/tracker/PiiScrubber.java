package io.github.lukepopwell.tracker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Redacts likely-sensitive content out of a payload before it ever leaves this process: the
 * same key list and regex patterns as the Ruby gem's {@code PiiScrubber} (and its Python/PHP/Node
 * ports), kept as a separate, dependency-free implementation here for the same reason: this
 * client has to work standalone in any host app, regardless of what's reporting into it.
 *
 * <p>ForgeOps itself scrubs again on arrival regardless: this is a second, earlier layer, not
 * the only one. Can be disabled via {@code Configuration#setScrubPii(false)}, but "on" is the only
 * sane default.
 */
public final class PiiScrubber {
  private static final String REDACTED = "[FILTERED]";

  private static final Set<String> SENSITIVE_KEYS = Set.of(
      "password", "passwd", "pwd",
      "secret", "apisecret", "clientsecret", "secretkey",
      "token", "accesstoken", "refreshtoken", "apikey", "apitoken", "authorization", "authtoken",
      "bearer", "sessiontoken", "csrftoken",
      "creditcard", "cardnumber", "cardnum", "cvv", "cvv2", "cvc",
      "ssn", "socialsecuritynumber", "socialsecurity",
      "privatekey");

  private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

  static {
    PATTERNS.put("EMAIL", Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"));
    PATTERNS.put("SSN", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"));
    PATTERNS.put("CREDIT CARD", Pattern.compile("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{1,4}\\b"));
    PATTERNS.put("BEARER TOKEN", Pattern.compile("\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*", Pattern.CASE_INSENSITIVE));
    PATTERNS.put("JWT", Pattern.compile("\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"));
    PATTERNS.put("AWS KEY", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"));
    PATTERNS.put("STRIPE KEY", Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{10,}\\b"));
    PATTERNS.put("GITHUB TOKEN", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"));
  }

  private PiiScrubber() {}

  public static Object scrub(Object value) {
    return scrub(value, null);
  }

  @SuppressWarnings("unchecked")
  public static Object scrub(Object value, String key) {
    if (isSensitiveKey(key) && value != null) {
      return REDACTED;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String entryKey = String.valueOf(entry.getKey());
        out.put(entryKey, scrub(entry.getValue(), entryKey));
      }
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(item -> scrub(item, key)).collect(Collectors.toList());
    }
    if (value instanceof String string) {
      return scrubString(string);
    }
    return value;
  }

  private static boolean isSensitiveKey(String key) {
    if (key == null) {
      return false;
    }
    String normalized = key.toLowerCase().replaceAll("[^a-z0-9]", "");
    for (String sensitive : SENSITIVE_KEYS) {
      if (normalized.contains(sensitive)) {
        return true;
      }
    }
    return false;
  }

  private static String scrubString(String input) {
    String result = input;
    for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
      Matcher matcher = entry.getValue().matcher(result);
      result = matcher.replaceAll(Matcher.quoteReplacement("[" + entry.getKey() + " FILTERED]"));
    }
    return result;
  }
}
