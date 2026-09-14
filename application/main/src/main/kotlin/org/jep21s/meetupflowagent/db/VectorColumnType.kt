package org.jep21s.meetupflowagent.db

import org.jetbrains.exposed.v1.core.ColumnType
import org.postgresql.util.PGobject

/**
 * ColumnType для pgvector `vector(dim)`: значение — [FloatArray].
 *
 * Биндинг через строковое представление `"[1.0,2.0,…]"` (pgjdbc не умеет отдавать
 * vector как JDBC-тип: и запись, и чтение идут текстом — при чтении приходит
 * [PGobject] со строковым value). Работает в паре с `stringtype=unspecified`
 * у пула (см. [DatabaseConnectivity]): PostgreSQL резолвит тип параметра из
 * контекста колонки/каста `?::vector`.
 */
class VectorColumnType(private val dim: Int) : ColumnType<FloatArray>() {

  override fun sqlType(): String = "vector($dim)"

  override fun nonNullValueToString(value: FloatArray): String =
    "'[${value.joinToString(",") { it.toString() }}]'"

  override fun notNullValueToDB(value: FloatArray): Any =
    value.joinToString(",", "[", "]") { it.toString() }

  override fun valueFromDB(value: Any): FloatArray = when (value) {
    is FloatArray -> value
    is PGobject -> parseVector(value.value)
    is String -> parseVector(value)
    else -> error("Unexpected vector value: ${value::class.qualifiedName}")
  }

  private fun parseVector(raw: String?): FloatArray {
    if (raw.isNullOrBlank()) error("Empty vector value")
    return raw.trim('[', ']').split(",").map { it.toFloat() }.toFloatArray()
  }
}
