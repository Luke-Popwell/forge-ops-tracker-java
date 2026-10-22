package io.github.lukepopwell.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlStatementTest {
  /** Stands in for Hibernate's JDBCException, which exposes the statement as getSQL(). */
  static class FakeJdbcException extends RuntimeException {
    private final String sql;

    FakeJdbcException(String message, String sql) {
      super(message);
      this.sql = sql;
    }

    public String getSQL() {
      return sql;
    }
  }

  /** Stands in for Spring's BadSqlGrammarException, which uses getSql(). */
  static class FakeSpringException extends RuntimeException {
    public String getSql() {
      return "CALL refund_order(1)";
    }
  }

  @Test
  void findsTheStatementViaEitherAccessorName() {
    assertEquals("SELECT 1", SqlStatement.findIn(new FakeJdbcException("boom", "SELECT 1")));
    assertEquals("CALL refund_order(1)", SqlStatement.findIn(new FakeSpringException()));
  }

  @Test
  void walksTheCauseChainAndSqlExceptionChain() {
    RuntimeException wrapped = new RuntimeException("refund failed", new FakeJdbcException("db", "CALL x(1)"));
    assertEquals("CALL x(1)", SqlStatement.findIn(wrapped));

    SQLException first = new SQLException("first");
    first.setNextException(new SQLException("second", new FakeJdbcException("db", "SELECT 2")));
    assertEquals("SELECT 2", SqlStatement.findIn(first));
  }

  @Test
  void returnsNullWithoutSql() {
    assertNull(SqlStatement.findIn(new IllegalStateException("nope")));
    assertNull(SqlStatement.findIn(new FakeJdbcException("blank", "  ")));
  }

  @Test
  void masksStringsAndNumbersButNotIdentifiersOrPlaceholders() {
    assertEquals(
        "SELECT * FROM orders2 WHERE email = ? AND id = ? AND x = $1",
        SqlStatement.mask("SELECT * FROM orders2 WHERE email = 'a@b.co' AND id = 42 AND x = $1"));
    assertEquals("SELECT price * ? FROM t", SqlStatement.mask("SELECT price * 1.5 FROM t"));
  }

  @Test
  void masksAnEscapedQuoteACutOffStringAndADollarQuotedBody() {
    assertEquals("EXEC sp_x @t = ?", SqlStatement.mask("EXEC sp_x @t = 'it''s'"));
    assertEquals("SELECT ? WHERE n = ?", SqlStatement.mask("SELECT 1 WHERE n = 'oops"));
    assertEquals("DO ?", SqlStatement.mask("DO $b$ BEGIN PERFORM 1; END $b$"));
  }

  @Test
  void isIdempotentTruncatesAndReturnsNullForBlank() {
    String once = SqlStatement.mask("SELECT * FROM t WHERE a = 'x' AND b = 9");
    assertEquals(once, SqlStatement.mask(once));
    assertEquals(SqlStatement.MAX_LENGTH + 3, SqlStatement.mask("SELECT " + "a, ".repeat(3000) + " b").length());
    assertNull(SqlStatement.mask("  "));
    assertNull(SqlStatement.mask(null));
  }

  /** The server's own test set for SqlStatementMasker: input, db.system (or null), expected. */
  private static final String[][] SHARED_CASES = {
    {"SELECT * FROM orders WHERE email = 'a@b.co' AND id = 42 LIMIT 10", null, "SELECT * FROM orders WHERE email = ? AND id = ? LIMIT ?"},
    {"EXEC sp_note @text = 'it''s broken'", null, "EXEC sp_note @text = ?"},
    {"SELECT 1 WHERE name = 'unterminated", null, "SELECT ? WHERE name = ?"},
    {"DO $body$ BEGIN PERFORM 1; END $body$", null, "DO ?"},
    {"SELECT \"user id\" FROM orders2 WHERE id = $1 AND v = sp_v2(?)", null, "SELECT \"user id\" FROM orders2 WHERE id = $1 AND v = sp_v2(?)"},
    {"SELECT price * 1.5 FROM t", null, "SELECT price * ? FROM t"},
    {"SELECT * FROM users WHERE name = E'o\\'brien' AND id = 1", null, "SELECT * FROM users WHERE name = ? AND id = ?"},
    {"SELECT * FROM users WHERE name = 'o\\'brien' AND id = 1", null, "SELECT * FROM users WHERE name = ? AND id = ?"},
    {"SELECT * FROM t WHERE b = X'DEADBEEF' AND s = N'uni' AND u = U&'d\\0061t' AND e = e'x'", null, "SELECT * FROM t WHERE b = ? AND s = ? AND u = ? AND e = ?"},
    {"SELECT * FROM t WHERE a LIKE'%secret%'", null, "SELECT * FROM t WHERE a LIKE?"},
    {"SELECT * FROM t WHERE f = 0x1F AND b = 0b101 AND n = 3e10 AND m = 1.5E-3 AND k = .5", null, "SELECT * FROM t WHERE f = ? AND b = ? AND n = ? AND m = ? AND k = ?"},
    {"SELECT e, t.col, 1e5e FROM t", null, "SELECT e, t.col, 1e5e FROM t"},
    {"SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "mysql", "SELECT ? FROM t WHERE token = ?"},
    {"SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "MariaDB", "SELECT ? FROM t WHERE token = ?"},
    {"SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "postgresql", "SELECT \"user id\" FROM t WHERE token = \"abc123secret\""},
    {"SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", null, "SELECT \"user id\" FROM t WHERE token = \"abc123secret\""},
    {"SELECT * FROM t WHERE a = 'x' AND b = 9", null, "SELECT * FROM t WHERE a = ? AND b = ?"},
    {"SELECT * FROM t WHERE a = ? AND b = ?", null, "SELECT * FROM t WHERE a = ? AND b = ?"},
    {"SELECT * FROM t WHERE path = 'C:\\\\dir\\\\' AND n = 5", null, "SELECT * FROM t WHERE path = ? AND n = ?"},
    {"INSERT INTO t (a, b) VALUES (-5, +3.25e+2)", null, "INSERT INTO t (a, b) VALUES (-?, +?)"},
    {"SELECT * FROM t WHERE a = 'secret\\", null, "SELECT * FROM t WHERE a = ?"},
    {"SELECT * FROM t WHERE a = \"secret\\", "mysql", "SELECT * FROM t WHERE a = ?"},
  };

  @Test
  void matchesTheServersMaskerOnEverySharedCase() {
    for (String[] c : SHARED_CASES) {
      assertEquals(c[2], SqlStatement.mask(c[0], c[1]), c[0] + " (" + c[1] + ")");
      assertEquals(c[2], SqlStatement.mask(c[2], c[1]), "masking again changed " + c[2]);
    }
  }

  @Test
  void masksAVeryLongStringInOnePieceAndNeverOverflows() {
    assertEquals("SELECT ?", SqlStatement.mask("SELECT '" + "x".repeat(100_000) + "'"));
    assertEquals("SELECT ?", SqlStatement.mask("SELECT \"" + "x".repeat(100_000) + "\"", "mysql"));
    String escapes = SqlStatement.mask("SELECT '" + "x\\'".repeat(50_000) + "'");
    assertTrue(escapes.equals("SELECT ?") || escapes.equals("?"), escapes);
  }

  @Test
  void findsAStoredProcedureWithItsSchema() {
    Map<String, Object> found = SqlStatement.objects("EXEC dbo.sp_refund_order @id = ?");
    assertEquals("EXEC", found.get("operation"));
    assertEquals(List.of("dbo.sp_refund_order"), found.get("procedures"));
    assertEquals(List.of(), found.get("relations"));
    assertEquals(List.of("refund_order"), SqlStatement.objects("CALL refund_order(?, ?)").get("procedures"));
    assertEquals(List.of("refund_order"), SqlStatement.objects("SELECT refund_order(?, ?)").get("procedures"));
  }

  @Test
  void findsViewsJoinedTablesAndTableFunctions() {
    assertEquals(
        List.of("v_totals", "public.customers"),
        SqlStatement.objects("SELECT * FROM v_totals t JOIN public.customers c ON c.id = t.id").get("relations"));
    assertEquals(List.of("get_open_orders"), SqlStatement.objects("SELECT * FROM get_open_orders(?) o").get("procedures"));
  }

  @Test
  void doesNotMisreadColumnListsOrBuiltinsAndReturnsNullForGarbage() {
    assertEquals(List.of(), SqlStatement.objects("INSERT INTO audit_log (a) VALUES (?)").get("procedures"));
    assertEquals(List.of(), SqlStatement.objects("SELECT count(*) FROM orders").get("procedures"));
    assertNull(SqlStatement.objects("garbage"));
  }

  @Test
  void eventBuilderSendsProcedureNameButNotStatementByDefault() {
    Configuration config = new Configuration();
    config.setEnvironment("production");
    Map<String, Object> payload =
        new EventBuilder(config).build(new FakeJdbcException("boom", "EXEC dbo.sp_refund_order @order_id = 8814, @note = 'a@b.co'"), Map.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> objects = (Map<String, Object>) payload.get("sql_objects");
    assertEquals(List.of("dbo.sp_refund_order"), objects.get("procedures"));
    assertFalse(payload.containsKey("sql_statement"));
  }

  @Test
  void eventBuilderSendsTheMaskedStatementWhenOptedInAndNothingWhenOff() {
    Configuration config = new Configuration();
    config.setEnvironment("production");
    config.setCaptureSqlStatement(true);
    FakeJdbcException error = new FakeJdbcException("boom", "EXEC dbo.sp_refund_order @order_id = 8814, @note = 'a@b.co'");
    assertEquals("EXEC dbo.sp_refund_order @order_id = ?, @note = ?", new EventBuilder(config).build(error, Map.of()).get("sql_statement"));

    config.setCaptureSqlObjects(false);
    config.setCaptureSqlStatement(false);
    Map<String, Object> off = new EventBuilder(config).build(error, Map.of());
    assertFalse(off.containsKey("sql_objects"));
    assertFalse(off.containsKey("sql_statement"));
    assertTrue(new EventBuilder(new Configuration()).build(new RuntimeException("nope"), Map.of()).get("sql_objects") == null);
  }
}
