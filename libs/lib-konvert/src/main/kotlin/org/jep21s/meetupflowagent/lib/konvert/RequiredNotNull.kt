package org.jep21s.meetupflowagent.lib.konvert

@Suppress("unused")
fun <T> T?.requireNotNull(fieldName: String): T {
  return requireNotNull(this) { "Field '$fieldName' is required" }
}

@Suppress("unused")
fun <T> T?.requireNotNull(): T {
  return requireNotNull(this)
}
