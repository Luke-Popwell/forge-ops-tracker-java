package io.github.lukepopwell.tracker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules {@link Configuration#isStrictPrivacy} applies, for customers who can't let personal or
 * health data leave their own servers: only structured facts about an error are sent, never free
 * text that could hold a name, an email or a diagnosis. Ported from the server's own {@code
 * StrictPrivacy} (see {@code sdks/STRICT_PRIVACY.md}), which applies the same rules again on
 * arrival when the project's own Strict privacy setting is on; {@code strict_privacy_vectors.json}
 * in the test resources pins the results, so the two can never drift apart unnoticed.
 *
 * <p>"Word character" is ASCII {@code [A-Za-z0-9_]} only, spelled out as a class rather than
 * {@code \w}/{@code \b}, so no regex flag (or a future default) can make a digit in another
 * script or an accented letter count as one.
 */
public final class StrictPrivacy {
  static final int FINGERPRINT_LENGTH = 32;

  private static final String WORD = "A-Za-z0-9_";
  private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
  // A quoted value (KeyError: 'Jane Doe') is data, not the shape of the error. Single quotes only open
  // and close at a word boundary, so a contraction (Couldn't) or the `email' style never starts one.
  private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"\\r\\n]*\"");
  private static final Pattern SINGLE_QUOTED = Pattern.compile("(?<![" + WORD + "])'[^'\\r\\n]*'(?![" + WORD + "])");
  private static final Pattern HEX_OBJECT_ID = Pattern.compile("0x[0-9a-fA-F]{4,}");
  private static final Pattern UUID = Pattern.compile("(?<![" + WORD + "])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
      + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![" + WORD + "])");
  private static final Pattern NUMBER = Pattern.compile("(?<![" + WORD + "])[0-9]+(?![" + WORD + "])");
  private static final Pattern HTTP_METHOD =
      Pattern.compile("^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
  private static final Pattern SPLIT_AUTHORITY = Pattern.compile("[/?#]");
  private static final Pattern PORT = Pattern.compile(":[0-9]+\\z");
  private static final Pattern ASCII_DIGIT = Pattern.compile("[0-9]");
  private static final int MAX_SEGMENT_LENGTH = 40;
  private static final Pattern SPLIT_QUERY = Pattern.compile("[?#]");
  // A route template's parameter segment: ":id", "{id}", "<int:id>", "[slug]" or "*path".
  private static final Pattern ROUTE_PARAMETER =
      Pattern.compile("\\A(?::[^\\n]+|\\{[^\\n]+\\}|<[^\\n]+>|\\[[^\\n]+\\]|\\*[^\\n]*)\\z");

  private StrictPrivacy() {}

  /**
   * The message with emails, quoted values, hex object ids, UUIDs and standalone numbers replaced,
   * in that order; a {@code null} message is {@code ""}.
   */
  public static String normalizeMessage(String message) {
    String normalized = message == null ? "" : message;
    normalized = EMAIL.matcher(normalized).replaceAll("<email>");
    normalized = DOUBLE_QUOTED.matcher(normalized).replaceAll("<str>");
    normalized = SINGLE_QUOTED.matcher(normalized).replaceAll("<str>");
    normalized = HEX_OBJECT_ID.matcher(normalized).replaceAll("0x");
    normalized = UUID.matcher(normalized).replaceAll("<uuid>");
    return NUMBER.matcher(normalized).replaceAll("<n>");
  }

  /**
   * What's sent in place of the message: the first 32 lowercase hex characters of the SHA-256 of
   * the normalized message's UTF-8 bytes. Lets ForgeOps group errors without ever seeing the text.
   */
  public static String messageFingerprint(String message) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(normalizeMessage(message).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.substring(0, FINGERPRINT_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-256; never reached in practice.
      throw new IllegalStateException(e);
    }
  }

  /**
   * A path that can't name a person, with no query or fragment. A route template (any segment a
   * parameter: {@code :id}, {@code {id}}, {@code <int:id>}, {@code [slug]}, {@code *path}) is kept
   * as it is. A raw path keeps only its first segment ({@code ":id"} if it holds an ASCII digit or
   * is longer than 40 characters), then {@code "/:path"} if there was more: {@code
   * "/patients/jane-doe/12"} is {@code "/patients/:path"}. No segments at all is {@code "/"}.
   */
  public static String strictPath(String path) {
    String withoutQuery = SPLIT_QUERY.split(path == null ? "" : path, 2)[0];
    List<String> segments = new ArrayList<>();
    for (String segment : withoutQuery.split("/")) {
      if (ROUTE_PARAMETER.matcher(segment).matches()) {
        return withoutQuery;
      }
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }
    if (segments.isEmpty()) {
      return "/";
    }
    String first = segments.get(0);
    boolean id = ASCII_DIGIT.matcher(first).find() || first.codePointCount(0, first.length()) > MAX_SEGMENT_LENGTH;
    return (withoutQuery.startsWith("/") ? "/" : "") + (id ? ":id" : first) + (segments.size() > 1 ? "/:path" : "");
  }

  /** @deprecated Renamed {@link #strictPath}, which it now calls; kept so existing callers still compile. */
  @Deprecated
  public static String pathWithoutIds(String path) {
    return strictPath(path);
  }

  /**
   * A span name (or a transaction name) with no path or host detail that could name a person:
   * {@code "GET https://api.example.com/patients/42?x=1"} is {@code "GET api.example.com"}, and a
   * path, bare ({@code "/patients/42"}) or after a method ({@code "GET /patients/42"}), has its ids
   * taken out. A target that isn't a host keeps its path with ids taken out; anything else ({@code
   * "OrdersController#create"}) is returned unchanged. Read by hand, never with a URL parser, so an
   * address a parser would reject can't make the whole address go out.
   */
  public static String spanName(String name) {
    if (name == null) {
      return null;
    }
    if (name.startsWith("/")) {
      return strictPath(name);
    }
    Matcher match = HTTP_METHOD.matcher(name);
    if (!match.find()) {
      return name;
    }
    String method = match.group(1).toUpperCase(Locale.ROOT);
    String target = match.group(2);
    if (target.startsWith("/")) {
      return method + " " + strictPath(target);
    }
    String host = hostOf(target);
    return method + " " + (host != null ? host : strictPath(target));
  }

  /**
   * The host of an outbound call's target: without any {@code scheme://}, userinfo or {@code :port},
   * and only when it really is one (the address had a scheme or a port, or the host has a dot or is
   * {@code localhost}); {@code null} otherwise.
   */
  private static String hostOf(String target) {
    Matcher scheme = SCHEME.matcher(target);
    boolean hadScheme = scheme.find();
    String authority = SPLIT_AUTHORITY.split(hadScheme ? target.substring(scheme.end()) : target, 2)[0];
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
      authority = authority.substring(at + 1);
    }
    Matcher port = PORT.matcher(authority);
    boolean hadPort = port.find();
    String host = hadPort ? authority.substring(0, port.start()) : authority;
    if (host.isEmpty()) {
      return null;
    }
    return hadScheme || hadPort || host.contains(".") || host.equalsIgnoreCase("localhost") ? host : null;
  }

  /**
   * Only the top-level entries whose key is allowed and whose value is a string, number or
   * boolean; {@code null} when nothing is left, so the field is left out of the payload.
   */
  static Map<String, Object> allowed(Map<String, Object> fields, Collection<String> allowedFields) {
    if (fields == null || allowedFields == null || allowedFields.isEmpty()) {
      return null;
    }
    Map<String, Object> kept = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      Object value = entry.getValue();
      if (allowedFields.contains(entry.getKey())
          && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
        kept.put(entry.getKey(), value);
      }
    }
    return kept.isEmpty() ? null : kept;
  }

  /** The user's {@code id} only, as a string; {@code null} when there's no id. */
  static Map<String, Object> user(Map<String, Object> user) {
    Object id = user == null ? null : user.get("id");
    if (id == null || id.toString().isBlank()) {
      return null;
    }
    Map<String, Object> kept = new LinkedHashMap<>();
    kept.put("id", id.toString());
    return kept;
  }

  /** Span data keeps only the (already masked) {@code db.statement} and {@code db.system}. */
  static Map<String, Object> spanData(Map<String, Object> data) {
    Map<String, Object> kept = new LinkedHashMap<>();
    if (data != null) {
      for (String key : new String[] {SpanBuffer.DB_STATEMENT, SpanBuffer.DB_SYSTEM}) {
        if (data.containsKey(key)) {
          kept.put(key, data.get(key));
        }
      }
    }
    return kept;
  }
}
