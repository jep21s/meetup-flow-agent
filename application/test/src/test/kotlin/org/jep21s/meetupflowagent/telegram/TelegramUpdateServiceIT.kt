package org.jep21s.meetupflowagent.telegram

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.db.FlowRepository
import org.jep21s.meetupflowagent.db.HumanRequestRepository
import org.jep21s.meetupflowagent.db.InboxMessages
import org.jep21s.meetupflowagent.db.InboxRepository
import org.jep21s.meetupflowagent.db.Users
import org.jep21s.meetupflowagent.db.UsersRepository
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.notify.EVENT_HUMAN_INPUT_REQUIRED
import org.jep21s.meetupflowagent.notify.ProxyNotification
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.telegram.db.TelegramQuestionRepository
import org.jep21s.meetupflowagent.telegram.integration.TelegramProxyClient
import org.jep21s.meetupflowagent.telegram.integration.TgButton
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Мозг входящего потока: сырой JSON апдейта → решение. Реальная БД
 * (PostgresTestBase: inbox/flows/human_requests/telegram_questions/users),
 * прокси и флоу-движок — mockk. Парсинг DTO Update — тот же JSON, что шлёт
 * telegram-proxy.
 */
class TelegramUpdateServiceIT : PostgresTestBase() {

  private val proxyClient = mockk<TelegramProxyClient>(relaxed = true)
  private val flowService = mockk<AgentFlowService>(relaxed = true)
  private val questionRepository = TelegramQuestionRepository(testConnectivity())
  private val inboxRepository = InboxRepository(testConnectivity())
  private val flowRepository = FlowRepository(testConnectivity())
  private val humanRequestRepository = HumanRequestRepository(testConnectivity())
  private val usersRepository = UsersRepository(testConnectivity())

  init {
    // источник passthrough: группа 100, топик 7 (override до конструирования сервиса)
    System.setProperty("telegram.source.chat-id", "100")
    System.setProperty("telegram.source.topic-id", "7")
  }

  @AfterEach
  fun clearSourceFilterOverrides() {
    System.clearProperty("telegram.source.chat-id")
    System.clearProperty("telegram.source.topic-id")
    System.clearProperty("telegram.sources")
    System.clearProperty("telegram.main.chat-id")
  }

  private fun service() = TelegramUpdateService(
    inboxRepository = inboxRepository,
    flowRepository = flowRepository,
    humanRequestRepository = humanRequestRepository,
    questionRepository = questionRepository,
    usersRepository = usersRepository,
    proxyClient = proxyClient,
    flowService = flowService,
    scope = CoroutineScope(Dispatchers.Unconfined),
  )

  /** Активный пользователь в allowlist users (потенциальный автор HITL-ответа). */
  @OptIn(ExperimentalUuidApi::class)
  private fun seedActiveUser(userId: Long) {
    transaction(database) {
      Users.insert {
        it[id] = Uuid.random()
        it[telegramUserId] = userId
        it[isActive] = true
      }
    }
  }

  /** JSON апдейта-клика по кнопке вопроса (message 55 в чате 100, idempotent updateId). */
  private fun callbackUpdateJson(flowId: UUID, fromId: Long, optionIndex: Int): String {
    val update = Update().apply {
      updateId = 20
      callbackQuery = CallbackQuery().apply {
        id = "cb-1"
        data = "hitl:$flowId:$optionIndex"
        from = User().apply { id = fromId; userName = "кликнувший" }
        message = Message().apply {
          messageId = 55
          date = 1_700_000_000 // реальный callback всегда с date — без него message десериализуется как недоступный
          chat = Chat().apply { id = 100L; type = "private" }
        }
      }
    }
    return jacksonMapper.writeValueAsString(update)
  }

  private fun textUpdateJson(
    updateId: Int,
    text: String,
    chatId: Long = 100L,
    threadId: Int? = 7,
    replyToMessageId: Int? = null,
  ): String {
    val update = Update().apply {
      this.updateId = updateId
      message = Message().apply {
        chat = Chat().apply { id = chatId; type = "supergroup" }
        from = User().apply { id = 7L; userName = "jep" }
        this.text = text
        date = 1_700_000_000
        threadId?.let { messageThreadId = it }
        replyToMessageId?.let {
          replyToMessage = Message().apply {
            messageId = it
            chat = Chat().apply { id = chatId; type = "supergroup" }
            from = User().apply { id = 1L; isBot = true }
          }
        }
      }
    }
    return jacksonMapper.writeValueAsString(update)
  }

  /** Прямой запрос inbox по idempotency_key (findByIdempotencyKey репозитория приватный). */
  @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
  private suspend fun inboxByKey(key: String): Pair<String, UUID?>? = withContext(Dispatchers.IO) {
    suspendTransaction(database) {
      InboxMessages.selectAll().where { InboxMessages.idempotencyKey eq key }.singleOrNull()
        ?.let { it[InboxMessages.rawText] to it[InboxMessages.flowId]?.toJavaUuid() }
    }
  }

