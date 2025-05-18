package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.raise.either
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.Player
import com.typesafe.config.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import social.bigbone.MastodonClient
import social.bigbone.MastodonRequest
import social.bigbone.api.Pageable
import social.bigbone.api.Range
import social.bigbone.api.entity.Notification
import social.bigbone.api.entity.NotificationType
import social.bigbone.api.entity.Status
import social.bigbone.api.entity.data.Visibility
import social.bigbone.api.exception.BigBoneRequestException

class MastodonBot(
  private val config: Config,
  private val gameEngine: GameEngine,
) {
  private val logger = KotlinLogging.logger {}

  private val instanceName: String
    get() = config.getString("mastodon.instance-name")
  private val accessToken: String
    get() = config.getString("mastodon.access-token")
  private val postSuffix: String
    get() = config.getString("mastodon.post-suffix")

  private val client: MastodonClient by lazy {
    logger.debug { "Initializing MastodonClient with instance: $instanceName" }
    MastodonClient.Builder(instanceName)
      .accessToken(accessToken)
      .build()
  }

  suspend fun handleMentions(limit: Int = 40): Int {
    logger.debug { "Fetching mentions with limit: $limit" }
    try {
      return pageableFlow {
        client.notifications.getAllNotifications(listOf(NotificationType.MENTION), range = it)
      }
        .onEach { handleMention(it) }
        .onEach { client.notifications.dismissNotification(it.id) }
        .onEach { logger.debug { "Handled a mention: ${it.status?.content?.take(50)}..." } }
        .count()
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to get mentions: ${e.message}" }
      throw MastodonBotException("Failed to get mentions: ${e.message}", e)
    }
  }

  suspend fun handleMention(notification: Notification) {
    logger.debug { "Handling mention notification with ID: ${notification.id}" }
    val status = notification.status ?: run {
      logger.warn { "Notification ${notification.id} has no status" }
      return
    }

    try {
      val response = generateMentionResponse(status)
      when (response) {
        is Either.Right -> respondToStatus(status, response.value)
        is Either.Left -> respondToProblem(status, response.value)
      }
      logger.debug { "Successfully responded to mention from ${status.account?.acct}" }
    } catch (e: Exception) {
      logger.error(e) { "Failed to respond to mention: ${e.message}" }
    }
  }

  suspend fun generateMentionResponse(status: Status): Either<Game.Problem, GameResponse> = either {
    gameEngine.processMention(status.toInteraction().bind()).bind()
  }

  private fun Status.toInteraction(): Either<Game.Problem, GameInteraction> = either {
    account?.let { GameInteraction(id, Player.Remote(it.id), content, inReplyToId) }
      ?: raise(Game.CommonProblem("Status has no account: $this"))
  }

  suspend fun respondToStatus(status: Status, response: GameResponse) {
    val username = status.account?.acct ?: throw MastodonBotException("Status has no account")
    logger.debug { "Responding to status from $username with response body: ${response.body}" }
    try {
      val reply = unlistedPost(responseMessage(status, response.body), status.id)
      response.idCallback(reply.id)
      logger.debug { "Successfully responded to status with ID: ${reply.id}" }
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to respond to status: ${e.message}" }
      throw MastodonBotException("Failed to respond to status: ${e.message}", e)
    }
  }

  suspend fun respondToProblem(status: Status, problem: Game.Problem) {
    val username = status.account?.acct ?: throw MastodonBotException("Status has no account")
    logger.debug { "Responding to status from $username which caused a problem: $problem" }
    try {
      val reply = unlistedPost(responseMessage(status, problem.message), status.id)
      logger.debug { "Successfully responded to status with ID: ${reply.id}" }
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to respond to status: ${e.message}" }
      throw MastodonBotException("Failed to respond to status: ${e.message}", e)
    }
  }

  fun responseMessage(status: Status, body: String): String {
    val username = status.account?.acct ?: throw MastodonBotException("Status has no account")
    return postSuffix.takeIf { it.isNotBlank() }
      ?.let { "@$username $body\n\n$it" }
      ?: "@$username $body"
  }

  suspend fun unlistedPost(body: String, inReplyTo: String): Status =
    client.statuses.postStatus(
      body,
      visibility = Visibility.UNLISTED,
      inReplyToId = inReplyTo
    ).execute()

  class MastodonBotException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

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
