package ca.cutterslade.fedigame

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
): HarnessResponse? = request(this, request, null, player)

private var requestCounter = 0
private fun requestId(): String = "harness-request-id-${requestCounter++}"
private var responseCounter = 0
private fun responseId(): String = "harness-response-id-${responseCounter++}"
private suspend fun request(
  engine: GameEngine,
  request: String,
  inReplyTo: String?,
  player: Player.Remote,
): HarnessResponse? {
  val response = engine.processMention(
    GameInteraction(
      requestId(),
      player,
      request,
      inReplyTo
    )
  )
  if (response == null)
    logger.debug { "$player sent '$request' in reply to $inReplyTo and received null response" }
  return response?.let {
    val id = responseId()
    logger.debug { "$player sent '$request' in reply to $inReplyTo and received $id: '${it.body}'" }
    it.idCallback(id)
    HarnessResponse(engine, id, it.body)
  }
}
