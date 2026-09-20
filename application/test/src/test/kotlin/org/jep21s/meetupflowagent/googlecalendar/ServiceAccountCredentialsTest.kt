package org.jep21s.meetupflowagent.googlecalendar

import org.assertj.core.api.Assertions.assertThat
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.time.Instant
import java.util.Base64

/**
 * Разбор JSON-ключа сервисного аккаунта и подпись RS256-assertion (grant_type=
 * jwt-bearer) — проверяется настоящей криптографией по сгенерированной паре.
 */
class ServiceAccountCredentialsTest {

  private val urlDecoder = Base64.getUrlDecoder()

  @Test
  fun `blank json parses to null`() {
    assertThat(ServiceAccountCredentials.parse("")).isNull()
    assertThat(ServiceAccountCredentials.parse("   ")).isNull()
  }

  @Test
  fun `parses client email and private key`() {
    val creds = ServiceAccountCredentials.parse(TestServiceAccounts.json())

    assertThat(creds).isNotNull
    assertThat(creds!!.clientEmail).isEqualTo(TestServiceAccounts.CLIENT_EMAIL)
  }

  @Test
  fun `json without private key is rejected`() {
    val ex = assertThrows<IllegalStateException> {
      ServiceAccountCredentials.parse("""{"client_email":"a@b.c"}""")
    }
    assertThat(ex.message).contains("client_email").contains("private_key")
  }

  @Test
  fun `rs256 assertion has expected header claims and verifiable signature`() {
    val creds = ServiceAccountCredentials.parse(TestServiceAccounts.json())!!
    val now = Instant.parse("2026-09-20T10:00:00Z")

    val jwt = creds.rs256Assertion(
      scope = "https://www.googleapis.com/auth/calendar.events",
      audience = "https://oauth2.googleapis.com/token",
      now = now,
    )

    val parts = jwt.split(".")
    assertThat(parts).hasSize(3)
    val header = String(urlDecoder.decode(parts[0]), StandardCharsets.UTF_8)
    assertThat(header).contains("\"alg\":\"RS256\"").contains("\"typ\":\"JWT\"")

    val claims = jacksonMapper.readTree(String(urlDecoder.decode(parts[1]), StandardCharsets.UTF_8))
    assertThat(claims.path("iss").asText()).isEqualTo(TestServiceAccounts.CLIENT_EMAIL)
    assertThat(claims.path("scope").asText()).isEqualTo("https://www.googleapis.com/auth/calendar.events")
    assertThat(claims.path("aud").asText()).isEqualTo("https://oauth2.googleapis.com/token")
    assertThat(claims.path("iat").asLong()).isEqualTo(now.epochSecond)
    assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(3600)

    // подпись проверяется публичным ключом сгенерированной пары
    val verifier = Signature.getInstance("SHA256withRSA")
    verifier.initVerify(TestServiceAccounts.keyPair.public)
    verifier.update((parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII))
    assertThat(verifier.verify(urlDecoder.decode(parts[2]))).isTrue()
  }
}
