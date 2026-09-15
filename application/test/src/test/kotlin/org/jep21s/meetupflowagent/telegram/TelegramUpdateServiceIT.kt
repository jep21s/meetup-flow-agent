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
import org.jep21s.meetupflowagent.flow.AgentFlowService
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.jep21s.meetupflowagent.telegram.db.TelegramQuestionRepository
import org.jep21s.meetupflowagent.telegram.integration.TelegramProxyClient
import org.jep21s.meetupflowagent.telegram.integration.TgButton
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jep21s.meetupflowagent.testsupport.PostgresTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import java.util.UUID
import kotlin.uuid.toJavaUuid

/**
 * Мозг входящего потока: сырой JSON апдейта → решение. Реальная БД
 * (PostgresTestBase: inbox/flows/human_requests/telegram_questions), прокси и
 * флоу-движок — mockk. Парсинг DTO Update — тот же JSON, что шлёт telegram-proxy.
 */
class TelegramUpdateServiceIT : PostgresTestBase() {

  private val proxyClient = mockk<TelegramProxyClient>(relaxed = true)
  private val flowService = mockk<AgentFlowService>(relaxed = true)
  private val questionRepository = TelegramQuestionRepository(testConnectivity())
  private val inboxRepository = InboxRepository(testConnectivity())
  private val flowRepository = FlowRepository(testConnectivity())
  private val humanRequestRepository = HumanRequestRepository(testConnectivity())

  init {
    // источник passthrough: группа 100, топик 7 (override до конструирования сервиса)
    System.setProperty("telegram.source.chat-id", "100")
    System.setProperty("telegram.source.topic-id", "7")
  }

  @AfterEach
  fun clearSourceFilterOverrides() {
    System.clearProperty("telegram.source.chat-id")
    System.clearProperty("telegram.source.topic-id")
  }

  private fun service() = TelegramUpdateService(
    inboxRepository = inboxRepository,
    flowRepository = flowRepository,
    humanRequestRepository = humanRequestRepository,
    questionRepository = questionRepository,
    proxyClient = proxyClient,
    flowService = flowService,
    scope = CoroutineScope(Dispatchers.Unconfined),
  )

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

    val question = Message().apply {
      messageId = 55
      date = 1_700_000_000 // реальный callback всегда с date — без него message десериализуется как недоступный
      chat = Chat().apply { id = 100L; type = "private" }
    }
    val update = Update().apply {
      updateId = 20
      callbackQuery = CallbackQuery().apply {
        id = "cb-1"
        data = "hitl:$flowId:1"
        from = User().apply { id = 9L; userName = "anna" }
        message = question
      }
    }
    val json = jacksonMapper.writeValueAsString(update)
    println("DEBUGJSON $json")
    // roundtrip: main обязан распарсить JSON прокси, включая message callback'а
    val parsed = jacksonMapper.readValue(json, Update::class.java)
    assertThat(parsed.callbackQuery?.message).isInstanceOf(Message::class.java)

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
  fun `reply to asked question submits free-text answer`() = runTest {
    val flowId = flowRepository.create("WAITING_HUMAN")
    humanRequestRepository.create(flowId, jacksonMapper.readTree("{\"question\":\"Идём?\",\"options\":[\"да\",\"нет\"]}"))
    questionRepository.register(flowId, 100L, 55L, listOf("да", "нет"))

    val svc = service()

    svc.handle(textUpdateJson(updateId = 21, text = "давай в среду", threadId = null, replyToMessageId = 55))

    coVerify { flowService.resume(flowId, "давай в среду") }
    assertThat(questionRepository.findAsked(100L, 55L)).isNull()
  }

  @Test
  fun `notifier binds proxy notification to question rows`() = runTest {
    // здесь заодно проверяем связку TelegramNotifier (ProxyNotifier для extractor'а)
    coEvery { proxyClient.configured } returns true
    val buttonsSlot = slot<List<TgButton>>()
    coEvery { proxyClient.sendQuestion(11L, "Какую дату?", capture(buttonsSlot)) } returns 77L
    val notifier = TelegramNotifier(proxyClient, questionRepository)

    notifier.notify(
      org.jep21s.meetupflowagent.notify.ProxyNotification(
        flowId = UUID.randomUUID(),
        event = "HUMAN_INPUT_REQUIRED",
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
}
