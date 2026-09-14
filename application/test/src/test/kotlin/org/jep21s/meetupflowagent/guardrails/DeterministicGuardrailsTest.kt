package org.jep21s.meetupflowagent.guardrails

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeterministicGuardrailsTest {

  @Test
  fun `clean message passes deterministic checks (null → LLM решает)`() {
    assertThat(DeterministicGuardrails.check("Митап PiterJS 2 октября, вход бесплатный")).isNull()
  }

  @Test
  fun `too long message is blocked`() {
    val verdict = DeterministicGuardrails.check("x".repeat(DeterministicGuardrails.MAX_TEXT_LENGTH + 1))
    assertThat(verdict).isNotNull
    assertThat(verdict!!.verdict).isEqualTo(GuardrailsVerdict.Verdict.SUSPICIOUS)
    assertThat(verdict.reasons.single()).contains("length")
  }

  @Test
  fun `url spam is blocked`() {
    val urls = (1..6).joinToString(" ") { "https://example.com/$it" }
    val verdict = DeterministicGuardrails.check("смотрите $urls")
    assertThat(verdict).isNotNull
    assertThat(verdict!!.reasons.single()).contains("URL spam")
  }

  @Test
  fun `control characters are blocked`() {
    val verdict = DeterministicGuardrails.check("митап\u0000 завтра")
    assertThat(verdict).isNotNull
    assertThat(verdict!!.reasons.single()).contains("control character")
  }

  @Test
  fun `tabs and newlines are allowed`() {
    assertThat(DeterministicGuardrails.check("митап\n\tзавтра в 19:00\r\nвход свободный")).isNull()
  }
}
