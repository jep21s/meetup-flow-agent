package org.jep21s.meetupflowagent.starter.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.util.Properties

private val logger = KotlinLogging.logger { }

/**
 * Shared singleton-загрузчик конфигурации (без DI).
 *
 * Читает config.properties из classpath (fallback: src/main/resources/config.properties как файл),
 * подставляет env-переменные в формате ${ENV_VAR:default}.
 */
object ConfigLoader {
    private val properties: Properties by lazy {
        val props = Properties()
        try {
            val configFile = ConfigLoader::class.java.classLoader.getResourceAsStream("config.properties")
                ?: FileInputStream("src/main/resources/config.properties")
            props.load(configFile)
        } catch (e: Exception) {
            logger.error(e) { "Warning: Could not load config.properties. Using default values." }
        }
        props
    }

    fun getProperty(key: String, defaultValue: String = ""): String {
        // -Doverride (system property) выше файла: точечная подмена без правки
        // config.properties — удобно ops и тестам (WireMock-URL в proxy.baseUrl)
        val rawValue = System.getProperty(key) ?: properties.getProperty(key, defaultValue)
        return resolveEnvironmentVariables(rawValue)
    }

    internal fun resolveEnvironmentVariables(value: String): String {
        // ${VAR}, ${VAR:} (пустой дефолт) и ${VAR:default} — имя VAR не содержит ':'
        val regex = Regex("\\$\\{([^}:]+)(?::([^}]*))?\\}")
        return regex.replace(value) { matchResult ->
            val (envVar, defaultValue) = matchResult.destructured
            System.getenv(envVar) ?: defaultValue
        }
    }

    fun getRequiredProperty(key: String, errorMessage: String? = null): String {
        val value = getProperty(key)
        if (value.isBlank()) {
            throw IllegalStateException(errorMessage ?: "Required property '$key' is not configured")
        }
        return value
    }
}
