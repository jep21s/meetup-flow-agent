package org.jep21s.meetupflowagent.outbox

import com.fasterxml.jackson.databind.JsonNode
import org.jep21s.meetupflowagent.db.DestinationRow
import org.jep21s.meetupflowagent.db.EventRow
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import java.time.Instant
import java.util.UUID

/**
 * Канонический снапшот публикации (решение «канонический JSON»): в outbox храним
 * один формат, а текст под конкретный канал транспорт форматирует при доставке —
 * новый канал не требует менять схему, повторная доставка даёт свежий текст.
 */
data class OutboxPublicationPayload(
  val flowId: String,
  /** Ключ дедупликации прокси: доставка at-least-once, повторы возможны после краша. */
  val deliveryDedupKey: String,
  val event: PublishedEvent,
  val finishedAt: Instant,
)

/** Событие, прошедшее проверку (офлайн/СПб/бесплатно/не дубль) — то, что публикуем. */
data class PublishedEvent(
  val eventId: String,
  val title: String,
  val startsAt: Instant,
  val endsAt: Instant? = null,
  val venueName: String? = null,
  val address: String? = null,
  val city: String? = null,
  val registrationUrl: String? = null,
  val description: String? = null,
  val organizer: String? = null,
  val tags: List<String> = emptyList(),
  val isFree: Boolean? = null,
)

/** Единица работы транспорта: доставка публикации в одно назначение. */
data class OutboxDeliveryTask(
  val deliveryId: UUID,
  val messageId: UUID,
  val attempts: Int,
  val destination: DestinationRow,
  val payload: OutboxPublicationPayload,
)

/** Неудачная попытка доставки: поллер решает, ретраить или завершить перманентно. */
class OutboxDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Сборка payload из вставляемого события; messageId = ключ дедупликации. */
fun buildPublicationPayload(messageId: UUID, event: EventRow, finishedAt: Instant = Instant.now()): JsonNode =
  jacksonMapper.valueToTree(
    OutboxPublicationPayload(
      flowId = event.flowId.toString(),
      deliveryDedupKey = messageId.toString(),
      event = PublishedEvent(
        eventId = event.id.toString(),
        title = event.title,
        startsAt = event.startsAt,
        endsAt = event.endsAt,
        venueName = event.venueName,
        address = event.address,
        city = event.city,
        registrationUrl = event.registrationUrl,
        description = event.description,
        organizer = event.organizer,
        tags = event.formats,
        isFree = event.isFree,
      ),
      finishedAt = finishedAt,
    ),
  )

/** Обратный разбор payload из БД. */
fun parsePublicationPayload(payload: JsonNode): OutboxPublicationPayload =
  jacksonMapper.treeToValue(payload, OutboxPublicationPayload::class.java)
