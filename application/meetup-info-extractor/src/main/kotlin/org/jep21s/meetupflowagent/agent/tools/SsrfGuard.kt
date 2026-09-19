package org.jep21s.meetupflowagent.agent.tools

import java.net.Inet6Address
import java.net.InetAddress

/**
 * SSRF-защита: решает, запрещён ли адрес для загрузки web-тулом.
 *
 * Запрещены: loopback (127/8, ::1), private (10/8, 172.16/12, 192.168/16),
 * link-local (169.254/16, fe80::/10), unique-local IPv6 (fc00::/7), unspecified.
 */
fun interface SsrfGuard {
  fun isBlocked(address: InetAddress): Boolean

  companion object {
    val DEFAULT = SsrfGuard { addr -> isBlockedAddress(addr) }

    fun isBlockedAddress(addr: InetAddress): Boolean = when {
      addr.isLoopbackAddress -> true
      addr.isAnyLocalAddress -> true
      addr.isSiteLocalAddress -> true   // 10/8, 172.16/12, 192.168/16
      addr.isLinkLocalAddress -> true   // 169.254/16, fe80::/10
      addr is Inet6Address && isUniqueLocalIpv6(addr) -> true
      else -> false
    }

    /** fc00::/7 — уникально-локальные IPv6 (fd00::/8 на практике). */
    private fun isUniqueLocalIpv6(addr: Inet6Address): Boolean {
      val first = addr.address[0].toInt() and 0xFF
      return (first and 0xFE) == 0xFC
    }
  }
}
