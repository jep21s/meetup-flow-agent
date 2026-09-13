package org.jep21s.meetupflowagent.db

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/** Миграции применяются в PostgresTestBase; здесь проверяем идемпотентность и схему. */
class SchemaMigrationTest : PostgresTestBase() {

  @Test
  fun `second migration run is a no-op`() {
    // повторный прогон на уже смигрированной БД не падает (changeSet'ы уже applied)
    LiquibaseRunner().runMigrations(ContainerHolder.dataSource)
  }

  @Test
  fun `schema contains all tables of section 6`() {
    val tables = transaction(database) {
      exec(
        """SELECT table_name FROM information_schema.tables
           WHERE table_schema = 'public' ORDER BY table_name""",
      ) { rs ->
        val result = mutableListOf<String>()
        while (rs.next()) result += rs.getString(1)
        result
      }.orEmpty()
    }
    assertThat(tables).contains(
      "inbox_messages", "flows", "flow_steps", "events", "human_requests", "duplicates", "users",
    )
  }

  @Test
  fun `events table has vector column and hnsw index`() {
    val vectorTypmod = transaction(database) {
      exec(
        """SELECT atttypmod FROM pg_attribute a
           JOIN pg_class c ON a.attrelid = c.oid
           WHERE c.relname = 'events' AND a.attname = 'embedding' AND a.attisdropped = false""",
      ) { rs ->
        if (rs.next()) rs.getInt(1) else -1
      } ?: -1
    }
    // atttypmod для vector(n) = n (размерность сохраняется в typmod)
    assertThat(vectorTypmod).isEqualTo(768)

    val indexes = transaction(database) {
      exec("SELECT indexname FROM pg_indexes WHERE tablename = 'events'") { rs ->
        val result = mutableListOf<String>()
        while (rs.next()) result += rs.getString(1)
        result
      }.orEmpty()
    }
    assertThat(indexes).contains("idx_events_embedding_hnsw", "idx_events_starts_at", "pk_events")
  }

  @Test
  fun `status check constraint rejects invalid flow status`() {
    val repo = FlowRepository(testConnectivity())
    val error = runCatching {
      kotlinx.coroutines.runBlocking { repo.create(status = "WRONG_STATUS") }
    }.exceptionOrNull()
    assertThat(error).isNotNull()
    assertThat(error!!.message).contains("ck_flows_status")
  }
}