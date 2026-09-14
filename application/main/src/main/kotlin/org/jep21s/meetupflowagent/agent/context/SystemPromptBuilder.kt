package org.jep21s.meetupflowagent.agent.context

import org.koin.core.annotation.Singleton

/**
 * Единственное место сборки системного промпта (ДЗ3): база prompts/extractor-system.md
 * + блок «Примеры» из [ContextProvider].
 */
@Singleton
class SystemPromptBuilder(private val contextProvider: ContextProvider) {

  fun build(): String =
    basePrompt().trimEnd() + "\n\n# Примеры\n\n" + contextProvider.systemContext().trim()

  private fun basePrompt(): String =
    javaClass.classLoader.getResourceAsStream(BASE_PROMPT_RESOURCE)
      ?.bufferedReader()?.readText()
      ?: throw IllegalStateException("System prompt resource not found: $BASE_PROMPT_RESOURCE")

  companion object {
    const val BASE_PROMPT_RESOURCE = "prompts/extractor-system.md"
  }
}
