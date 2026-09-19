package org.jep21s.meetupflowagent.starter.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigLoaderTest {

  @Test
  fun `plain value passes through`() {
    assertThat(ConfigLoader.resolveEnvironmentVariables("abc")).isEqualTo("abc")
  }

  @Test
  fun `placeholder without env maps to empty default`() {
    // ключевой кейс: ${VAR:} — пустой дефолт (секрет не задан в dev-окружении)
    assertThat(ConfigLoader.resolveEnvironmentVariables("\${LLM_API_KEY:}")).isEmpty()
  }

  @Test
  fun `placeholder with default maps to default`() {
    assertThat(ConfigLoader.resolveEnvironmentVariables("\${LLM_BASE_URL:https://api.example.com}"))
      .isEqualTo("https://api.example.com")
  }

  @Test
  fun `set env variable wins over default`() {
    withEnv("MEETUP_FLOW_TEST_VAR", "hello") {
      assertThat(ConfigLoader.resolveEnvironmentVariables("\${MEETUP_FLOW_TEST_VAR:fallback}"))
        .isEqualTo("hello")
      assertThat(ConfigLoader.resolveEnvironmentVariables("\${MEETUP_FLOW_TEST_VAR:}"))
        .isEqualTo("hello")
    }
  }

  @Test
  fun `mixed text with placeholders`() {
    withEnv("MEETUP_FLOW_TEST_VAR", "x") {
      assertThat(ConfigLoader.resolveEnvironmentVariables("a\${MEETUP_FLOW_TEST_VAR}b"))
        .isEqualTo("axb")
    }
  }

  private fun withEnv(name: String, value: String, block: () -> Unit) {
    val field = System.getenv().javaClass.getDeclaredField("m")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val map = field.get(System.getenv()) as MutableMap<String, String>
    map[name] = value
    try {
      block()
    } finally {
      map.remove(name)
    }
  }
}
