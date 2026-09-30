package uk.co.softwarecrafts.contextlauncher.data.todoist

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uk.co.softwarecrafts.contextlauncher.core.todoist.CommandDto
import uk.co.softwarecrafts.contextlauncher.core.todoist.CompletedResponse
import uk.co.softwarecrafts.contextlauncher.core.todoist.ItemDto
import uk.co.softwarecrafts.contextlauncher.core.todoist.SyncResponse
import uk.co.softwarecrafts.contextlauncher.core.todoist.TodoistJson
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class TodoistApiException(val code: Int, message: String) : IOException("Todoist $code: $message")

/** Thin HTTP layer over the Todoist unified API v1. No state; the repository owns tokens and caching. */
class TodoistApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.todoist.com/api/v1/",
) {

    /** Incremental sync; pass "*" as [syncToken] for a full sync. [commands] may be empty. */
    suspend fun sync(token: String, syncToken: String, commands: List<CommandDto> = emptyList()): SyncResponse =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("sync_token", syncToken)
                .add("resource_types", TodoistJson.encodeStrings(listOf("items", "labels")))
            if (commands.isNotEmpty()) form.add("commands", TodoistJson.encodeCommands(commands))
            val request = Request.Builder()
                .url(baseUrl + "sync")
                .header("Authorization", "Bearer $token")
                .post(form.build())
                .build()
            TodoistJson.decodeSync(execute(request))
        }

    /** Tasks completed between two instants (the sync endpoint omits completed items on a full sync). */
    suspend fun completedBetween(token: String, since: Instant, until: Instant): List<ItemDto> =
        withContext(Dispatchers.IO) {
            val all = mutableListOf<ItemDto>()
            var cursor: String? = null
            do {
                val url = (baseUrl + "tasks/completed/by_completion_date").toHttpUrl().newBuilder()
                    .addQueryParameter("since", since.toString().substring(0, 19))
                    .addQueryParameter("until", until.toString().substring(0, 19))
                    .addQueryParameter("limit", "200")
                    .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
                    .build()
                val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
                val page: CompletedResponse = TodoistJson.decodeCompleted(execute(request))
                all += page.items
                cursor = page.nextCursor
            } while (cursor != null)
            all
        }

    /** Creates a personal label. */
    suspend fun addLabel(token: String, name: String) = withContext(Dispatchers.IO) {
        val body = """{"name":${TodoistJson.json.encodeToString(String.serializer(), name)}}"""
        val request = Request.Builder()
            .url(baseUrl + "labels")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request)
        Unit
    }

    /** Natural-language task creation, the same parser as the apps' quick add. */
    suspend fun quickAdd(token: String, text: String): ItemDto = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + "tasks/quick")
            .header("Authorization", "Bearer $token")
            .post(FormBody.Builder().add("text", text).build())
            .build()
        TodoistJson.decodeItem(execute(request))
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw TodoistApiException(response.code, body.take(300))
            return body
        }
    }
}
