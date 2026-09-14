package org.jep21s.meetupflowagent.agent.tools

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.db.DuplicateCandidate
import org.jep21s.meetupflowagent.db.EventRepository
import org.jep21s.meetupflowagent.llm.EmbeddingClient
import org.jep21s.meetupflowagent.llm.EmbeddingException
import org.jep21s.meetupflowagent.llm.LlmException
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Юнит-тесты тула search_duplicate: моки эмбеддера и репозитория (ноль внешних вызовов). */
class SearchDuplicateToolTest {

  private val embeddingClient: EmbeddingClient = mockk()
  private val eventRepository: EventRepository = mockk()
  private val tool = SearchDuplicateTool(embeddingClient, eventRepository)

  private val vector = FloatArray(768) { 0.01f }

  @Test
  fun `returns candidates json when found`() {
    coEvery { embeddingClient.embed(any()) } returns vector
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns listOf(
      DuplicateCandidate(UUID.randomUUID(), "PiterJS #61", Instant.parse("2026-10-02T15:30:00Z"), "PiterJS", 0.95),
    )

    val result = runBlocking {
      tool.execute(jacksonMapper.readTree("""{"query":"PiterJS | 2026-10-02","eventDate":"2026-10-02"}"""))
    }

    assertThat(result).isInstanceOf(ToolResult.Success::class.java)
    val payload = jacksonMapper.readTree((result as ToolResult.Success).text)
    assertThat(payload.path("candidates").first().path("title").asText()).isEqualTo("PiterJS #61")
    assertThat(payload.path("candidates").first().path("similarity").asDouble()).isEqualTo(0.95)
    assertThat(payload.path("hint").asText()).contains("0.92")
  }

  @Test
  fun `empty result reports no duplicates`() {
    coEvery { embeddingClient.embed(any()) } returns vector
    coEvery { eventRepository.searchSimilar(any(), any(), any(), any(), any()) } returns emptyList()

    val result = runBlocking {
      tool.execute(jacksonMapper.readTree("""{"query":"новый митап","eventDate":"2026-10-02"}"""))
    }

    assertThat(result).isInstanceOf(ToolResult.Success::class.java)
    assertThat((result as ToolResult.Success).text).contains("не найдено")
  }

  @Test
  fun `missing query is INVALID_ARGS`() {
    val result = runBlocking { tool.execute(jacksonMapper.readTree("""{"eventDate":"2026-10-02"}""")) }
    assertThat(result).isInstanceOf(ToolResult.Error::class.java)
    assertThat((result as ToolResult.Error).code).isEqualTo("INVALID_ARGS")
  }

  @Test
  fun `bad eventDate format is INVALID_ARGS`() {
    val result = runBlocking {
      tool.execute(jacksonMapper.readTree("""{"query":"митап","eventDate":"02.10.2026"}"""))
    }
    assertThat(result).isInstanceOf(ToolResult.Error::class.java)
    assertThat((result as ToolResult.Error).code).isEqualTo("INVALID_ARGS")
  }

  @Test
  fun `embedding failure becomes tool error, not exception`() {
    coEvery { embeddingClient.embed(any()) } throws EmbeddingException(
      LlmException.Category.RETRYABLE, "provider unavailable",
    )

    val result = runBlocking { tool.execute(jacksonMapper.readTree("""{"query":"митап"}""")) }

    assertThat(result).isInstanceOf(ToolResult.Error::class.java)
    assertThat((result as ToolResult.Error).code).isEqualTo("EMBEDDING_RETRYABLE")
  }

  @Test
  fun `date window and organizer filter are passed to repository`() {
    coEvery { embeddingClient.embed(any()) } returns vector
    val dateFromSlot = slot<Instant?>()
    val dateToSlot = slot<Instant?>()
    val organizerSlot = slot<String?>()
    coEvery {
      eventRepository.searchSimilar(any(), captureNullable(dateFromSlot), captureNullable(dateToSlot), captureNullable(organizerSlot), any())
    } returns emptyList()

    runBlocking {
      tool.execute(jacksonMapper.readTree("""{"query":"митап","eventDate":"2026-10-02","organizer":"PiterJS"}"""))
    }

    // окно ±3 дн. от 2026-10-02 (UTC): [2026-09-29T00:00Z, 2026-10-06T00:00Z)
    assertThat(dateFromSlot.captured).isEqualTo(Instant.parse("2026-09-29T00:00:00Z"))
    assertThat(dateToSlot.captured).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"))
    assertThat(organizerSlot.captured).isEqualTo("PiterJS")
  }
}
