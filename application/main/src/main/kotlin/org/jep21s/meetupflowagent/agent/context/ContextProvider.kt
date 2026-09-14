package org.jep21s.meetupflowagent.agent.context

import org.koin.core.annotation.Singleton

/** Источник внешнего контекста для системного промпта (ДЗ3: подключение контекста). */
interface ContextProvider {
  fun systemContext(): String
}

/**
 * Контекст из файла в classpath — few-shot примеры «сообщение → итоговый JSON».
 * Смена источника (API, БД) — новая реализация [ContextProvider] без правки промпта.
 */
@Singleton(binds = [ContextProvider::class])
class FileContextProvider(
  private val resourcePath: String = DEFAULT_RESOURCE,
) : ContextProvider {

  override fun systemContext(): String =
    javaClass.classLoader.getResourceAsStream(resourcePath)
      ?.bufferedReader()?.readText()
      ?: throw IllegalStateException("Context resource not found: $resourcePath")

  companion object {
    const val DEFAULT_RESOURCE = "context/meetup-examples.md"
  }
}