  @Test
  fun `message from source chat and topic becomes extraction flow`() = runTest {
    val svc = service()

    svc.handle(textUpdateJson(updateId = 10, text = "митап в пятницу"))

    val inserted = inboxByKey("tg-10")
    assertThat(inserted).isNotNull
    assertThat(inserted!!.first).contains("\"update_id\":10")
    assertThat(inserted.second).isNotNull
  }

  @Test
  fun `duplicate updateId is idempotent`() = runTest {
    val svc = service()

    svc.handle(textUpdateJson(updateId = 11, text = "митап"))
    svc.handle(textUpdateJson(updateId = 11, text = "митап"))

    // второй прогон не создал новый inbox — flowId на месте
    val rows = inboxByKey("tg-11")
    assertThat(rows).isNotNull
    assertThat(rows!!.second).isNotNull
  }

  @Test
  fun `message outside source chat ignored - no flow`() = runTest {
    val svc = service()

    svc.handle(textUpdateJson(updateId = 12, text = "спам", chatId = 555L))

    assertThat(inboxByKey("tg-12")).isNull()
  }

  @Test
  fun `message from another topic of same chat ignored`() = runTest {
    val svc = service()

    svc.handle(textUpdateJson(updateId = 13, text = "не тот топик", threadId = 9))

    assertThat(inboxByKey("tg-13")).isNull()
  }

  @Test
  fun `message from extra source chat without topic becomes flow`() = runTest {
    // telegram.sources складывается с парой chat-id/topic-id (100/7)
    System.setProperty("telegram.sources", "300")
    val svc = service()

    svc.handle(textUpdateJson(updateId = 30, text = "форвард старого анонса", chatId = 300L, threadId = null))

    val inserted = inboxByKey("tg-30")
    assertThat(inserted).isNotNull
    assertThat(inserted!!.first).contains("\"update_id\":30")
  }

  @Test
  fun `extra source with topic accepts only that topic`() = runTest {
    System.setProperty("telegram.sources", "300:5")
    val svc = service()

    svc.handle(textUpdateJson(updateId = 31, text = "не тот топик", chatId = 300L, threadId = 9))
    svc.handle(textUpdateJson(updateId = 32, text = "тот топик", chatId = 300L, threadId = 5))

    assertThat(inboxByKey("tg-31")).isNull()
    assertThat(inboxByKey("tg-32")).isNotNull
  }

  @Test
  fun `malformed sources entry skipped - valid one works`() = runTest {
    System.setProperty("telegram.sources", "abc,300")
    val svc = service()

    svc.handle(textUpdateJson(updateId = 33, text = "привет", chatId = 300L, threadId = null))

    assertThat(inboxByKey("tg-33")).isNotNull
  }

  @Test
  fun `start command greets and does not create flow`() = runTest {
    val svc = service()
    coEvery { proxyClient.sendText(any(), any()) } returns 1L

    svc.handle(textUpdateJson(updateId = 14, text = "/start", threadId = null))

    coVerify { proxyClient.sendText(100L, match { it.contains("Привет") }) }
    assertThat(inboxByKey("tg-14")).isNull()
  }

  @Test
  fun `callback submits option and resolves question everywhere`() = runTest {
    val flowId = flowRepository.create("WAITING_HUMAN")
    // флоу ждёт ответа: PENDING-вопрос в human_requests + заданные сообщения
    humanRequestRepository.create(flowId, jacksonMapper.readTree("{\"question\":\"Идём?\",\"options\":[\"да\",\"нет\"]}"))
    questionRepository.register(flowId, 100L, 55L, listOf("да", "нет"))
    questionRepository.register(flowId, 200L, 66L, listOf("да", "нет"))
    coEvery { proxyClient.answerCallback(any(), any()) } returns Unit
    coEvery { proxyClient.removeKeyboard(any(), any()) } returns Unit
    seedActiveUser(9L)

    val json = callbackUpdateJson(flowId, fromId = 9L, optionIndex = 1)
    // roundtrip: main обязан распарсить JSON прокси, включая message callback'а
    assertThat(jacksonMapper.readValue(json, Update::class.java).callbackQuery?.message).isInstanceOf(Message::class.java)

    val svc = service()

    svc.handle(json)

    // опция №1 ушла как ответ человека; флоу-резюм запущен
    coVerify { flowService.resume(flowId, "нет") }
    // вопрос закрыт у всех адресатов, кнопки сняты
    assertThat(questionRepository.findAsked(100L, 55L)).isNull()
    assertThat(questionRepository.findAsked(200L, 66L)).isNull()
    coVerify { proxyClient.removeKeyboard(100L, 55L) }
    coVerify { proxyClient.removeKeyboard(200L, 66L) }
    coVerify { proxyClient.answerCallback("cb-1", "Ответ принят") }
  }

