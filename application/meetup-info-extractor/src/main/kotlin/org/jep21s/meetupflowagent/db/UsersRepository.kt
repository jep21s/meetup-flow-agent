package org.jep21s.meetupflowagent.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.core.annotation.Singleton

private val logger = KotlinLogging.logger { }

/**
 * Чтение справочника пользователей (таблица `users`, changeSet `080-users`):
 * Telegram-id адресатов HITL-вопросов и системных уведомлений. Наполнение —
 * ручной SQL (см. README), писателя в v1 нет.
 */
@Singleton
class UsersRepository(private val db: DatabaseConnectivity) {

  /**
   * Telegram-id активных пользователей; сбой БД не должен ронять уведомление —
   * возвращаем пустой список (прокси отправит в общий канал по fallback).
   */
  suspend fun activeTelegramUserIds(): List<Long> = try {
    withContext(Dispatchers.IO) {
      suspendTransaction(db.database) {
        Users.selectAll().where { Users.isActive eq true }.map { it[Users.telegramUserId] }
      }
    }
  } catch (e: Exception) {
    logger.warn(e) { "cannot load active users for notification" }
    emptyList()
  }
}
