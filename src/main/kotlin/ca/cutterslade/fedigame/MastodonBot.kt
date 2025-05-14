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

/**
 * A simple Mastodon bot that can authenticate and post statuses.
 *
 * This bot provides basic functionality for interacting with a Mastodon
 * instance:
 * - Posting statuses
 * - Following/unfollowing users
 * - Boosting and favoriting posts
 * - Retrieving timelines
 */
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

  /**
   * Handle and dismiss all outstanding mention notifications.
   *
   * @param limit Maximum number of statuses to check (default: 40)
   */
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

    // Respond to the mention
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

  fun gameListStatus(): String =
    gameEngine.getAvailableGames().takeIf { it.isNotEmpty() }?.let { games ->
      val gameList = games.joinToString("\n") { "- ${it.gameName} (play ${it.gameId})" }
      "Hi there! I'm a game bot. You can play the following games with me:\n$gameList\n\nTo start a game, reply with 'play <gameId>'."
    } ?: "Hi there! I'm a game bot, but no games are currently available. Please try again later."

  suspend fun respondToStatus(status: Status, response: GameResponse) {
    val username = status.account?.acct ?: throw MastodonBotException("Status has no account")
    logger.debug { "Responding to status from $username" }
    try {
      // Create a reply to the status
      val replyContent = postSuffix.takeIf { it.isNotBlank() }
        ?.let { "@$username ${response.body}\n\n$it" }
        ?: "@$username ${response.body}"
      val reply = client.statuses.postStatus(
        replyContent,
        visibility = Visibility.UNLISTED,
        inReplyToId = status.id
      ).execute()

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
      val replyContent = postSuffix.takeIf { it.isNotBlank() }
        ?.let { "@$username ${problem.message}\n\n$it" }
        ?: "@$username ${problem.message}"
      val reply = client.statuses.postStatus(
        replyContent,
        visibility = Visibility.UNLISTED,
        inReplyToId = status.id
      ).execute()
      logger.debug { "Successfully responded to status with ID: ${reply.id}" }
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to respond to status: ${e.message}" }
      throw MastodonBotException("Failed to respond to status: ${e.message}", e)
    }
  }

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
