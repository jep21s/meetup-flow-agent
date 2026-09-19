package org.jep21s.meetupflowagent.testsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jep21s.meetupflowagent.db.DatabaseConnectivity
import org.jep21s.meetupflowagent.db.Destinations
import org.jep21s.meetupflowagent.db.Duplicates
import org.jep21s.meetupflowagent.db.Events
import org.jep21s.meetupflowagent.db.FlowSteps
import org.jep21s.meetupflowagent.db.Flows
import org.jep21s.meetupflowagent.db.HumanRequests
import org.jep21s.meetupflowagent.db.InboxMessages
import org.jep21s.meetupflowagent.db.LiquibaseRunner
import org.jep21s.meetupflowagent.db.OutboxDeliveries
import org.jep21s.meetupflowagent.db.OutboxMessages
import org.jep21s.meetupflowagent.db.Users
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Общая база интеграционных тестов с реальным Postgres+pgvector (Testcontainers,
 * не мок — §16). Один контейнер на JVM: старт + миграции Liquibase + Exposed
 * connect выполняются лениво один раз; тесты чистят таблицы перед каждым тестом.
 */
abstract class PostgresTestBase {

  val database get() = ContainerHolder.database

  /** Репозиториям из теста нужна только ссылка на [org.jetbrains.exposed.v1.jdbc.Database]. */
  fun testConnectivity(): DatabaseConnectivity = mockk {
    every { database } returns ContainerHolder.database
  }

  @org.junit.jupiter.api.BeforeEach
  fun cleanTables() {
    transaction(ContainerHolder.database) {
      // сначала доставки/публикации: FK ссылаются на events и flows
      OutboxDeliveries.deleteAll()
      OutboxMessages.deleteAll()
      Duplicates.deleteAll()
      Events.deleteAll()
      FlowSteps.deleteAll()
      HumanRequests.deleteAll()
      // циклическая связь inbox ↔ flows (fk_flows_inbox_message + fk_inbox_messages_flow):
      // сначала рвём ссылки, иначе deleteAll падает на FK
      Flows.update { it[inboxMessageId] = null }
      InboxMessages.update { it[flowId] = null }
      InboxMessages.deleteAll()
      Flows.deleteAll()
      Users.deleteAll()
      org.jep21s.meetupflowagent.telegram.db.TelegramQuestions.deleteAll()
      // справочник: оставляем только сеянную миграцией telegram_main и включаем её —
      // тесты добавляют/выключают свои назначения
      Destinations.deleteWhere { Destinations.name neq "telegram_main" }
      Destinations.update({ Destinations.name eq "telegram_main" }) {
        it[isActive] = true
      }
    }
  }

  object ContainerHolder {
    val container: PostgreSQLContainer<*> = PostgreSQLContainer(DOCKER_IMAGE)
      .withDatabaseName("meetup_test")
      .withUsername("test")
      .withPassword("test")

    val dataSource: HikariDataSource

    val database: Database

    init {
      container.start()
      dataSource = HikariDataSource(
        HikariConfig().apply {
          jdbcUrl = container.jdbcUrl
          username = container.username
          password = container.password
          maximumPoolSize = 4
          // важно: тот же биндинг, что в прод-пуле (vector/jsonb строкой)
          addDataSourceProperty("stringtype", "unspecified")
        },
      )
      LiquibaseRunner().runMigrations(dataSource)
      database = Database.connect(dataSource)
    }

    const val DOCKER_IMAGE = "pgvector/pgvector:pg18"
  }
}
