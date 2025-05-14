package ca.cutterslade.fedigame

import arrow.atomic.AtomicLong
import arrow.core.Either
import arrow.core.raise.either
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.Player
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class HarnessResponse(
  val engine: GameEngine,
  val responseId: String,
  val body: String,
) {
  suspend fun request(content: String, player: Player.Remote = DefaultPlayer) =
    request(engine, content, responseId, player)
}

val DefaultPlayer = Player.Remote("harness-default-player")
suspend fun GameEngine.firstRequest(
  request: String,
  player: Player.Remote = DefaultPlayer,
): Either<Game.Problem, HarnessResponse> = request(this, request, null, player)

private val requestCounter = AtomicLong()
private fun requestId(): String = "harness-request-id-${requestCounter.getAndIncrement()}"
private val responseCounter = AtomicLong()
private fun responseId(): String = "harness-response-id-${responseCounter.getAndIncrement()}"
private suspend fun request(
  engine: GameEngine,
  request: String,
  inReplyTo: String?,
  player: Player.Remote,
): Either<Game.Problem, HarnessResponse> = either {
  val response = engine.processMention(
    GameInteraction(
      requestId(),
      player,
      request,
      inReplyTo
    )
  ).bind()
  val id = responseId()
  logger.debug { "$player sent '$request' in reply to $inReplyTo and received $id: '${response.body}'" }
  response.idCallback(id)
  HarnessResponse(engine, id, response.body)
}
