package org.jep21s.meetupflowagent.observability

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import org.jep21s.meetupflowagent.starter.config.ConfigLoader
import org.koin.core.annotation.Singleton
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger { }

/**
 * Трейсинг (§14): OpenTelemetry SDK + OTLP exporter (otel.endpoint; пусто или
 * otel.enabled=false → noop). Span'ы: flow (root, атрибут flowId), шаги флоу,
 * llm.call (model, tokens), tool.call (tool, outcome). traceId доступен для
 * flows; при резюме — новый root-span.
 */
@Singleton
class Tracing {

  val enabled: Boolean
  private val tracer: Tracer

  init {
    val endpoint = ConfigLoader.getProperty("otel.endpoint").trim()
    enabled = ConfigLoader.getProperty("otel.enabled", "true").toBoolean() && endpoint.isNotEmpty()
    tracer = if (enabled) {
      val exporter = OtlpGrpcSpanExporter.builder()
        .setEndpoint(endpoint)
        .setTimeout(5, TimeUnit.SECONDS)
        .build()
      val provider = SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        .build()
      val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(provider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
      sdk.getTracer("meetup-flow-agent")
    } else {
      logger.info { "otel tracing disabled (otel.enabled=${ConfigLoader.getProperty("otel.enabled", "true")}, endpoint='$endpoint')" }
      OpenTelemetry.noop().getTracer("noop")
    }
  }

  /** Root-span флоу; возвращает Span (вызывающий закрывает). */
  fun startFlowSpan(flowId: String, operation: String = "flow"): Span =
    tracer.spanBuilder(operation)
      .setSpanKind(SpanKind.INTERNAL)
      .setAttribute("flowId", flowId)
      .startSpan()

  fun startStepSpan(parent: Span, name: String, attributes: Map<String, String> = emptyMap()): Span {
    val span = tracer.spanBuilder(name)
      .setParent(Context.current().with(parent))
      .startSpan()
    attributes.forEach { (k, v) -> span.setAttribute(k, v) }
    return span
  }

  fun currentTraceId(): String? {
    val ctx = Span.current().spanContext
    return if (ctx.isValid) ctx.traceId else null
  }
}
