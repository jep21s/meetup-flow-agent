package org.jep21s.meetupflowagent.starter.jackson

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Готовый ObjectMapper с проектным конфигом. Для Ktor (server/client ContentNegotiation)
 * использовать JacksonConfig.customizer — единый источник правды для Jackson во всём приложении.
 */
val jacksonMapper = jacksonObjectMapper()
  .apply {
    JacksonConfig.customizer(this)
  }

object JacksonConfig {
  val customizer: ObjectMapper.() -> Unit = {
    JacksonConfig.enabled.forEach {
      enable(it)
    }
    JacksonConfig.disabled.forEach {
      disable(it)
    }
    JacksonConfig.modules.forEach {
      registerModule(it)
    }
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
  }

  private val enabled = setOf(
    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY
  )

  private val disabled = setOf(
    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
  )

  private val modules = setOf(
    JavaTimeModule()
  )
}
