package org.jep21s.meetupflowagent.telegramproxy.telegram

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.telegram.telegrambots.meta.api.objects.Update

/**
 * Мост из блокирующего колбэка telegrambots (onUpdateReceived) в корутины:
 * DROP_OLDEST при переполнении — медленная обработка не блокирует поллер.
 */
object UpdateEventRelay {
  private val updateEventFlow = MutableSharedFlow<Update>(
    replay = 0,
    extraBufferCapacity = 128,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  fun accept(update: Update) {
    updateEventFlow.tryEmit(update)
  }

  fun getUpdateEventFlow() = updateEventFlow.asSharedFlow()
}
