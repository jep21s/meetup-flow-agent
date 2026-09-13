package org.jep21s.meetupflowagent.db

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.llm.EmbeddingClient
import org.jep21s.meetupflowagent.llm.EmbeddingException
import org.jep21s.meetupflowagent.llm.LlmException
import org.jep21s.meetupflowagent.testsupport.FakeEmbeddingClient
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Юнит-тест персистера: скрипты JSON агента → исходы записи (моки репозиториев). */
class EventPersisterTest {

  private val eventRepository: EventRepository = mockk(relaxed = true)
  private val flowRepository: FlowRepository = mockk(relaxed = true)
  private val embeddingClient: EmbeddingClient = FakeEmbeddingClient()

  private fun persister(embedder: EmbeddingClient = embeddingClient) =
    DbEventPersister(embedder, eventRepository, flowRepository)

  @Test
  fun `non-json reply is skipped without flow`() {
    val outcome = runBlocking { persister().persist("мусор не JSON") }

    assertThat(outcome).isInstanceOf(PersistOutcome.Skipped::class.java)
    coVerify(exactly = 0) { flowRepository.create(any(), any()) }
  }

  @Test
  fun `rejected verdict creates flow in REJECTED and skips event`() {
    val flowId = UUID.randomUUID()
    coEvery { flowRepository.create("PROCESSING", any()) } returns flowId
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns emptyList()

    val outcome = runBlocking {
      persister().persist(
        """{"title":"X","startsAt":"2026-10-02T18:30:00+03:00",
            "verdict":{"status":"REJECTED","reasons":["PAID"]}}""",
      )
    }

    assertThat(outcome).isInstanceOf(PersistOutcome.Skipped::class.java)
    assertThat((outcome as PersistOutcome.Skipped).reason).contains("REJECTED")
    coVerify { flowRepository.updateStatus(flowId, "REJECTED", null) }
    coVerify(exactly = 0) { eventRepository.insert(any()) }
  }

  @Test
  fun `duplicate above threshold is recorded even with NEEDS_REVIEW verdict`() {
    val flowId = UUID.randomUUID()
    val existingId = UUID.randomUUID()
    coEvery { flowRepository.create("PROCESSING", any()) } returns flowId
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns listOf(
      DuplicateCandidate(existingId, "PiterJS #61 (повтор)", null, null, 0.97),
    )

    val outcome = runBlocking {
      persister().persist(
        """{"title":"PiterJS #61","startsAt":"2026-10-02T18:30:00+03:00",
            "verdict":{"status":"NEEDS_REVIEW","reasons":["POSSIBLE_DUPLICATE"]}}""",
      )
    }

    // повторный анонс с NEEDS_REVIEW от тул-чека: связь duplicates пишется и без APPROVED
    assertThat(outcome).isInstanceOf(PersistOutcome.Duplicate::class.java)
    coVerify { flowRepository.insertDuplicate(flowId, existingId, 0.97, "AGENT") }
    coVerify { flowRepository.updateStatus(flowId, "DUPLICATE", null) }
    coVerify(exactly = 0) { eventRepository.insert(any()) }
  }

  @Test
  fun `approved without startsAt is skipped`() {
    val outcome = runBlocking {
      persister().persist("""{"title":"Митап","verdict":{"status":"APPROVED"}}""")
    }

    assertThat(outcome).isInstanceOf(PersistOutcome.Skipped::class.java)
    assertThat((outcome as PersistOutcome.Skipped).reason).contains("startsAt")
    coVerify(exactly = 0) { eventRepository.insert(any()) }
  }

