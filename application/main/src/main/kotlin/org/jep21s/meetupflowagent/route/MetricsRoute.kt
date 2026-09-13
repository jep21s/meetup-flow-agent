package org.jep21s.meetupflowagent.route

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jep21s.meetupflowagent.observability.Metrics
import org.koin.mp.KoinPlatform

/**
 * GET /internal/metrics — текст Prometheus (scrape). Роут ВНЕ /api: без
 * авторизации (скрейп только локально/внутри сети, §11).
 */
fun Route.internalMetrics() {
  get("/internal/metrics") {
    val metrics: Metrics = KoinPlatform.getKoin().get(Metrics::class)
    call.respondText(metrics.scrape())
  }
}
