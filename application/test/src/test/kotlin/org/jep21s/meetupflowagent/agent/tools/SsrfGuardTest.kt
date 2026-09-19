package org.jep21s.meetupflowagent.agent.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress

class SsrfGuardTest {

  @Test
  fun `blocks private loopback and link-local addresses`() {
    val blocked = listOf(
      "127.0.0.1", "127.8.8.8",
      "10.0.0.1",
      "172.16.0.1", "172.31.255.255",
      "192.168.1.1",
      "169.254.1.1",
      "0.0.0.0",
      "::1", "fd00::1", "fc00::1", "fe80::1",
    )
    for (ip in blocked) {
      assertThat(SsrfGuard.isBlockedAddress(InetAddress.getByName(ip)))
        .`as`("$ip должен быть заблокирован")
        .isTrue
    }
  }

  @Test
  fun `allows public addresses`() {
    val allowed = listOf(
      "93.184.216.34",
      "8.8.8.8",
      "2a00::1",
      "2001:4860:4860::8888",
    )
    for (ip in allowed) {
      assertThat(SsrfGuard.isBlockedAddress(InetAddress.getByName(ip)))
        .`as`("$ip должен быть разрешён")
        .isFalse
    }
  }
}
