package org.jep21s.meetupflowagent.domain

import com.fasterxml.jackson.databind.JsonNode
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper

/** Финальный текст агента не удалось привести к контракту §7. */
class ContractParseException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/** Распарсенный контракт: DTO + исходное JSON-дерево (для raw-поля events). */
data class ParsedContract(val dto: EventContractDto, val raw: JsonNode)

/**
 * Достаёт контракт §7 из финального текста агента: модель может оборачивать JSON
 * в markdown-заборы (```json … ```) или добавлять прозу до/после — берём
 * подстроку от первого '{' до последнего '}'.
 */
object ContractParser {

  fun parse(text: String): ParsedContract {
    val json = extractJson(text)
      ?: throw ContractParseException("в ответе агента нет JSON-объекта (первая строка: ${text.lineSequence().firstOrNull()?.take(120)})")
    val tree: JsonNode = try {
      jacksonMapper.readTree(json)
    } catch (e: Exception) {
      throw ContractParseException("JSON ответа агента невалиден: ${e.message?.take(200)}", e)
    }
    if (!tree.isObject) {
      throw ContractParseException("корневой элемент не JSON-объект: ${tree.nodeType}")
    }
    val dto = try {
      jacksonMapper.treeToValue(tree, EventContractDto::class.java)
    } catch (e: Exception) {
      throw ContractParseException("ответ агента не соответствует контракту: ${e.message?.take(200)}", e)
    }
    return ParsedContract(dto, tree)
  }

  private fun extractJson(text: String): String? {
    val trimmed = text.trim().trim('`')
    // markdown-забор: ```json\n{...}\n``` — обрезаем до/после
    val body = trimmed.lineSequence()
      .dropWhile { it.trimStart().startsWith("```") || it.isBlank() }
      .toList()
      .dropLastWhile { it.trimStart().startsWith("```") || it.isBlank() }
      .joinToString("\n")
    val candidate = body.ifBlank { trimmed }
    val start = candidate.indexOf('{')
    val end = candidate.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return candidate.substring(start, end + 1)
  }
}
