package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import ca.cutterslade.fedigame.mastodon.MastodonClient
import ca.cutterslade.fedigame.mastodon.MastodonClientProblem
import ca.cutterslade.fedigame.mastodon.Notification
import ca.cutterslade.fedigame.mastodon.Status
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.Player
import com.typesafe.config.Config
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import social.bigbone.api.exception.BigBoneRequestException

class MastodonBot(
  private val config: Config,
  private val gameEngine: GameEngine,
  private val client: MastodonClient,
) {
  private val logger = KotlinLogging.logger {}

  private val postSuffix: String
    get() = config.getString("mastodon.post-suffix")

  suspend fun handleMentions(): Int = client.notificationFlow()
    .mapNotNull { flowElement ->
      when (flowElement) {
        is Either.Left -> null.also {
          logger.warn(flowElement.value.cause) { "Error from notification flow: ${flowElement.value}" }
        }

        is Either.Right -> flowElement.value
      }
    }
    .onEach { notification ->
      either { handleMention(notification) }
        .onLeft { logger.warn(it.cause) { "Error responding to status: $it" } }
    }
    .onEach { notification ->
      client.dismissNotification(notification.id)
        .onLeft { logger.warn(it.cause) { "Error dismissing notification: $it"} }
    }
    .onEach { logger.debug { "Handled a mention: ${it.status.content.take(50)}..." } }
    .count()

  private suspend fun Raise<MastodonClientProblem>.handleMention(notification: Notification) {
    logger.debug { "Handling mention notification with ID: ${notification.id}" }

    try {
      val response = generateMentionResponse(notification.status)
      when (response) {
        is Either.Right -> respondToStatus(notification.status, response.value)
        is Either.Left -> respondToProblem(notification.status, response.value)
      }
      logger.debug { "Successfully responded to mention from ${notification.status.account.qualifiedName}" }
    } catch (e: Exception) {
      logger.error(e) { "Failed to respond to mention: ${e.message}" }
    }
  }

  private suspend fun generateMentionResponse(status: Status): Either<Game.Problem, GameResponse> = either {
    gameEngine.processMention(status.toInteraction().bind()).bind()
  }

  private fun Status.toInteraction(): Either<Game.Problem, GameInteraction> = either {
    GameInteraction(id, Player.Remote(account.id), content, inReplyToId)
  }

  private suspend fun Raise<MastodonClientProblem>.respondToStatus(status: Status, response: GameResponse) {
    val reply = client.unlistedPost(responseMessage(status, response.body), status.id).bind()
    response.idCallback(reply.id)
    logger.debug { "Successfully responded to status with ID: ${reply.id}" }
  }

  private suspend fun Raise<MastodonClientProblem>.respondToProblem(status: Status, problem: Game.Problem) {
    val reply = client.unlistedPost(responseMessage(status, problem.message), status.id).bind()
    logger.debug { "Successfully responded to status with ID: ${reply.id}" }
  }

  private fun responseMessage(status: Status, body: String): String {
    val username = status.account.qualifiedName
    logger.debug { "Building response message for $username with body: $body" }
    return postSuffix.takeIf { it.isNotBlank() }
      ?.let { "@$username $body\n\n$it" }
      ?: "@$username $body"
  }

  class MastodonBotException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
