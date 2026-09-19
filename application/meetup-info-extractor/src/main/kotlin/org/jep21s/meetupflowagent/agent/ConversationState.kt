package org.jep21s.meetupflowagent.agent

import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.ChatRole

/**
 * Контекст цикла (ДЗ3): вся история сессии — system + user + assistant(tool_calls) +
 * tool-наблюдения. Передаётся в `messages` на каждом вызове LLM, чтобы модель видела
 * весь ход флоу, а не только последнее сообщение.
 */
class ConversationState(initial: List<ChatMessage> = emptyList()) {

  private val messages = initial.toMutableList()

  fun add(message: ChatMessage) {
    messages += message
  }

  /** Копия истории для запроса. */
  fun snapshot(): List<ChatMessage> = messages.toList()

  fun lastAssistantContent(): String? =
    messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.content
}
