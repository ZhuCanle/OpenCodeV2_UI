package com.zhucanle.opencodev2.ide.api.models

import com.google.gson.*
import java.lang.reflect.Type

/**
 * Base class for OpenCode SSE events.
 */
sealed class OpenCodeEvent {
    abstract val type: String
}

/**
 * Unknown event type - for events we don't specifically handle.
 */
data class UnknownEvent(
    override val type: String
) : OpenCodeEvent()

/**
 * v2 turn start (`session.execution.started`). Emitted once per turn, before the first step.
 *
 * This is the real v2 "busy" signal; the v1 `session.status {status:{type:"busy"}}` was never
 * observed on the v2 wire.
 */
data class SessionExecutionStartedEvent(
    val sessionID: String,
    override val type: String = "session.execution.started"
) : OpenCodeEvent()

/**
 * v2 turn end (`session.execution.succeeded|failed|interrupted`).
 *
 * @property outcome one of `succeeded`, `failed`, `interrupted`, derived from the wire type name
 * @property errorMessage `data.error.message` carried by `session.execution.failed`, when present
 * @property type the wire type name, e.g. `session.execution.failed`
 */
data class SessionExecutionEndedEvent(
    val sessionID: String,
    val outcome: String,
    val errorMessage: String? = null,
    override val type: String = "session.execution.$outcome"
) : OpenCodeEvent()

/**
 * v2 step end (`session.step.ended`). Carries the files changed during the step; this replaces the
 * v1 `session.diff` event, which was not observed on the v2 wire.
 *
 * @property assistantMessageID id of the assistant message the step belongs to
 * @property files repo-relative paths changed during the step; empty when nothing changed
 * @property snapshot git blob sha of the working-tree snapshot for the step, if reported
 */
data class SessionStepEndedEvent(
    val sessionID: String,
    val assistantMessageID: String? = null,
    val files: List<String> = emptyList(),
    val snapshot: String? = null,
    override val type: String = "session.step.ended"
) : OpenCodeEvent()

/**
 * Custom Gson deserializer for OpenCodeEvent.
 *
 * Routes **only** the real v2 event names, whose payload is the envelope's `data` object, and falls
 * back to [UnknownEvent] for everything else. The v1 names (`session.diff`, `session.idle`,
 * `session.status`, `session.updated`, `message.updated`, `message.part.*`, `command.executed`,
 * `file.edited`), which carried their payload in `properties`, are intentionally not supported:
 * the plugin is v2-only and those names were never observed on the v2 wire. Envelope bookkeeping
 * fields (`id`, `created`, `location`, `metadata`, `durable`) are ignored.
 */
class OpenCodeEventDeserializer : JsonDeserializer<OpenCodeEvent> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): OpenCodeEvent {
        val jsonObject = json.asJsonObject
        val eventType = jsonObject.get("type")?.asString ?: return UnknownEvent("unknown")
        val data = jsonObject.getAsJsonObject("data")

        // A frame missing its session id is dropped (UnknownEvent) so turn state is never keyed
        // under "".
        return when (eventType) {
            "session.execution.started" -> data.nonBlank("sessionID")
                ?.let { SessionExecutionStartedEvent(sessionID = it) }
                ?: UnknownEvent(eventType)
            "session.execution.succeeded" -> data.nonBlank("sessionID")
                ?.let { SessionExecutionEndedEvent(sessionID = it, outcome = "succeeded") }
                ?: UnknownEvent(eventType)
            "session.execution.failed" -> data.nonBlank("sessionID")
                ?.let {
                    SessionExecutionEndedEvent(
                        sessionID = it,
                        outcome = "failed",
                        errorMessage = data.nestedString("error", "message")
                    )
                }
                ?: UnknownEvent(eventType)
            "session.execution.interrupted" -> data.nonBlank("sessionID")
                ?.let { SessionExecutionEndedEvent(sessionID = it, outcome = "interrupted") }
                ?: UnknownEvent(eventType)
            "session.step.ended" -> data.nonBlank("sessionID")
                ?.let {
                    SessionStepEndedEvent(
                        sessionID = it,
                        assistantMessageID = data.stringOrNull("assistantMessageID"),
                        files = data.stringList("files"),
                        snapshot = data.stringOrNull("snapshot")
                    )
                }
                ?: UnknownEvent(eventType)

            else -> UnknownEvent(eventType)
        }
    }

    /** Reads a string field, returning null when the object is null or the field is absent/not a string. */
    private fun JsonObject?.stringOrNull(name: String): String? {
        val element = this?.get(name) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    /** Reads a string field only when it is present and non-blank. */
    private fun JsonObject?.nonBlank(name: String): String? =
        stringOrNull(name)?.takeIf { it.isNotBlank() }

    /** Reads a nested `parent.child` string field, or null when any level is absent/not a string. */
    private fun JsonObject?.nestedString(parent: String, child: String): String? {
        val nested = this?.get(parent) ?: return null
        if (!nested.isJsonObject) return null
        return nested.asJsonObject.stringOrNull(child)
    }

    /** Reads a string-array field, returning an empty list when absent or not an array. */
    private fun JsonObject?.stringList(name: String): List<String> {
        val element = this?.get(name) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) item.asString else null
        }
    }
}
