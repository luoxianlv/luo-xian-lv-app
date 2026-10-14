package app.luoxianlv.platform

import android.os.Bundle
import org.json.JSONObject

internal enum class RealtimeConnection(val wire: String) {
    STOPPED("stopped"),
    WAITING_NETWORK("waiting_network"),
    UNAUTHORIZED("unauthorized"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    BACKOFF("backoff"),
}

internal data class RealtimeIdentity(
    val connectionEpoch: String = "",
    val accountId: String = "",
    val accountResetEpoch: Long = 0,
)

internal data class RealtimeVersions(
    val versionCode: Long,
    val versionName: String,
    val updateEnabled: Boolean,
    val midiCoreVersion: String,
) {
    companion object {
        fun parse(json: JSONObject): RealtimeVersions {
            val code = json.optLong("versionCode", 0)
            require(code >= 0)
            return RealtimeVersions(
                code,
                json.optString("versionName").take(128),
                json.optBoolean("updateEnabled", true),
                json.optString("midiCoreVersion").take(256),
            )
        }
    }
}

internal data class RealtimeState(
    val connection: RealtimeConnection = RealtimeConnection.STOPPED,
    val identity: RealtimeIdentity = RealtimeIdentity(),
    val queriesReady: Boolean = false,
    val hotSubscribed: Boolean = false,
    val versions: RealtimeVersions? = null,
) {
    val connected
        get() = connection == RealtimeConnection.CONNECTED

    companion object {
        fun read(value: Bundle) =
            RealtimeState(
                connection =
                    RealtimeConnection.entries.firstOrNull { it.wire == value.getString("state") }
                        ?: RealtimeConnection.STOPPED,
                identity =
                    RealtimeIdentity(
                        value.getString("connectionEpoch").orEmpty(),
                        value.getString("accountId").orEmpty(),
                        value.getLong("accountResetEpoch"),
                    ),
                queriesReady = value.getBoolean("queriesReady"),
                hotSubscribed = value.getBoolean("hotSubscribed"),
                versions =
                    value
                        .getString("catalog")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            runCatching { RealtimeVersions.parse(JSONObject(it)) }.getOrNull()
                        },
            )
    }
}

internal enum class RealtimeResource(val wire: String) {
    LIBRARY("library"),
    INTERACTIONS("interactions"),
    NOTIFICATIONS("notifications"),
    PROFILE("profile"),
}

/** 刷新版本是内存序号；晚订阅或慢页面仍能发现变化，不依赖瞬时通知是否被消费。 */
internal data class RealtimeInvalidations(
    val identity: RealtimeIdentity = RealtimeIdentity(),
    val library: Long = 0,
    val interactions: Long = 0,
    val notifications: Long = 0,
    val profile: Long = 0,
) {
    fun changed(resource: RealtimeResource, revision: Long) =
        when (resource) {
            RealtimeResource.LIBRARY -> copy(library = revision)
            RealtimeResource.INTERACTIONS -> copy(interactions = revision)
            RealtimeResource.NOTIFICATIONS -> copy(notifications = revision)
            RealtimeResource.PROFILE -> copy(profile = revision)
        }
}

internal sealed interface RealtimeEvent {
    data class StateChanged(val state: RealtimeState) : RealtimeEvent

    data class VersionsChanged(val versions: RealtimeVersions) : RealtimeEvent

    data class ResourceChanged(
        val identity: RealtimeIdentity,
        val resource: RealtimeResource,
        val revision: Long,
    ) : RealtimeEvent
}

/** 只接受普通通知；协议响应、控制指令和未知类型不会进入业务事件流。 */
internal object RealtimeEventFormat {
    fun decode(topic: String, type: String, json: String, state: RealtimeState): RealtimeEvent? {
        if (!state.connected || type != "changed" || topic !in setOf("catalog", "business"))
            return null
        require(json.length <= 16 * 1024 && json.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        val frame = JSONObject(json)
        require(
            frame.getInt("protocolVersion") == 1 &&
                frame.getString("topic") == topic &&
                frame.getString("type") == type
        )
        if (frame.getString("connectionEpoch") != state.identity.connectionEpoch) return null
        val data = frame.getJSONObject("data")
        if (topic == "catalog")
            return RealtimeEvent.VersionsChanged(
                RealtimeVersions.parse(data.getJSONObject("versions"))
            )
        if (state.identity.accountId.isEmpty()) return null
        val resource =
            RealtimeResource.entries.firstOrNull { it.wire == data.getString("kind") }
                ?: return null
        val revision = data.getString("revision").toLong().also { require(it > 0) }
        return RealtimeEvent.ResourceChanged(state.identity, resource, revision)
    }
}
