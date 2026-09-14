package org.jep21s.meetupflowagent.testsupport

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.llm.dto.ChatCompletionRequest
import org.jep21s.meetupflowagent.llm.dto.ChatMessage
import org.jep21s.meetupflowagent.llm.dto.ChatRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeChatClientContractTest {

  @Test
  fun `serves scripted responses in order and records requests`() = runBlocking {
    val fake = FakeChatClient(
      FakeChatClient.text("первый"),
      FakeChatClient.text("второй"),
    )
    val request = ChatCompletionRequest(model = "m", messages = listOf(ChatMessage.user("u")))

    assertThat(fake.complete(request).firstMessage().content).isEqualTo("первый")
    assertThat(fake.complete(request).firstMessage().content).isEqualTo("второй")
    assertThat(fake.requests).hasSize(2)
    assertThat(fake.requests.first().model).isEqualTo("m")
  }

  @Test
  fun `script exhaustion fails loudly`() = runBlocking {
    val fake = FakeChatClient(FakeChatClient.text("единственный"))
    fake.complete(ChatCompletionRequest(model = "m", messages = listOf(ChatMessage.user("u"))))

    val ex = assertThrows<IllegalStateException> {
      fake.complete(ChatCompletionRequest(model = "m", messages = listOf(ChatMessage.user("u"))))
    }
    assertThat(ex.message).contains("script exhausted")
  }

  @Test
  fun `text and toolCall factories produce well-formed responses`() {
    val text = FakeChatClient.text("ок")
    assertThat(text.firstMessage().role).isEqualTo(ChatRole.ASSISTANT)
    assertThat(text.firstMessage().content).isEqualTo("ок")
    assertThat(text.usage).isNotNull

    val call = FakeChatClient.toolCall(name = "fetch_web_page", argumentsJson = "{}")
    val toolCalls = call.firstMessage().toolCalls
    assertThat(toolCalls).hasSize(1)
    assertThat(toolCalls!!.first().function.name).isEqualTo("fetch_web_page")
    assertThat(toolCalls.first().type).isEqualTo("function")
  }
}
