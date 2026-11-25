package nz.mckenzie.frigo.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import nz.mckenzie.frigo.R

/**
 * The Home Assistant this phone asks about a camera's floodlight, and the token it
 * asks with.
 *
 * It belongs to the phone rather than to a server: it is a box in the house, and
 * one token asks about every light in it. The token is kept the way Frigate's is,
 * in this app's own storage.
 */
@Serializable
data class HomeAssistant(val address: String = "", val token: String = "") {
    /** Whether the phone has been told where the box is and how to ask. */
    val isSet: Boolean get() = address.isNotBlank() && token.isNotBlank()
}

/** A light or a switch in Home Assistant: the id everything is asked for, and the name to show. */
data class Light(val id: String, val name: String) {
    /** What Home Assistant calls this kind of entity: `light`, or `switch` for a lamp. */
    val domain: String get() = id.substringBefore('.')
}

/** Home Assistant turning something down, in words a screen can show. */
class HomeAssistantException(override val message: String) : Exception(message)

/**
 * The little of Home Assistant's REST API this app needs: what lights there are,
 * whether one is on, and turning it either way.
 *
 * A long-lived token is a bearer token, the same shape as Frigate's session and
 * with no end date, so nothing here renews anything. Calls block; the repository
 * runs them off the main thread.
 */
class HomeAssistantApi(
    private val context: Context,
    private val client: OkHttpClient,
) {

    /**
     * Asks the box who it is. This is what a save is checked against, so a wrong
     * address or a token Home Assistant has forgotten is refused at the door
     * rather than the first time a light is tapped.
     */
    fun check(address: String, token: String) {
        request(address, token, "/api/").get().build().okText()
    }

    /** Every light and switch the box has, in the order it sent them. */
    fun lights(address: String, token: String): List<Light> =
        lightsIn(request(address, token, "/api/states").get().build().okText())

    /**
     * Whether that light is on, or null when Home Assistant has no such light: an
     * entity deleted on the box is not the same answer as one that is off.
     */
    fun isOn(address: String, token: String, entity: String): Boolean? {
        val request = request(address, token, "/api/states/$entity").get().build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw response.toException()
            val state = runCatching {
                HaJson.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject
            }.getOrNull()
            state?.get("state")?.jsonPrimitive?.contentOrNull?.let { it == "on" }
        }
    }

    /**
     * Turns one light or switch on or off. Home Assistant takes either as an action
     * call with the entity it applies to, and answers at once: the light itself
     * catches up.
     *
     * The action is the entity's own domain, because a Reolink lamp and plenty of
     * other things arrive as a `switch`, and `light/turn_on` for one of those is a
     * call the box turns down.
     */
    fun turn(address: String, token: String, entity: String, on: Boolean) {
        val body = lightCallBody(entity).toRequestBody(JSON)
        val domain = entity.substringBefore('.')
        val path = "/api/services/$domain/" + if (on) "turn_on" else "turn_off"
        request(address, token, path).post(body).build().okText()
    }

    private fun request(address: String, token: String, path: String): Request.Builder {
        val url = (address.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw HomeAssistantException(context.words(R.string.ha_error_address))
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
    }

    private fun Request.okText(): String {
        client.newCall(this).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.body?.string().orEmpty()
        }
    }

    /**
     * What Home Assistant says when it turns the call down. Its own words are passed
     * on where it gave any: a service call it refused names the reason, and that is
     * more use than a sentence saying it didn't answer.
     */
    private fun okhttp3.Response.toException(): HomeAssistantException {
        val said = runCatching {
            (HaJson.parseToJsonElement(body?.string().orEmpty()) as? JsonObject)
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val text = when {
            code == 401 || code == 403 -> context.words(R.string.ha_token_wrong)
            said != null -> said
            else -> context.words(R.string.ha_no_answer)
        }
        return HomeAssistantException(text)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private val HaJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * The action data Home Assistant expects: one object naming the entity.
 *
 * Not a list of them, which is how a service call can be written elsewhere. The
 * REST endpoint reads the body as the action's own data and turns a list down with
 * a 400, which arrives as the light silently refusing to come on.
 */
internal fun lightCallBody(entity: String): String =
    buildJsonObject { put("entity_id", entity) }.toString()

/**
 * The lights and switches in an `/api/states` answer: everything whose entity id
 * is one of those two, named by its friendly name where Home Assistant gave it one.
 *
 * A floodlight arrives as a `light` from Tapo's own integration and as a `switch`
 * from Reolink and plenty of others, and a lamp is a lamp either way, so both are
 * offered. What kind it is travels with it, because the action to turn it over is
 * named after it.
 *
 * The whole house's entities arrive in this one answer, which is a lot of JSON for
 * the handful in it, so the rest is dropped as it is read.
 */
internal fun lightsIn(states: String): List<Light> =
    runCatching {
        (HaJson.parseToJsonElement(states) as? JsonArray).orEmpty().mapNotNull { element ->
            val state = element as? JsonObject ?: return@mapNotNull null
            val id = state["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!id.startsWith("light.") && !id.startsWith("switch.")) return@mapNotNull null
            val attributes = state["attributes"] as? JsonObject
            val named = attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
            Light(id = id, name = named?.takeIf { it.isNotBlank() } ?: lightName(id))
        }
    }.getOrDefault(emptyList())

/**
 * What to call a light Home Assistant gave no name for: `light.door_cam_floodlight`
 * reads as "Door cam floodlight".
 */
internal fun lightName(entity: String): String {
    val bare = entity.substringAfter('.')
        .replace('_', ' ')
        .trim()
    if (bare.isEmpty()) return entity
    return bare.replaceFirstChar { it.uppercaseChar() }
}

/**
 * The name to show in a list: Home Assistant's own, and the kind of entity it is
 * when two of them share a name and the name alone would read as two of the same.
 */
internal fun Light.label(among: List<Light>): String =
    if (among.count { it.name.equals(name, ignoreCase = true) } > 1) "$name ($domain)" else name

/**
 * The lights to offer for a camera: the one named after it first, then the rest by
 * name.
 *
 * Tapo Cameras Control makes a light called after the camera it belongs to, so the
 * one you want is usually the one the camera's own name catches. A house with a
 * dozen lights otherwise starts its list at the top of the alphabet every time.
 */
internal fun lightsForCamera(lights: List<Light>, camera: String, title: String): List<Light> {
    val wanted = listOf(camera, title).map(::loose).filter { it.isNotEmpty() }
    val own = lights
        .sortedBy { it.name.lowercase() }
        .firstOrNull { light ->
            val said = loose(light.name) + loose(light.id)
            wanted.any { it in said }
        }
    return listOfNotNull(own) + lights
        .sortedBy { it.name.lowercase() }
        .filterNot { it.id == own?.id }
}

/**
 * Takes what people type - `homeassistant.local:8123`, a full URL, or one that
 * already ends in /api - and returns a clean base, or null when it is not one.
 *
 * No scheme reads as http rather than as https: a Home Assistant is a box on the
 * home network, and it makes its own certificate only if you have set it up to.
 */
internal fun homeAssistantAddress(input: String): String? {
    var value = input.trim()
    if (value.isEmpty()) return null
    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) {
        value = "http://$value"
    }
    value = value.trimEnd('/')
    if (value.endsWith("/api", ignoreCase = true)) {
        value = value.dropLast(4).trimEnd('/')
    }
    val parsed = value.toHttpUrlOrNull() ?: return null
    return parsed.toString().trimEnd('/')
}

/** Letters and digits alone, so `door_cam`, `Door Cam` and `DoorCam` all match. */
private fun loose(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
