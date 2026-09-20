package org.jep21s.meetupflowagent.googlecalendar

import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Фейковый сервис-аккаунт для тестов: RSA-пара генерируется один раз на
 * класс-лоадер, JSON повторяет структуру ключа из Cloud Console (PKCS#8 PEM).
 */
internal object TestServiceAccounts {

  val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

  const val CLIENT_EMAIL = "test-sa@test-project.iam.gserviceaccount.com"

  fun json(): String {
    val base64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
    val pem = "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
    return """
      {"type":"service_account","client_email":"$CLIENT_EMAIL",
       "private_key":${jacksonMapper.writeValueAsString(pem)},
       "token_uri":"https://oauth2.googleapis.com/token"}
    """.trimIndent()
  }
}
