package org.jep21s.meetupflowagent.agent.context

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SystemPromptBuilderTest {

  @Test
  fun `context file exists in main resources (fail-fast)`() {
    // если файл удалён/переименован — FileContextProvider упадёт при старте приложения
    val resource = javaClass.classLoader.getResource(FileContextProvider.DEFAULT_RESOURCE)
    assertThat(resource).isNotNull()
  }

  @Test
  fun `build glues base prompt and examples block`() {
    val builder = SystemPromptBuilder(FakeContext("ПРИМЕР-МАРКЕР"))

    val prompt = builder.build()

    assertThat(prompt).startsWith("Ты — агент")
    assertThat(prompt).contains("# Правила отбора")
    assertThat(prompt).contains("# Формат ответа")
    assertThat(prompt).contains("\n# Примеры\n")
    assertThat(prompt).endsWith("ПРИМЕР-МАРКЕР")
  }

  @Test
  fun `build with real file context keeps few-shot sections`() {
    val builder = SystemPromptBuilder(FileContextProvider())

    val prompt = builder.build()

    assertThat(prompt).contains("Пример 1 — полное сообщение")
    assertThat(prompt).contains("Пример 2 — сообщение только со ссылкой")
    assertThat(prompt).contains("Пример 3 — платное мероприятие")
    assertThat(prompt).contains("Пример 4 — город не назван")
    // базовая часть идёт раньше блока примеров
    assertThat(prompt.indexOf("# Правила отбора")).isLessThan(prompt.indexOf("# Примеры"))
  }

  private class FakeContext(private val text: String) : ContextProvider {
    override fun systemContext() = text
  }
}
