package org.jep21s.meetupflowagent.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import org.jep21s.meetupflowagent.starter.config.ConfigLoader

private val logger = KotlinLogging.logger { }

/**
 * Один origin, распарсенный из строки конфигурации.
 *
 * [hostWithPort] — домен с портом, если порт не дефолтный для схемы (напр. `localhost:5173`);
 * для дефолтного порта — только домен (`example.com`).
 */
internal data class CorsOrigin(val scheme: String, val hostWithPort: String)

/**
 * Разбирает строку `cors.allowed.origins` в список [CorsOrigin].
 *
 * - Пустая строка → `null` (CORS не устанавливается).
 * - `*` → пустой список (anyHost, все схемы).
 * - Список через `,` → распарсенные origins. Невалидные строки пропускаются с warning.
 *
 * Ktor CORS матчит origin строго с портом: `allowHost("localhost")` НЕ сматчит
 * `http://localhost:5173`. Поэтому порт сохраняется явно.
 */
internal fun parseCorsOrigins(raw: String): List<CorsOrigin>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed == "*") return emptyList()
    return trimmed.split(',').mapNotNull { piece ->
        val origin = piece.trim()
        if (origin.isEmpty()) return@mapNotNull null
        val lowered = origin.lowercase()
        if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) {
            logger.warn { "Skipping CORS origin without http(s) prefix: '$origin'" }
            return@mapNotNull null
        }
        val url = try {
            Url(origin)
        } catch (e: URLParserException) {
            logger.warn(e) { "Skipping invalid CORS origin: '$origin'" }
            return@mapNotNull null
        }
        val scheme = url.protocol.name
        if (url.host.isEmpty()) {
            logger.warn { "Skipping CORS origin without host: '$origin'" }
            return@mapNotNull null
        }
        val hostWithPort = if (url.port == url.protocol.defaultPort) url.host else "${url.host}:${url.port}"
        CorsOrigin(scheme, hostWithPort)
    }
}

/**
 * Условно устанавливает CORS-плагин.
 *
 * Если `cors.allowed.origins` пуст — плагин не инсталлируется (поведение по умолчанию,
 * для прод-конфигурации same-origin). Иначе — конфигурируется согласно [parseCorsOrigins].
 *
 * `allowCredentials = false`: токен передаётся в заголовке Authorization, не в cookie;
 * с `anyHost()` это и обязательно (браузер запрещает credentials с wildcard origin).
 */
fun Application.configureCors() {
    val raw = ConfigLoader.getProperty("cors.allowed.origins")
    val origins = parseCorsOrigins(raw) ?: run {
        logger.info { "CORS disabled (cors.allowed.origins is empty)" }
        return
    }

    install(CORS) {
        if (origins.isEmpty()) {
            anyHost()
        } else {
            origins.forEach { allowHost(it.hostWithPort, schemes = listOf(it.scheme)) }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowCredentials = false
    }
    logger.info { "CORS configured: origins=${origins.size.let { if (it == 0) "*" else origins }}" }
}
