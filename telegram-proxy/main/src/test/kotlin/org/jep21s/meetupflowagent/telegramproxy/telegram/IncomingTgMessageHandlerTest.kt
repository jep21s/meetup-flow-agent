package org.jep21s.meetupflowagent.telegramproxy.telegram

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.telegramproxy.integration.AgentCallResult
import org.jep21s.meetupflowagent.telegramproxy.integration.MeetupFlowAgent
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import java.util.UUID

/**
 * Входящие апдейты: сырой passthrough всей DTO в агента, кнопки/reply на
 * HITL-вопрос → POST /responses, команды бота агенту не пересылаются.
 * Бот не поднимается вовсе (telegram.bot.enabled=false по умолчанию) —
 * handler тестируется напрямую.
 */
class IncomingTgMessageHandlerTest {

  private val agent = mockk<MeetupFlowAgent>()
  private val sender = mockk<TgMessageSender>(relaxed = true)
  private val pendingStore = HitlPendingStore(CoroutineScope(Dispatchers.Unconfined))
  private val handler = IncomingTgMessageHandler(agent, sender, pendingStore, CoroutineScope(Dispatchers.Unconfined))

  private fun chat(id: Long = 100L, title: String? = null) = Chat().apply {
    this.id = id
    type = "private"
    this.title = title
  }

  private fun user(id: Long = 7L, username: String? = "jep") = User().apply {
    this.id = id
    userName = username
  }

  private fun textUpdate(
    updateId: Int,
    text: String,
    replyTo: Message? = null,
    from: User = user(),
    chatId: Long = 100L,
    threadId: Int? = null,
  ): Update =
    Update().apply {
      this.updateId = updateId
      message = Message().apply {
        chat = Chat().apply {
          id = chatId
          type = "private"
        }
        this.from = from
        this.text = text
        date = 1_700_000_000
        replyToMessage = replyTo
        threadId?.let { messageThreadId = it }
      }
    }

  @Test
  fun `text message is forwarded as whole update dto with tg idempotency key`() = runTest {
    coEvery { agent.postMessage(any(), any(), any()) } returns AgentCallResult.Accepted

    handler.handle(textUpdate(updateId = 42, text = "митап в пятницу"))

    coVerify(exactly = 1) {
      agent.postMessage(
        idempotencyKey = "tg-42",
        text = match { json -> json.contains("\"update_id\":42") && json.contains("митап в пятницу") },
        meta = any(),
      )
    }
  }

  @Test
  fun `callback submits chosen option and removes pending for whole flow`() = runTest {
    coEvery { agent.submitHumanResponse(any(), any(), any()) } returns AgentCallResult.Accepted
    val flowId = UUID.randomUUID()
    pendingStore.register(100L, 55L, PendingQuestion(flowId, listOf("да", "нет")))
    val question = Message().apply {
      messageId = 55
      chat = this@IncomingTgMessageHandlerTest.chat()
    }
    val update = Update().apply {
      updateId = 43
      callbackQuery = CallbackQuery().apply {
        id = "cb-1"
        data = "hitl:$flowId:1"
        from = user(id = 9L)
        message = question
      }
    }

    handler.handle(update)

    coVerify(exactly = 1) { agent.submitHumanResponse(flowId, 9L, "нет") }
    coVerify { sender.editQuestionAnswered(100L, 55L) }
    coVerify { sender.answerCallback("cb-1", "Ответ принят") }
    assertThat(pendingStore.find(100L, 55L)).isNull()
  }

  @Test
  fun `reply to pending question submits free-text answer`() = runTest {
    coEvery { agent.submitHumanResponse(any(), any(), any()) } returns AgentCallResult.Accepted
    val flowId = UUID.randomUUID()
    pendingStore.register(100L, 55L, PendingQuestion(flowId, listOf("да", "нет")))
    val question = Message().apply {
      messageId = 55
      chat = this@IncomingTgMessageHandlerTest.chat()
    }

    handler.handle(textUpdate(updateId = 44, text = "давай в среду", replyTo = question))

    coVerify(exactly = 1) { agent.submitHumanResponse(flowId, 7L, "давай в среду") }
    coVerify { sender.sendText(100L, match { it.contains("Ответ принят") }) }
  }

  @Test
  fun `bot command start is greeted locally and not forwarded`() = runTest {
    handler.handle(textUpdate(updateId = 45, text = "/start"))

    coVerify(exactly = 0) { agent.postMessage(any(), any(), any()) }
    coVerify { sender.sendText(100L, match { it.contains("Привет") }) }
  }

  // фильтр источника: handler читает telegram.source.* при создании, поэтому
  // override задаётся ДО конструирования локального handler'а (ConfigLoader
  // проверяет System.getProperty первым — штатный механизм для тестов/ops)
  @org.junit.jupiter.api.AfterEach
  fun clearSourceFilterOverrides() {
    System.clearProperty("telegram.source.chat-id")
    System.clearProperty("telegram.source.topic-id")
  }

  @Test
  fun `source filter - message from configured chat and topic is forwarded`() = runTest {
    System.setProperty("telegram.source.chat-id", "100")
    System.setProperty("telegram.source.topic-id", "7")
    coEvery { agent.postMessage(any(), any(), any()) } returns AgentCallResult.Accepted
    val filtered = IncomingTgMessageHandler(agent, sender, pendingStore, CoroutineScope(Dispatchers.Unconfined))

    filtered.handle(textUpdate(updateId = 50, text = "митап", chatId = 100L, threadId = 7))

    coVerify(exactly = 1) { agent.postMessage(any(), any(), any()) }
  }

  @Test
  fun `source filter - message from another chat is ignored`() = runTest {
    System.setProperty("telegram.source.chat-id", "100")
    System.setProperty("telegram.source.topic-id", "7")
    val filtered = IncomingTgMessageHandler(agent, sender, pendingStore, CoroutineScope(Dispatchers.Unconfined))

    filtered.handle(textUpdate(updateId = 51, text = "спам из другого чата", chatId = 555L, threadId = 7))

    coVerify(exactly = 0) { agent.postMessage(any(), any(), any()) }
  }

  @Test
  fun `source filter - message from another topic of same chat is ignored`() = runTest {
    System.setProperty("telegram.source.chat-id", "100")
    System.setProperty("telegram.source.topic-id", "7")
    val filtered = IncomingTgMessageHandler(agent, sender, pendingStore, CoroutineScope(Dispatchers.Unconfined))

    filtered.handle(textUpdate(updateId = 52, text = "не тот топик", chatId = 100L, threadId = 9))

    coVerify(exactly = 0) { agent.postMessage(any(), any(), any()) }
  }
}
