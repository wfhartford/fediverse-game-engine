package ca.cutterslade.fedigame.mastodon

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import com.typesafe.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class KtorMastodonClient(private val config: Config) : MastodonClient {
  private val instanceName: String
    get() = config.getString("mastodon.instance-name")
  private val port: Int
    get() = config.getInt("mastodon.port")
  private val disableHttps: Boolean
    get() = config.getBoolean("mastodon.disable-https")
  private val accessToken: String
    get() = config.getString("mastodon.access-token")

  private val baseUrl: String
    get() {
      val protocol = if (disableHttps) "http" else "https"
      return "$protocol://$instanceName:$port"
    }

  private val client = HttpClient(CIO) {
    install(ContentNegotiation) { json() }
  }

  override fun notificationFlow(limit: Int): Flow<Either<MastodonClientProblem, Notification>> = flow {
    var nextUrl: String? = "$baseUrl/api/v1/notifications?limit=$limit"

    while (null != nextUrl) {
      logger.debug { "Getting notifications from $nextUrl" }
      val response = try {
        client.get(nextUrl) {
          header("Authorization", "Bearer $accessToken")
        }
      } catch (e: Exception) {
        emit(MastodonClientProblem.Exception(e).left())
        break
      }

      if (!response.status.isSuccess()) {
        emit(MastodonClientProblem.HttpStatus(nextUrl, response.status.value, response.status.description).left())
        break
      }

      val notifications = response.body<List<JsonObject>>()
      if (notifications.isEmpty()) {
        break
      }

      emitAll(notifications.map { either { parseNotification(it) } }.asFlow())

      // Extract the Link header for pagination
      nextUrl = extractNextUrl(response.headers["Link"])
    }
  }.catch { e ->
    emit(MastodonClientProblem.Exception(e).left())
  }

  private fun extractNextUrl(linkHeader: String?): String? {
    if (linkHeader == null) return null

    val nextLinkRegex = Regex("<([^>]*)>; rel=\"next\"")
    val match = nextLinkRegex.find(linkHeader)

    return match?.groupValues?.getOrNull(1)
  }

  override suspend fun dismissNotification(id: String): Either<MastodonClientProblem, Unit> = either {
    logger.debug { "Dismissing notification with ID: $id" }
    val url = "$baseUrl/api/v1/notifications/$id/dismiss"
    val response = catch(
      { client.post(url) { header("Authorization", "Bearer $accessToken") } },
      ::raiseException
    )

    response.status.takeUnless { it.isSuccess() }?.also {
      raise(MastodonClientProblem.HttpStatus(url, it.value, it.description))
    }
  }

  override suspend fun unlistedPost(
    body: String,
    inReplyTo: String,
  ): Either<MastodonClientProblem, Status> = either {
    val url = "$baseUrl/api/v1/statuses"
    logger.debug { "Posting unlisted status in reply to $inReplyTo" }

    val response = catch(
      {
        client.submitForm(
          url = url,
          formParameters = Parameters.build {
            append("status", body)
            append("visibility", "unlisted")
            append("in_reply_to_id", inReplyTo)
            append("sensitive", "false")
          }
        ) {
          header("Authorization", "Bearer $accessToken")
        }
      },
      ::raiseException
    )

    response.status.takeUnless { it.isSuccess() }?.also {
      raise(MastodonClientProblem.HttpStatus(url, it.value, it.description))
    }

    parseStatus(response.body())
  }

  override suspend fun instanceDetails(): Either<MastodonClientProblem, Instance> = either {
    logger.debug { "Getting instance details" }

    val nodeinfoUrl = fetchNodeInfoUrl()
    val softwareJson = fetchSoftwareJson(nodeinfoUrl)
    val instanceJson = fetchJsonObject("$baseUrl/api/v2/instance")

    Instance(
      domain = instanceJson["domain"]?.jsonPrimitive?.content
        ?: raise(MastodonClientProblem.CommonProblem("No domain in instance")),
      title = instanceJson["title"]?.jsonPrimitive?.content
        ?: raise(MastodonClientProblem.CommonProblem("No title in instance")),
      version = softwareJson["version"]?.jsonPrimitive?.content
        ?: raise(MastodonClientProblem.CommonProblem("No version in nodeinfo")),
      sourceUrl = instanceJson["source_url"]?.jsonPrimitive?.content
        ?: raise(MastodonClientProblem.CommonProblem("No source_url in instance")),
      description = instanceJson["description"]?.jsonPrimitive?.content
        ?: raise(MastodonClientProblem.CommonProblem("No description in instance")),
    )
  }

  private suspend fun Raise<MastodonClientProblem>.fetchNodeInfoUrl(): String {
    val nodeinfoJson = fetchJsonObject("${baseUrl}/.well-known/nodeinfo")
    val links = nodeinfoJson["links"]?.jsonArray
      ?: raise(MastodonClientProblem.CommonProblem("No links in nodeinfo"))
    val nodeinfoUrl = links.find {
      it.jsonObject["rel"]?.jsonPrimitive?.content == "http://nodeinfo.diaspora.software/ns/schema/2.0"
    }?.jsonObject?.get("href")?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("No nodeinfo URL found"))
    return nodeinfoUrl
  }

  private suspend fun Raise<MastodonClientProblem>.fetchSoftwareJson(nodeinfoUrl: String): JsonObject {
    val nodeinfoDetailsJson = fetchJsonObject(nodeinfoUrl)
    return nodeinfoDetailsJson["software"]?.jsonObject
      ?: raise(MastodonClientProblem.CommonProblem("No software in nodeinfo details"))
  }

  private suspend fun Raise<MastodonClientProblem>.fetchJsonObject(url: String): JsonObject {
    val response = catch({ client.get(url) }, ::raiseException)
    response.status.takeUnless { it.isSuccess() }
      ?.also { raise(MastodonClientProblem.HttpStatus(url, it.value, it.description)) }
    return response.body<JsonObject>()
  }

  private fun Raise<MastodonClientProblem>.parseNotification(json: JsonObject): Notification = Notification(
    json["id"]?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("Notification has no ID: $json")),
    parseStatus(
      json["status"]?.jsonObject
        ?: raise(MastodonClientProblem.CommonProblem("Notification has no status: $json"))
    )
  )

  private fun Raise<MastodonClientProblem>.parseStatus(json: JsonObject): Status = Status(
    json["id"]?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("Status has no ID: $json")),
    parseAccount(
      json["account"]?.jsonObject
        ?: raise(MastodonClientProblem.CommonProblem("Status has no account: $json"))
    ),
    json["content"]?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("Status has no content: $json")),
    json["in_reply_to_id"]?.jsonPrimitive?.content
  )

  private fun Raise<MastodonClientProblem>.parseAccount(json: JsonObject): Account = Account(
    json["id"]?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("Account has no ID: $json")),
    json["username"]?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("Account has no username: $json")),
    json["acct"]?.jsonPrimitive?.content
      ?: raise(MastodonClientProblem.CommonProblem("Account has no acct: $json"))
  )

  override fun close() {
    client.close()
  }
}

private fun Raise<MastodonClientProblem>.raiseException(e: Exception): Nothing =
  raise(MastodonClientProblem.Exception(e))
