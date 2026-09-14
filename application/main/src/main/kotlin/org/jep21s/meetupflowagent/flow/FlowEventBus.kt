package org.jep21s.meetupflowagent.flow

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.annotation.Singleton

/** Живое событие флоу для SSE (§11): шаг/статус/финал/ожидание человека. */
data class FlowEvent(
  val flowId: UUID,
  val type: String,
  val seq: Int?,
  val payload: Any?,
)

/**
 * In-memory шина живых событий флоу (по flowId): replay-SSE берёт историю из
 * flow_steps, затем подписывается сюда. Держит последнего подписчика на поток
 * (SSE-соединение одно); события без подписчиков теряются — это ок: клиент
 * переподключится с Last-Event-ID и получит replay из БД.
 */
@Singleton
class FlowEventBus {

  private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<FlowEvent>>()

  fun flowFor(flowId: UUID): SharedFlow<FlowEvent> = flows.computeIfAbsent(flowId) {
    MutableSharedFlow(replay = 64, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  }

  fun publish(event: FlowEvent) {
    flows[event.flowId]?.tryEmit(event)
  }
}