  @Test
  fun `callback from user outside allowlist rejected - question stays asked`() = runTest {
    val flowId = flowRepository.create("WAITING_HUMAN")
    humanRequestRepository.create(flowId, jacksonMapper.readTree("{\"question\":\"Идём?\",\"options\":[\"да\",\"нет\"]}"))
    questionRepository.register(flowId, 100L, 55L, listOf("да", "нет"))
    coEvery { proxyClient.answerCallback(any(), any()) } returns Unit
    // пользователя 9 в allowlist users нет

    service().handle(callbackUpdateJson(flowId, fromId = 9L, optionIndex = 1))

    coVerify(exactly = 0) { flowService.resume(any(), any()) }
    // вопрос не закрыт и кнопки не сняты — адресаты всё ещё могут ответить
    assertThat(questionRepository.findAsked(100L, 55L)).isNotNull
    coVerify(exactly = 0) { proxyClient.removeKeyboard(any(), any()) }
    coVerify { proxyClient.answerCallback("cb-1", "⛔ Не авторизован") }
  }

  @Test
  fun `reply to asked question submits free-text answer`() = runTest {
    val flowId = flowRepository.create("WAITING_HUMAN")
    humanRequestRepository.create(flowId, jacksonMapper.readTree("{\"question\":\"Идём?\",\"options\":[\"да\",\"нет\"]}"))
    questionRepository.register(flowId, 100L, 55L, listOf("да", "нет"))
    seedActiveUser(7L) // from.id текстового апдейта

    val svc = service()

    svc.handle(textUpdateJson(updateId = 21, text = "давай в среду", threadId = null, replyToMessageId = 55))

    coVerify { flowService.resume(flowId, "давай в среду") }
    assertThat(questionRepository.findAsked(100L, 55L)).isNull()
  }

  @Test
  fun `reply from user outside allowlist rejected - question stays asked`() = runTest {
    val flowId = flowRepository.create("WAITING_HUMAN")
    humanRequestRepository.create(flowId, jacksonMapper.readTree("{\"question\":\"Идём?\",\"options\":[\"да\",\"нет\"]}"))
    questionRepository.register(flowId, 100L, 55L, listOf("да", "нет"))
    // from.id=7 в allowlist users не сеем

    service().handle(textUpdateJson(updateId = 22, text = "посторонний совет", threadId = null, replyToMessageId = 55))

    coVerify(exactly = 0) { flowService.resume(any(), any()) }
    assertThat(questionRepository.findAsked(100L, 55L)).isNotNull
    coVerify { proxyClient.sendText(100L, match { it.contains("Не авторизован") }) }
  }

  @Test
  fun `notifier binds proxy notification to question rows`() = runTest {
    // здесь заодно проверяем связку TelegramNotifier (ProxyNotifier для extractor'а)
    coEvery { proxyClient.configured } returns true
    val buttonsSlot = slot<List<TgButton>>()
    coEvery { proxyClient.sendQuestion(11L, "Какую дату?", capture(buttonsSlot)) } returns 77L
    val notifier = TelegramNotifier(proxyClient, questionRepository)

    notifier.notify(
      ProxyNotification(
        flowId = UUID.randomUUID(),
        event = EVENT_HUMAN_INPUT_REQUIRED,
        userIds = listOf(11L),
        text = "Какую дату?",
        options = listOf("пятница", "суббота"),
      )
    )

    val buttons = buttonsSlot.captured
    assertThat(buttons).hasSize(2)
    assertThat(buttons[0].callbackData).startsWith("hitl:")
    val asked = questionRepository.findAsked(11L, 77L)
    assertThat(asked).isNotNull
    assertThat(asked!!.options).containsExactly("пятница", "суббота")
  }

  @Test
  fun `hitl without trusted recipients is not sent to main chat`() = runTest {
    System.setProperty("telegram.main.chat-id", "300")
    coEvery { proxyClient.configured } returns true

    TelegramNotifier(proxyClient, questionRepository).notify(
      ProxyNotification(
        flowId = UUID.randomUUID(),
        event = EVENT_HUMAN_INPUT_REQUIRED,
        userIds = emptyList(),
        text = "Идём?",
        options = listOf("да", "нет"),
      )
    )

    coVerify(exactly = 0) { proxyClient.sendQuestion(any(), any(), any()) }
    coVerify(exactly = 0) { proxyClient.sendText(any(), any()) }
  }

  @Test
  fun `system event with empty userIds still falls back to main chat`() = runTest {
    System.setProperty("telegram.main.chat-id", "300")
    coEvery { proxyClient.configured } returns true

    TelegramNotifier(proxyClient, questionRepository).notify(
      ProxyNotification(
        flowId = UUID.randomUUID(),
        event = "FLOW_FAILED",
        userIds = emptyList(),
        text = "флоу провален",
      )
    )

    coVerify { proxyClient.sendText(300L, "флоу провален") }
  }
}
