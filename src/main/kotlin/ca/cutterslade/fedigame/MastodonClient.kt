package ca.cutterslade.fedigame

import com.typesafe.config.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import social.bigbone.MastodonRequest
import social.bigbone.api.Pageable
import social.bigbone.api.Range
import social.bigbone.api.entity.Status
import social.bigbone.api.entity.data.Visibility
import social.bigbone.MastodonClient as BigBone

private val logger = KotlinLogging.logger {}

class MastodonClient(
  private val config: Config,
) {
  private companion object {
    fun <T> pageableFlow(
      limit: Int = 40,
      pageFunction: suspend (range: Range) -> MastodonRequest<Pageable<T>>,
    ): Flow<T> = flow {
      var range = Range(limit = limit)
      while (true) {
        val page = pageFunction(range).execute()
        if (page.part.isEmpty()) break
        emitAll(page.part.asFlow())
        range = page.nextRange(limit)
      }
    }
  }
  private val instanceName: String
    get() = config.getString("mastodon.instance-name")
  private val accessToken: String
    get() = config.getString("mastodon.access-token")
  private val client: BigBone by lazy {
    logger.debug { "Initializing MastodonClient with instance: $instanceName" }
    BigBone.Builder(instanceName)
      .accessToken(accessToken)
      .build()
  }

  fun notifications() = pageableFlow { client.notifications.getAllNotifications(range = it) }
  suspend fun dismissNotification(id: String) = client.notifications.dismissNotification(id)
  suspend fun unlistedPost(body: String, inReplyTo: String): Status =
    client.statuses.postStatus(
      body,
      visibility = Visibility.UNLISTED,
      inReplyToId = inReplyTo
    ).execute()
}
