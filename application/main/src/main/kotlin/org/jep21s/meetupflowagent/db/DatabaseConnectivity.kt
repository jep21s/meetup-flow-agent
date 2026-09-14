package org.jep21s.meetupflowagent.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.koin.core.annotation.Singleton

private val logger = KotlinLogging.logger { }

/**
 * Единственная точка владения соединениями: HikariCP-пул из конфига `db.*`,
 * миграции Liquibase ДО подключения Exposed, затем [database] для репозиториев.
 *
 * `createdAtStart = true` — поднимается при старте Koin, то есть до старта Ktor-роутов:
 * сервис не отвечает запросам, пока схема не применена.
 */
@Singleton(createdAtStart = true)
class DatabaseConnectivity(private val liquibaseRunner: LiquibaseRunner) {

  val dataSource: HikariDataSource = HikariDataSource(
    HikariConfig().apply {
      jdbcUrl = "jdbc:postgresql://${cfg("db.host")}:${cfg("db.port")}/${cfg("db.name")}"
      username = cfg("db.user")
      password = ConfigLoader.getRequiredProperty(
        "db.password",
        "db.password is not configured (DB_PASSWORD)",
      )
      poolName = "meetup-flow-agent"
      maximumPoolSize = 10
      minimumIdle = 2
      connectionTimeout = 10_000
      // vector/jsonb Exposed биндит строкой: без unspecified PG отвергает varchar-параметр
      addDataSourceProperty("stringtype", "unspecified")
      validate()
    },
  )

  val database: Database

  init {
    liquibaseRunner.runMigrations(dataSource)
    database = Database.connect(dataSource)
    logger.info { "database connectivity ready: ${cfg("db.host")}:${cfg("db.port")}/${cfg("db.name")}" }
  }

  private fun cfg(key: String): String = ConfigLoader.getRequiredProperty(
    key,
    "$key is not configured (${key.uppercase().replace('.', '_')})",
  )
}
