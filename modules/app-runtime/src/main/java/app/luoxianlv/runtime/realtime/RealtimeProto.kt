package app.luoxianlv.runtime.realtime

import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.DynamicMessage
import org.json.JSONArray
import org.json.JSONObject

/** The socket uses the shared schema. JSONObject remains the local module contract. */
internal object RealtimeProto {
    private const val SCHEMA = "/app/luoxianlv/runtime/realtime/realtime.pb"

    private val frame by lazy {
        val files = mutableMapOf<String, FileDescriptor>()
        val schema =
            checkNotNull(javaClass.getResourceAsStream(SCHEMA)).use {
                FileDescriptorSet.parseFrom(it)
            }
        for (file in schema.fileList) {
            files[file.name] =
                FileDescriptor.buildFrom(
                    file,
                    file.dependencyList.map(files::getValue).toTypedArray(),
                )
        }
        checkNotNull(files.getValue("common/frame.proto").findMessageTypeByName("Frame"))
    }

    private fun field(type: Descriptor, name: String) =
        requireNotNull(type.fields.firstOrNull { it.jsonName == name }) { "Unknown realtime field" }

    @JvmStatic
    fun encode(
        epoch: String,
        id: String,
        topic: String,
        kind: String,
        data: JSONObject,
    ): ByteArray {
        val route =
            if (kind == "error") "error" else topic + kind.replaceFirstChar { it.uppercaseChar() }
        val payload =
            when {
                kind == "error" ->
                    JSONObject()
                        .put("topic", topic)
                        .put("status", data.getInt("status"))
                        .put("message", data.getString("message"))
                route == "ensembleResult" && data.has("members") ->
                    JSONObject().put("snapshot", data)
                route == "ensembleResult" && data.has("room") && data.isNull("room") ->
                    JSONObject().put("noRoom", true)
                else -> data
            }
        val envelope =
            JSONObject()
                .put("protocolVersion", 1)
                .put("messageId", id)
                .put("connectionEpoch", epoch)
                .put(route, payload)
        val message = build(frame, envelope)
        require(message.serializedSize <= RealtimeWire.MAX_BYTES)
        return message.toByteArray()
    }

    private fun build(type: Descriptor, data: JSONObject): DynamicMessage {
        val result = DynamicMessage.newBuilder(type)
        for (name in data.keys()) {
            val value = data.get(name)
            if (value === JSONObject.NULL) continue
            if (type.name == "QueryRequestData" && name == "body") {
                result.setField(
                    field(type, "bodyJson"),
                    ByteString.copyFromUtf8((value as JSONObject).toString()),
                )
                continue
            }
            val key = field(type, name)
            if (key.isRepeated) {
                val items = value as JSONArray
                for (i in 0 until items.length()) result.addRepeatedField(
                    key,
                    scalar(key, items.get(i)),
                )
            } else result.setField(key, scalar(key, value))
        }
        return result.build()
    }

    private fun scalar(field: FieldDescriptor, value: Any): Any =
        when (field.javaType) {
            JavaType.MESSAGE -> build(field.messageType, value as JSONObject)
            JavaType.INT ->
                (value as Number).toLong().also { require(it in 0..0xffff_ffffL) }.toInt()
            JavaType.LONG -> (value as Number).toLong()
            JavaType.BYTE_STRING -> ByteString.copyFrom(value as ByteArray)
            else -> value
        }

    @JvmStatic
    fun decode(bytes: ByteArray): JSONObject {
        require(bytes.size <= RealtimeWire.MAX_BYTES)
        val message = DynamicMessage.parseFrom(frame, bytes)
        val route = requireNotNull(message.getOneofFieldDescriptor(frame.oneofs.single()))
        val payload = unpack(message.getField(route) as DynamicMessage)
        val topic =
            if (route.name == "error") payload.remove("topic") as String
            else route.name.substringBefore('_')
        val kind =
            if (route.name == "error") "error"
            else route.jsonName.removePrefix(topic).replaceFirstChar { it.lowercaseChar() }
        val data =
            when {
                route.name == "ensemble_result" && payload.has("snapshot") ->
                    payload.getJSONObject("snapshot")
                route.name == "ensemble_result" && payload.optBoolean("noRoom") ->
                    JSONObject().put("room", JSONObject.NULL)
                else -> payload
            }
        return JSONObject()
            .put("protocolVersion", message.getField(field(frame, "protocolVersion")))
            .put("messageId", message.getField(field(frame, "messageId")))
            .put("connectionEpoch", message.getField(field(frame, "connectionEpoch")))
            .put("topic", topic)
            .put("type", kind)
            .put("data", data)
    }

    private fun unpack(message: DynamicMessage): JSONObject {
        val result = JSONObject()
        for (key in message.descriptorForType.fields) {
            if (key.isRepeated) {
                val items = JSONArray()
                for (value in message.getField(key) as List<*>) items.put(unpackValue(key, value!!))
                result.put(key.jsonName, items)
            } else if (message.hasField(key)) {
                val value = message.getField(key)
                if (key.name == "body_json")
                    result.put(
                        "body",
                        RealtimeWire.objectValue((value as ByteString).toStringUtf8()),
                    )
                else result.put(key.jsonName, unpackValue(key, value))
            }
        }
        return result
    }

    private fun unpackValue(field: FieldDescriptor, value: Any): Any =
        when (value) {
            is DynamicMessage -> unpack(value)
            is ByteString -> value.toByteArray()
            is Int ->
                if (field.type == FieldDescriptor.Type.UINT32) Integer.toUnsignedLong(value)
                else value
            else -> value
        }
}
