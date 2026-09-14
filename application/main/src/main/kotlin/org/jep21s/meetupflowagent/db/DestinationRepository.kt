package org.jep21s.meetupflowagent.db

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.core.annotation.Singleton
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid

/** Место публикации результата (телеграм-прокси, гугл-календарь, …). */
data class DestinationRow(
  val id: UUID,
  val type: String,
  val name: String,
  val config: JsonNode,
  val isActive: Boolean,
)

/** Справочник назначений: config — только не-секретные параметры (секреты в ENV). */
@OptIn(ExperimentalUuidApi::class)
@Singleton
class DestinationRepository(private val db: DatabaseConnectivity) {

  suspend fun listActive(): List<DestinationRow> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Destinations.selectAll().where { Destinations.isActive eq true }.map { it.toDestinationRow() }
    }
  }

  suspend fun listAll(): List<DestinationRow> = withContext(Dispatchers.IO) {
    suspendTransaction(db.database) {
      Destinations.selectAll().map { it.toDestinationRow() }
    }
  }

  private fun ResultRow.toDestinationRow() = DestinationRow(
    id = this[Destinations.id].toJavaUuid(),
    type = this[Destinations.type],
    name = this[Destinations.name],
    config = this[Destinations.config],
    isActive = this[Destinations.isActive],
  )
}
