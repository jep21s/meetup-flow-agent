package org.jep21s.meetupflowagent.db

import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt

/**
 * Интеграционный тест репозитория событий на реальном Postgres+pgvector:
 * round-trip всех полей (включая vector(768) и jsonb) и гибридный поиск
 * searchSimilar (порядок по близости + WHERE-фильтры даты/организатора).
 */
class EventRepositoryTest : PostgresTestBase() {

  private val repository = EventRepository(testConnectivity())

  private val baseDate: Instant = Instant.parse("2026-10-02T15:30:00Z")

  @Test
  fun `insert and select round-trip all fields`() {
    val embedding = unitVector(seed = 42)
    val row = EventRow(
      title = "PiterJS #61",
      description = "Осенний митап о фронтенде",
      organizer = "PiterJS",
      city = "Санкт-Петербург",
      isFree = true,
      price = null,
      formats = listOf("OFFLINE", "ONLINE"),
      address = "Кожевенная линия, 40",
      venueName = "Севкабель Порт",
      startsAt = baseDate,
      endsAt = baseDate.plusSeconds(3 * 3600),
      talks = jacksonObjectMapperReadTree("""[{"title":"WebGPU","speaker":"Иван"}]"""),
      registrationUrl = "https://piterjs.org/register",
      sourceUrls = jacksonObjectMapperReadTree("""["https://t.me/piterjs"]"""),
      language = "RU",
      confidence = 0.87,
      raw = jacksonObjectMapperReadTree("""{"title":"PiterJS #61"}"""),
      embedding = embedding,
    )

    val id = kotlinx.coroutines.runBlocking { repository.insert(row) }

    val loaded = kotlinx.coroutines.runBlocking { repository.findById(id) }
    assertThat(loaded).isNotNull
    loaded!!
    assertThat(loaded.title).isEqualTo(row.title)
    assertThat(loaded.organizer).isEqualTo("PiterJS")
    assertThat(loaded.city).isEqualTo("Санкт-Петербург")
    assertThat(loaded.isFree).isTrue()
    assertThat(loaded.formats).containsExactly("OFFLINE", "ONLINE")
    assertThat(loaded.venueName).isEqualTo("Севкабель Порт")
    assertThat(loaded.startsAt).isEqualTo(baseDate)
    assertThat(loaded.endsAt).isEqualTo(row.endsAt)
    assertThat(loaded.registrationUrl).isEqualTo(row.registrationUrl)
    assertThat(loaded.confidence).isEqualTo(0.87)
    assertThat(loaded.talks!!.first().path("title").asText()).isEqualTo("WebGPU")
    assertThat(loaded.sourceUrls!!.first().asText()).isEqualTo("https://t.me/piterjs")
    assertThat(loaded.embedding).isNotNull
    assertThat(loaded.embedding!!.size).isEqualTo(768)
    // вектор туда-обратно с точностью float
    assertThat(loaded.embedding!!.zip(embedding.toTypedArray()).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6f }).isTrue()
  }

  @Test
  fun `searchSimilar orders by cosine similarity descending`() {
    val query = unitVector(seed = 1)
    insertEvent("Близкий", embedding = perturb(query, 0.05f, 100), startsAt = baseDate)
    insertEvent("Средний", embedding = perturb(query, 0.7f, 200), startsAt = baseDate)
    insertEvent("Далёкий", embedding = perturb(query, 3.0f, 300), startsAt = baseDate)

    val found = kotlinx.coroutines.runBlocking {
      repository.searchSimilar(embedding = query, dateFrom = baseDate.minusSeconds(3 * 86400), dateTo = baseDate.plusSeconds(3 * 86400))
    }

    assertThat(found.map { it.title }).containsExactly("Близкий", "Средний", "Далёкий")
    assertThat(found[0].similarity).isGreaterThan(found[1].similarity)
    assertThat(found[1].similarity).isGreaterThan(found[2].similarity)
    assertThat(found[0].similarity).isGreaterThan(0.9)
  }

  @Test
  fun `searchSimilar date window excludes far events`() {
    val query = unitVector(seed = 2)
    insertEvent("В окне", embedding = perturb(query, 0.05f, 400), startsAt = baseDate)
    insertEvent("Вне окна", embedding = perturb(query, 0.05f, 500), startsAt = baseDate.plusSeconds(30L * 86400))

    val found = kotlinx.coroutines.runBlocking {
      repository.searchSimilar(embedding = query, dateFrom = baseDate.minusSeconds(3 * 86400), dateTo = baseDate.plusSeconds(3 * 86400))
    }

    assertThat(found.map { it.title }).containsExactly("В окне")
  }

  @Test
  fun `searchSimilar organizer filter narrows results`() {
    val query = unitVector(seed = 3)
    insertEvent("PiterJS митап", embedding = perturb(query, 0.05f, 600), startsAt = baseDate, organizer = "PiterJS")
    insertEvent("Тот же вектор другой организатор", embedding = perturb(query, 0.05f, 600), startsAt = baseDate, organizer = "SPb IT")

    val found = kotlinx.coroutines.runBlocking {
      repository.searchSimilar(
        embedding = query,
        dateFrom = baseDate.minusSeconds(3 * 86400),
        dateTo = baseDate.plusSeconds(3 * 86400),
        organizer = "SPb IT",
      )
    }

    assertThat(found).hasSize(1)
    assertThat(found[0].organizer).isEqualTo("SPb IT")
  }

  @Test
  fun `searchSimilar ignores events without embedding`() {
    insertEvent("Без эмбеддинга", embedding = null, startsAt = baseDate)
    val found = kotlinx.coroutines.runBlocking {
      repository.searchSimilar(embedding = unitVector(seed = 4), dateFrom = baseDate.minusSeconds(86400), dateTo = baseDate.plusSeconds(86400))
    }
    assertThat(found).isEmpty()
  }

  private fun insertEvent(title: String, embedding: FloatArray?, startsAt: Instant, organizer: String? = null): UUID =
    kotlinx.coroutines.runBlocking {
      repository.insert(
        EventRow(
          title = title,
          organizer = organizer,
          startsAt = startsAt,
          embedding = embedding,
        ),
      )
    }

  private fun jacksonObjectMapperReadTree(json: String) =
    org.jep21s.meetupflowagent.starter.jackson.jacksonMapper.readTree(json)

  /** Детерминированный единичный 768-мерный вектор (LCG). */
  private fun unitVector(seed: Long): FloatArray {
    var state = seed
    val v = FloatArray(EMBEDDING_DIM) {
      state = state * 6364136223846793005L + 1442695040888963407L
      ((state ushr 33) % 2001 - 1000).toFloat() / 1000f
    }
    val norm = sqrt(v.map { it.toDouble() * it.toDouble() }.sum()).toFloat()
    for (i in v.indices) v[i] /= norm
    return v
  }

  /** Перпендикулярный шум: v + noise*k (в первом и ещё одной координате). */
  private fun perturb(base: FloatArray, k: Float, seed: Long): FloatArray {
    val noise = unitVector(seed)
    val result = base.copyOf()
    for (i in result.indices) result[i] += noise[i] * k
    val norm = sqrt(result.map { it.toDouble() * it.toDouble() }.sum()).toFloat()
    for (i in result.indices) result[i] /= norm
    return result
  }
}