  @Test
  fun `approved event without duplicates is saved with all best-effort fields`() {
    val flowId = UUID.randomUUID()
    coEvery { flowRepository.create("PROCESSING", any()) } returns flowId
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns emptyList()

    val outcome = runBlocking {
      persister().persist(
        """{"title":"PiterJS #61","description":"митап","organizer":"PiterJS","city":"Санкт-Петербург",
            "isFree":true,"formats":["OFFLINE"],"venueName":"Севкабель Порт",
            "startsAt":"2026-10-02T18:30:00+03:00","endsAt":"2026-10-02T21:30:00+03:00",
            "registrationUrl":"https://piterjs.org","sourceUrls":["https://t.me/piterjs"],
            "language":"RU","confidence":0.82,
            "verdict":{"status":"APPROVED","reasons":[]}}""",
      )
    }

    assertThat(outcome).isInstanceOf(PersistOutcome.Saved::class.java)
    val rowSlot = slot<EventRow>()
    coVerify { eventRepository.insert(capture(rowSlot)) }
    coVerify { flowRepository.updateStatus(flowId, "COMPLETED", null) }
    val row = rowSlot.captured
    assertThat(row.flowId).isEqualTo(flowId)
    assertThat(row.title).isEqualTo("PiterJS #61")
    assertThat(row.startsAt).isEqualTo(Instant.parse("2026-10-02T15:30:00Z"))
    assertThat(row.endsAt).isEqualTo(Instant.parse("2026-10-02T18:30:00Z"))
    assertThat(row.isFree).isTrue()
    assertThat(row.formats).containsExactly("OFFLINE")
    assertThat(row.venueName).isEqualTo("Севкабель Порт")
    assertThat(row.embedding).isNotNull
    assertThat(row.embedding!!.size).isEqualTo(768)
    // raw хранит исходный JSON агента
    assertThat(row.raw!!.path("title").asText()).isEqualTo("PiterJS #61")
  }

  @Test
  fun `candidate above threshold becomes duplicate with link`() {
    val flowId = UUID.randomUUID()
    val existingId = UUID.randomUUID()
    coEvery { flowRepository.create("PROCESSING", any()) } returns flowId
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns listOf(
      DuplicateCandidate(existingId, "PiterJS #61 (повтор)", Instant.parse("2026-10-02T15:30:00Z"), "PiterJS", 0.94),
    )

    val outcome = runBlocking {
      persister().persist(approvedJson())
    }

    assertThat(outcome).isInstanceOf(PersistOutcome.Duplicate::class.java)
    val duplicate = outcome as PersistOutcome.Duplicate
    assertThat(duplicate.existingEventId).isEqualTo(existingId)
    assertThat(duplicate.similarity).isEqualTo(0.94)
    coVerify { flowRepository.insertDuplicate(flowId, existingId, 0.94, "AGENT") }
    coVerify { flowRepository.updateStatus(flowId, "DUPLICATE", null) }
    coVerify(exactly = 0) { eventRepository.insert(any()) }
  }

  @Test
  fun `candidate in grey zone below threshold is saved as new event`() {
    coEvery { flowRepository.create("PROCESSING", any()) } returns UUID.randomUUID()
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns listOf(
      DuplicateCandidate(UUID.randomUUID(), "Похожий, но другой", null, null, 0.87),
    )

    val outcome = runBlocking { persister().persist(approvedJson()) }

    assertThat(outcome).isInstanceOf(PersistOutcome.Saved::class.java)
    coVerify(exactly = 1) { eventRepository.insert(any()) }
  }

  @Test
  fun `embedding outage skips with WAITING_RETRY`() {
    val brokenEmbedder: EmbeddingClient = mockk()
    coEvery { brokenEmbedder.embed(any()) } throws EmbeddingException(
      LlmException.Category.RETRYABLE, "provider down",
    )
    val flowId = UUID.randomUUID()
    coEvery { flowRepository.create("PROCESSING", any()) } returns flowId

    val outcome = runBlocking { persister(brokenEmbedder).persist(approvedJson()) }

    assertThat(outcome).isInstanceOf(PersistOutcome.Skipped::class.java)
    assertThat((outcome as PersistOutcome.Skipped).reason).contains("эмбеддинг")
    coVerify { flowRepository.updateStatus(flowId, "WAITING_RETRY", any()) }
    coVerify(exactly = 0) { eventRepository.insert(any()) }
  }

  private fun approvedJson() =
    """{"title":"PiterJS #61","organizer":"PiterJS","venueName":"Севкабель Порт",
        "startsAt":"2026-10-02T18:30:00+03:00",
        "verdict":{"status":"APPROVED","reasons":[]}}"""
}
