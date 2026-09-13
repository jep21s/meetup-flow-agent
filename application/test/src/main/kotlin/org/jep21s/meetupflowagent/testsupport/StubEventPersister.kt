package org.jep21s.meetupflowagent.testsupport

import org.jep21s.meetupflowagent.db.PersistOutcome
import org.jep21s.meetupflowagent.db.EventPersister
import java.util.UUID

/**
 * Стаб персистера для роут-тестов: возвращает предзаданный исход и запоминает,
 * какие ответы агента «записывались» (для assert'ов).
 */
class StubEventPersister(
  private val outcome: PersistOutcome = PersistOutcome.Skipped("stub"),
) : EventPersister {

  val persistedReplies = mutableListOf<String>()

  override suspend fun persist(agentReply: String): PersistOutcome {
    persistedReplies += agentReply
    return outcome
  }

  companion object {
    fun saved() = StubEventPersister(
      PersistOutcome.Saved(eventId = UUID.randomUUID(), flowId = UUID.randomUUID()),
    )

    fun duplicate(similarity: Double = 0.95) = StubEventPersister(
      PersistOutcome.Duplicate(
        existingEventId = UUID.randomUUID(),
        existingTitle = "PiterJS #61",
        similarity = similarity,
      ),
    )
  }
}
