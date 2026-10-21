package com.padlink.app

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

/**
 * البروتوكول (أسطر نصية UTF-8) — نفسه يعمل فوق Wi-Fi/TCP وفوق Bluetooth:
 *  تحكم -> شاشة:  HELLO <pin> <name> | CFG 1:A,2:B | SENS 2.0 | D id | U id | T id | DM id dx dy | P D | P M dx dy | P U | PING t
 *  شاشة -> تحكم:  OK <name> | NO | PONG t
 */
object Proto {
    const val TCP_PORT = 5555
    const val UDP_PORT = 5556
    const val NSD_TYPE = "_padlink._tcp."
    const val MAX_BTN = 30
    val BT_UUID: UUID = UUID.fromString("7a1d0c5e-4b0b-4f43-9c1e-5ad11e000777")

    fun pin(c: Context): String {
        val p = c.getSharedPreferences("app", Context.MODE_PRIVATE)
        var s = p.getString("pin", null)
        if (s == null) {
            s = (1000 + SecureRandom().nextInt(9000)).toString()
            p.edit().putString("pin", s).apply()
        }
        return s
    }

    fun deviceName(): String = (android.os.Build.MODEL ?: "Android").replace(Regex("[^A-Za-z0-9._-]"), "_")

    private val okIface = Regex("^(wlan|ap|swlan|rndis|eth|p2p|bt-pan|softap|wigig).*")

    /** كل عناوين IPv4 المحلية (واي فاي، هوت سبوت، USB-tether…): (اسم الواجهة, العنوان) */
    fun localIps(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && okIface.matches(it.name) }
            .flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .map { ni.name to (it.hostAddress ?: "") }
            }
    } catch (_: Throwable) {
        emptyList()
    }
}
