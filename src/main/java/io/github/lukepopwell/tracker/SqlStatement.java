package io.github.lukepopwell.tracker;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the SQL behind a database error and reduces it to something safe to send: the names of
 * the stored procedures, tables and views it touched, and (only if {@link
 * Configuration#isCaptureSqlStatement} is on) the statement itself with every string and number
 * replaced by {@code ?}. Ported from {@code gems/forge_ops_tracker}'s {@code SqlStatement}, which
 * is itself ported from the server's own {@code SqlStatementMasker}/{@code SqlObjectExtractor}:
 * same rules everywhere, and the server applies them again on arrival, so a difference here can
 * only ever mean less is masked client-side, never that something unmasked gets stored.
 *
 * <p>Deliberately a single pass over a few patterns, not a SQL parser.
 */
final class SqlStatement {
  static final String MASK = "?";
  static final int MAX_LENGTH = 4000;
  static final int MAX_NAMES = 10;
  static final int MAX_NAME_LENGTH = 200;
  private static final int MAX_CAUSE_DEPTH = 5;

  // A string's type prefix (E'', X'', N'', B'', U&'') only counts when it isn't the end of a word,
  // but the quote always does: LIKE'%x%', with no space, is still a string. A backslash escapes the
  // next character ('o\'brien'); where a backslash is just a character, the string is masked on to
  // the end: more than needed, never less. The possessive run of ordinary characters ([^'\\]++)
  // matches exactly what the server's pattern does; it only keeps a long string from costing one
  // stack frame per character in java.util.regex.
  private static final String STRING = "(?:(?<![\\w$])(?:[EeXxNnBb]|[Uu]&))?'(?:[^'\\\\]++|\\\\(?:.|\\z)|'')*(?:'|\\z)";
  private static final String DOUBLE_QUOTED_STRING = "\"(?:[^\"\\\\]++|\\\\(?:.|\\z)|\"\")*(?:\"|\\z)";
  private static final String DOLLAR_QUOTED = "(?<tag>\\$[A-Za-z_]*\\$).*?(?:\\k<tag>|\\z)";
  // Integers, decimals (.5 too), exponents, hex and binary, never the digits inside an identifier.
  private static final String NUMBER =
      "(?<![\\w$.])(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)(?!\\w)";
  private static final Pattern LITERAL =
      Pattern.compile(STRING + "|" + DOLLAR_QUOTED + "|" + NUMBER, Pattern.DOTALL);
  // MySQL and MariaDB read "double quotes" as a string, not a name, so for those they're masked too.
  private static final Pattern LITERAL_WITH_DOUBLE_QUOTES =
      Pattern.compile(STRING + "|" + DOUBLE_QUOTED_STRING + "|" + DOLLAR_QUOTED + "|" + NUMBER, Pattern.DOTALL);
  private static final Set<String> DOUBLE_QUOTED_STRING_SYSTEMS = Set.of("mysql", "mariadb");

  private static final String PART = "(?:[\\w$#@]+|\"[^\"]+\"|\\[[^\\]]+\\]|`[^`]+`)";
  private static final String NAME = PART + "(?:\\." + PART + ")*";
  private static final Set<String> OPERATIONS =
      Set.of(
          "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "CALL", "EXEC", "EXECUTE", "CREATE", "ALTER", "DROP",
          "TRUNCATE");
  private static final Pattern PROCEDURE_CALL =
      Pattern.compile(
          "\\b(?:CALL|EXEC(?:UTE)?|PERFORM)\\s+(?!IMMEDIATE\\b|FUNCTION\\b|PROCEDURE\\b)(" + NAME + ")",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern RELATION =
      Pattern.compile("\\b(FROM|JOIN|INTO|UPDATE|TABLE)\\s+(" + NAME + ")(\\s*\\()?", Pattern.CASE_INSENSITIVE);
  private static final Pattern SELECT_FUNCTION =
      Pattern.compile("\\A\\s*SELECT\\s+(" + NAME + ")\\s*\\(", Pattern.CASE_INSENSITIVE);
  private static final Set<String> BUILTINS =
      Set.of(
          "count", "sum", "min", "max", "avg", "now", "coalesce", "nullif", "lower", "upper", "length", "concat",
          "cast", "date_trunc", "current_timestamp", "current_date", "row_number", "rank", "json_build_object",
          "json_agg", "array_agg");
  private static final Pattern FROM_INSIDE_FUNCTION =
      Pattern.compile("\\b(?:EXTRACT|SUBSTRING|TRIM|OVERLAY)\\s*\\([^()]*\\)", Pattern.CASE_INSENSITIVE);
  private static final Set<String> KEYWORDS_NOT_NAMES =
      Set.of("select", "set", "values", "where", "lateral", "only", "unnest", "generate_series");
  private static final Pattern FULL_NAME = Pattern.compile("\\A" + NAME + "\\z");
  private static final Pattern FROM_WORD = Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern FIRST_WORD = Pattern.compile("\\A\\s*(\\w+)");

  /** The accessor names JVM database layers use for the failing statement: Hibernate's {@code
   * JDBCException#getSQL}, Spring's {@code BadSqlGrammarException#getSql} and {@code
   * UncategorizedSQLException#getSql}. Read reflectively so this client never depends on any of
   * them. */
  private static final String[] STATEMENT_ACCESSORS = {"getSQL", "getSql"};

  private SqlStatement() {}

  /**
   * The raw statement off the error itself or, for an app that wraps a database error in its own
   * exception, off whatever it was raised from ({@code getCause()}, or the next exception in a
   * {@link SQLException} chain).
   */
  static String findIn(Throwable error) {
    Set<Throwable> seen = new HashSet<>();
    List<Throwable> queue = new ArrayList<>();
    queue.add(error);
    int visited = 0;
    while (!queue.isEmpty() && visited < MAX_CAUSE_DEPTH * 3) {
      Throwable current = queue.remove(0);
      visited++;
      if (current == null || !seen.add(current)) {
        continue;
      }
      String statement = statementOf(current);
      if (statement != null) {
        return statement;
      }
      queue.add(current.getCause());
      if (current instanceof SQLException) {
        queue.add(((SQLException) current).getNextException());
      }
    }
    return null;
  }

  private static String statementOf(Throwable error) {
    for (String accessor : STATEMENT_ACCESSORS) {
      try {
        Method method = error.getClass().getMethod(accessor);
        if (method.getReturnType() == String.class && method.getParameterCount() == 0) {
          Object value = method.invoke(error);
          if (value instanceof String && !((String) value).isBlank()) {
            return (String) value;
          }
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // No such accessor on this class, or it isn't callable: not a source of SQL, keep looking.
      }
    }
    return null;
  }

  static String mask(String statement) {
    return mask(statement, null);
  }

  /**
   * {@code system} is the statement's {@code db.system} when known: for {@code "mysql"} and {@code
   * "mariadb"} (any case), double-quoted strings are masked too.
   */
  static String mask(String statement, String system) {
    if (statement == null || statement.isBlank()) {
      return null;
    }
    boolean doubleQuotedStrings =
        system != null && DOUBLE_QUOTED_STRING_SYSTEMS.contains(system.toLowerCase(Locale.ROOT));
    Pattern pattern = doubleQuotedStrings ? LITERAL_WITH_DOUBLE_QUOTES : LITERAL;
    String masked;
    try {
      masked = pattern.matcher(statement).replaceAll(Matcher.quoteReplacement(MASK));
    } catch (StackOverflowError e) {
      // A string with thousands of escaped quotes in it still recurses once per escape. Rather
      // than send any of it, send nothing but the mask.
      return MASK;
    }
    return masked.length() > MAX_LENGTH ? masked.substring(0, MAX_LENGTH) + "..." : masked;
  }

  /**
   * Takes an already-masked statement (so a keyword inside a string value can't be mistaken for
   * SQL). Returns null when nothing recognizable was found.
   */
  static Map<String, Object> objects(String masked) {
    if (masked == null || masked.isBlank()) {
      return null;
    }

    String sql = FROM_INSIDE_FUNCTION.matcher(masked).replaceAll(" ");
    List<String> procedures = new ArrayList<>();
    Matcher call = PROCEDURE_CALL.matcher(sql);
    while (call.find()) {
      procedures.add(call.group(1));
    }
    List<String> relations = new ArrayList<>();

    Matcher relation = RELATION.matcher(sql);
    while (relation.find()) {
      String name = relation.group(2);
      if (KEYWORDS_NOT_NAMES.contains(name.toLowerCase())) {
        continue;
      }
      String keyword = relation.group(1).toUpperCase();
      boolean functionCall = relation.group(3) != null && (keyword.equals("FROM") || keyword.equals("JOIN"));
      (functionCall ? procedures : relations).add(name);
    }

    Matcher select = SELECT_FUNCTION.matcher(sql);
    if (select.find() && !BUILTINS.contains(select.group(1).toLowerCase()) && !FROM_WORD.matcher(sql).find()) {
      procedures.add(select.group(1));
    }

    Matcher first = FIRST_WORD.matcher(sql);
    String operation = first.find() ? first.group(1).toUpperCase() : "";
    Map<String, Object> result = new LinkedHashMap<>();
    if (OPERATIONS.contains(operation)) {
      result.put("operation", operation);
    }
    List<String> cleanProcedures = clean(procedures);
    List<String> cleanRelations = clean(relations);
    result.put("procedures", cleanProcedures);
    result.put("relations", cleanRelations);
    if (cleanProcedures.isEmpty() && cleanRelations.isEmpty() && !result.containsKey("operation")) {
      return null;
    }
    return result;
  }

  private static List<String> clean(List<String> names) {
    List<String> cleaned = new ArrayList<>();
    for (String raw : names) {
      String name = raw.strip();
      if (name.length() > MAX_NAME_LENGTH) {
        name = name.substring(0, MAX_NAME_LENGTH);
      }
      if (FULL_NAME.matcher(name).matches() && !cleaned.contains(name)) {
        cleaned.add(name);
      }
    }
    return cleaned.size() > MAX_NAMES ? new ArrayList<>(cleaned.subList(0, MAX_NAMES)) : cleaned;
  }
}
