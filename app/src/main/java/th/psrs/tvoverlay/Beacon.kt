package th.psrs.tvoverlay

import org.json.JSONObject

/** A PS Remote Sub PC heard on the LAN: its stable [id], display [name], and where to connect. */
data class PcBeacon(val id: String, val name: String, val host: String, val port: Int)

/**
 * Parses a UDP beacon datagram (`{"app":"psrs","v":1,"id":"…","name":"…","port":5000}`).
 * [host] is the datagram's source address. Returns null for anything that is not a v1 psrs beacon;
 * unknown extra fields are ignored.
 */
fun parseBeacon(bytes: ByteArray, host: String): PcBeacon? = try {
    val o = JSONObject(String(bytes, Charsets.UTF_8))
    val id = o.optString("id", "")
    val port = o.optInt("port", 0)
    if (o.optString("app") != "psrs" || o.optInt("v", 0) != 1 || id.isEmpty() || port !in 1..65535) null
    else PcBeacon(id, o.optString("name", id), host, port)
} catch (e: Exception) {
    null
}
