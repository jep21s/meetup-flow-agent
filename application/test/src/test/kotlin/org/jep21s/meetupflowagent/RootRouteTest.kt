package org.jep21s.meetupflowagent

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.jep21s.meetupflowagent.config.restModule

class RootRouteTest {

  @Test
  fun `root responds hello world`() = testApplication {
    application { restModule() }
    val response = client.get("/")
    assertThat(response.status.value).isEqualTo(200)
    assertThat(response.bodyAsText()).isEqualTo("Hello World!")
  }

  @Test
  fun `api ping requires token`() = testApplication {
    application { restModule() }

    val noAuth = client.get("/api/ping")
    assertThat(noAuth.status.value).isEqualTo(401)

    // дефолт app.token из config.properties (${APP_TOKEN:change-me-token})
    val authorized = client.get("/api/ping") {
      header(HttpHeaders.Authorization, "change-me-token")
    }
    assertThat(authorized.status.value).isEqualTo(200)
    assertThat(authorized.bodyAsText()).isEqualTo("pong")
  }
}
