package app.flick.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format shared with the desktop receiver. See docs/PROTOCOL.md. */
object Flick {
    const val DISCOVERY_PORT = 47474
    const val SERVICE_TYPE = "_flick._tcp"
    const val PROTOCOL_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

@Serializable
data class Probe(
    val flick: Int = Flick.PROTOCOL_VERSION,
    val type: String = "probe",
    val id: String,
    val name: String,
)

/** A computer's answer to a probe, and the body of GET /v1/info. */
@Serializable
data class Here(
    val flick: Int = 0,
    val type: String = "",
    val id: String,
    val name: String,
    val os: String = "",
    val port: Int = 0,
    val fp: String = "",
    val v: String = "",
)

@Serializable
data class SenderInfo(
    val id: String,
    val name: String,
    val model: String = "",
    val platform: String = "android",
    val trustKey: String? = null,
)

@Serializable
data class ItemInfo(
    val name: String,
    val size: Long,
    val mime: String,
)

@Serializable
data class AskRequest(
    val sender: SenderInfo,
    val items: List<ItemInfo> = emptyList(),
    val text: String? = null,
    val thumb: String? = null,
    /** Ask to connect: the computer may then push its clipboard and files to this phone. */
    val pair: Boolean? = null,
)

@Serializable
data class AskResponse(
    val accepted: Boolean = false,
    val session: String? = null,
    val token: String? = null,
    val trustKey: String? = null,
    val reason: String? = null,
)

@Serializable
data class UploadResponse(
    val ok: Boolean = false,
    val name: String? = null,
    val error: String? = null,
)

/** A computer we can send to. */
data class Receiver(
    val id: String,
    val name: String,
    val os: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val via: String = "udp",
) {
    val isMac get() = os == "mac"
    val isWindows get() = os == "windows"
}

/** A file offer from a computer to this phone. */
@Serializable
data class Offer(
    val id: String,
    val from: String = "",
    val fromId: String = "",
    val items: List<ItemInfo> = emptyList(),
    val total: Long = 0,
    val description: String = "",
)

/** One line on the live connection from a computer. */
@Serializable
data class LinkEvent(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val clipboard: Boolean? = null,
    val text: String? = null,
    val offer: Offer? = null,
    val offerId: String? = null,
)

@Serializable
data class OfferStatus(val state: String, val reason: String = "")

@Serializable
data class ClipBody(val text: String)

@Serializable
data class ErrorBody(val error: String = "")

/** How a paired phone identifies itself to a computer. */
data class PhoneAuth(val phoneId: String, val key: String, val name: String, val model: String = "")

/** Something to upload: a file on the phone, or a test file on the JVM. */
interface Payload {
    val name: String
    val size: Long          // -1 when unknown
    val mime: String
    fun open(): java.io.InputStream
}
