package io.github.lukepopwell.tracker;

import java.util.ArrayList;
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
      "iban", "accountnumber", "routingnumber", "bankaccount",
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

  // Card numbers in any other shape, and IBANs: see sdks/PII_SCRUBBING.md, which every SDK
  // follows, and pii_scrubbing_vectors.json in the test resources, which pins the results. Word
  // characters are ASCII only, so the boundaries are spelled out as [A-Za-z0-9_].
  private static final Pattern DIGIT_RUN =
      Pattern.compile("(?<![A-Za-z0-9_])[0-9](?:[ -]?[0-9])*(?![A-Za-z0-9_])");
  private static final Pattern CARD_PREFIXES = Pattern.compile(
      "(?:4|5[1-5]|2(?:22[1-9]|2[3-9][0-9]|[3-6][0-9][0-9]|7[01][0-9]|720)|3[47]|6011|64[4-9]|65"
          + "|35(?:2[89]|[3-8][0-9])|36|30[0-5]|62)");
  private static final Pattern IBAN = Pattern.compile(
      "(?<![A-Za-z0-9_])[A-Z]{2}[0-9]{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)"
          + "(?![A-Za-z0-9_])");

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
    // IBANs first: a spaced IBAN's middle groups look like a formatted card number.
    String result = IBAN.matcher(input).replaceAll(
        match -> Matcher.quoteReplacement(isIban(match.group()) ? "[IBAN FILTERED]" : match.group()));
    for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
      Matcher matcher = entry.getValue().matcher(result);
      result = matcher.replaceAll(Matcher.quoteReplacement("[" + entry.getKey() + " FILTERED]"));
    }
    return DIGIT_RUN.matcher(result).replaceAll(
        match -> Matcher.quoteReplacement(scrubCardNumbersIn(match.group())));
  }

  /**
   * From each group of the run on, the longest stretch of whole groups that is a card number is
   * replaced, so a card followed by its CVV, two cards in a row, and Amex's 4-6-5 are all found.
   */
  private static String scrubCardNumbersIn(String run) {
    List<String> digits = new ArrayList<>();
    List<String> separators = new ArrayList<>();
    int start = 0;
    for (int at = 0; at < run.length(); at++) {
      char c = run.charAt(at);
      if (c == ' ' || c == '-') {
        digits.add(run.substring(start, at));
        separators.add(String.valueOf(c));
        start = at + 1;
      }
    }
    digits.add(run.substring(start));
    separators.add("");

    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < digits.size()) {
      int j = -1;
      for (int last = digits.size() - 1; last >= i; last--) {
        if (isCardNumber(String.join("", digits.subList(i, last + 1)))) {
          j = last;
          break;
        }
      }
      if (j >= 0) {
        out.append("[CREDIT CARD FILTERED]").append(separators.get(j));
        i = j + 1;
      } else {
        out.append(digits.get(i)).append(separators.get(i));
        i++;
      }
    }
    return out.toString();
  }

  private static boolean isCardNumber(String digits) {
    return digits.length() >= 13
        && digits.length() <= 19
        && CARD_PREFIXES.matcher(digits).lookingAt()
        && isLuhn(digits);
  }

  private static boolean isLuhn(String digits) {
    int sum = 0;
    for (int index = 0; index < digits.length(); index++) {
      int value = digits.charAt(digits.length() - 1 - index) - '0';
      if (index % 2 == 1) {
        value *= 2;
        if (value > 9) {
          value -= 9;
        }
      }
      sum += value;
    }
    return sum % 10 == 0;
  }

  /**
   * The IBAN check, a digit at a time: the first four characters moved to the end, letters as two
   * digits (A is 10), and the remainder by 97 is 1.
   */
  private static boolean isIban(String candidate) {
    String compact = candidate.replace(" ", "");
    if (compact.length() < 15 || compact.length() > 34) {
      return false;
    }
    String rearranged = compact.substring(4) + compact.substring(0, 4);
    int remainder = 0;
    for (int at = 0; at < rearranged.length(); at++) {
      char c = rearranged.charAt(at);
      String number = c >= 'A' && c <= 'Z' ? String.valueOf(c - 'A' + 10) : String.valueOf(c);
      for (int k = 0; k < number.length(); k++) {
        remainder = (remainder * 10 + (number.charAt(k) - '0')) % 97;
      }
    }
    return remainder == 1;
  }
}
