package org.jep21s.meetupflowagent.googlecalendar

import com.fasterxml.jackson.databind.JsonNode
import org.jep21s.meetupflowagent.starter.jackson.jacksonMapper
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Ключ сервисного аккаунта Google (JSON из Cloud Console). Секрет — только в
 * ENV (`GOOGLE_CALENDAR_CREDENTIALS_JSON`), в `destinations.config` его быть
 * не должно. Подпись JWT (RS256) — штатным `java.security`, без внешних библиотек.
 */
class ServiceAccountCredentials(
  val clientEmail: String,
  private val privateKey: PrivateKey,
) {

  /**
   * Signed JWT (grant_type=jwt-bearer) для обмена на access_token: заголовок
   * {alg:RS256, typ:JWT}, claims iss/scope/aud + exp/iat (час жизни).
   */
  fun rs256Assertion(scope: String, audience: String, now: Instant = Instant.now()): String {
    val header = """{"alg":"RS256","typ":"JWT"}"""
    val iat = now.epochSecond
    val exp = iat + ASSERTION_TTL_SECONDS
    val claims = """{"iss":"$clientEmail","scope":"$scope","aud":"$audience","exp":$exp,"iat":$iat}"""
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val signingInput =
      encoder.encodeToString(header.toByteArray(Charsets.UTF_8)) + "." +
        encoder.encodeToString(claims.toByteArray(Charsets.UTF_8))
    val signature = Signature.getInstance("SHA256withRSA").apply {
      initSign(privateKey)
      update(signingInput.toByteArray(Charsets.US_ASCII))
    }.sign()
    return "$signingInput.${encoder.encodeToString(signature)}"
  }

  companion object {
    private const val ASSERTION_TTL_SECONDS = 3600L

    /** Разбор PEM-блока private_key из JSON-ключа ("-----BEGIN PRIVATE KEY-----…"). */
    fun parse(json: String): ServiceAccountCredentials? {
      val trimmed = json.trim()
      if (trimmed.isEmpty()) return null
      val root: JsonNode = jacksonMapper.readTree(trimmed)
      val email = root.path("client_email").asText().trim()
      val pemBody = root.path("private_key").asText()
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
      check(email.isNotEmpty() && pemBody.isNotEmpty()) {
        "service account json must contain non-empty client_email and private_key"
      }
      val der = Base64.getDecoder().decode(pemBody)
      val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
      return ServiceAccountCredentials(clientEmail = email, privateKey = key)
    }
  }
}
