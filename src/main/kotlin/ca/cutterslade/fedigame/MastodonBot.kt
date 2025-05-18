package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.raise.either
import ca.cutterslade.fedigame.mastodon.MastodonClient
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.Player
import com.typesafe.config.Config
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.onEach
import mu.KotlinLogging
import social.bigbone.api.entity.Notification
import social.bigbone.api.entity.Status
import social.bigbone.api.exception.BigBoneRequestException

class MastodonBot(
  private val config: Config,
  private val gameEngine: GameEngine,
  private val client: MastodonClient,
) {
  private val logger = KotlinLogging.logger {}

  private val postSuffix: String
    get() = config.getString("mastodon.post-suffix")

  suspend fun handleMentions(): Int {
    try {
      return client.notificationFlow()
        .onEach { handleMention(it) }
        .onEach { client.dismissNotification(it.id) }
        .onEach { logger.debug { "Handled a mention: ${it.status?.content?.take(50)}..." } }
        .count()
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to get mentions: ${e.message}" }
      throw MastodonBotException("Failed to get mentions: ${e.message}", e)
    }
  }

  private suspend fun handleMention(notification: Notification) {
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

  private suspend fun generateMentionResponse(status: Status): Either<Game.Problem, GameResponse> = either {
    gameEngine.processMention(status.toInteraction().bind()).bind()
  }

  private fun Status.toInteraction(): Either<Game.Problem, GameInteraction> = either {
    account?.let { GameInteraction(id, Player.Remote(it.id), content, inReplyToId) }
      ?: raise(Game.CommonProblem("Status has no account: $this"))
  }

  private suspend fun respondToStatus(status: Status, response: GameResponse) {
    try {
      val reply = client.unlistedPost(responseMessage(status, response.body), status.id)
      response.idCallback(reply.id)
      logger.debug { "Successfully responded to status with ID: ${reply.id}" }
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to respond to status: ${e.message}" }
      throw MastodonBotException("Failed to respond to status: ${e.message}", e)
    }
  }

  private suspend fun respondToProblem(status: Status, problem: Game.Problem) {
    try {
      val reply = client.unlistedPost(responseMessage(status, problem.message), status.id)
      logger.debug { "Successfully responded to status with ID: ${reply.id}" }
    } catch (e: BigBoneRequestException) {
      logger.error(e) { "Failed to respond to status: ${e.message}" }
      throw MastodonBotException("Failed to respond to status: ${e.message}", e)
    }
  }

  private fun responseMessage(status: Status, body: String): String {
    val username = status.account?.acct ?: throw MastodonBotException("Status has no account")
    logger.debug { "Building response message for $username with body: $body" }
    return postSuffix.takeIf { it.isNotBlank() }
      ?.let { "@$username $body\n\n$it" }
      ?: "@$username $body"
  }

  class MastodonBotException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
