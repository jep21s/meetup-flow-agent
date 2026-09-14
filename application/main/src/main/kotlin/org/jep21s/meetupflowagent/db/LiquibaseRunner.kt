package org.jep21s.meetupflowagent.db

import io.github.oshai.kotlinlogging.KotlinLogging
import liquibase.Contexts
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.koin.core.annotation.Singleton
import javax.sql.DataSource

private val logger = KotlinLogging.logger { }

/**
 * Прогон Liquibase-миграций (`db/changelog/master.xml`) при старте приложения —
 * ДО подключения Exposed: DDL принадлежит только Liquibase (§6), Exposed лишь
 * маппит готовые таблицы.
 */
@Singleton
class LiquibaseRunner {

  fun runMigrations(dataSource: DataSource) {
    dataSource.connection.use { connection ->
      val database = DatabaseFactory.getInstance()
        .findCorrectDatabaseImplementation(JdbcConnection(connection))
      Liquibase(MASTER_CHANGELOG, ClassLoaderResourceAccessor(), database).use { liquibase ->
        liquibase.update(Contexts())
      }
    }
    logger.info { "liquibase migrations applied ($MASTER_CHANGELOG)" }
  }

  companion object {
    private const val MASTER_CHANGELOG = "db/changelog/master.xml"
  }
}
