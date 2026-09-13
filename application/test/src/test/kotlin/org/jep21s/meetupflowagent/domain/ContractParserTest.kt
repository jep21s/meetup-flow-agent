package org.jep21s.meetupflowagent.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Test

class ContractParserTest {

  @Test
  fun `parses clean json`() {
    val parsed = ContractParser.parse("""{"title":"PiterJS #61","startsAt":"2026-10-02T19:00+03:00"}""")

    assertThat(parsed.dto.title).isEqualTo("PiterJS #61")
    assertThat(parsed.dto.startsAt).isEqualTo("2026-10-02T19:00+03:00")
    assertThat(parsed.raw.path("title").asText()).isEqualTo("PiterJS #61")
  }

  @Test
  fun `parses markdown-wrapped json with prose around`() {
    val text = """
      Вот итог извлечения:

      ```json
      {"title":"GoSPb #12","isFree":true,"formats":["OFFLINE"]}
      ```

      Готово.
    """.trimIndent()

    val parsed = ContractParser.parse(text)

    assertThat(parsed.dto.title).isEqualTo("GoSPb #12")
    assertThat(parsed.dto.isFree).isTrue()
  }

  @Test
  fun `garbage text fails with ContractParseException`() {
    assertThatThrownBy { ContractParser.parse("митап будет в пятницу, без JSON") }
      .isInstanceOf(ContractParseException::class.java)
  }

  @Test
  fun `broken json braces fail with ContractParseException`() {
    assertThatThrownBy { ContractParser.parse("""{"title":"x","startsAt":""") }
      .isInstanceOf(ContractParseException::class.java)
  }

  @Test
  fun `array root is not a contract`() {
    assertThatThrownBy { ContractParser.parse("""[1,2,3]""") }
      .isInstanceOf(ContractParseException::class.java)
  }

  @Test
  fun `unknown fields are ignored by design (FAIL_ON_UNKNOWN disabled)`() {
    val parsed = jacksonMapper.readValue(
      """{"title":"X","customExtraField":"whatever"}""",
      EventContractDto::class.java,
    )
    assertThat(parsed.title).isEqualTo("X")
  }
}
