package org.jep21s.meetupflowagent.outbox

/**
 * Транспорт доставки публикации в назначение типа [type] (= destinations.type).
 * Точка расширения: гугл-календарь и другие каналы — будущие имплементации.
 * Одна попытка на вызов: исключение = неудача, ретраи по расписанию — задача
 * OutboxPoller'а (в отличие от in-flight retry старого ProxyNotifier).
 */
interface OutboxTransport {
  val type: String

  suspend fun deliver(task: OutboxDeliveryTask)
}
