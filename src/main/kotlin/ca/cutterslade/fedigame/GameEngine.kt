package ca.cutterslade.fedigame

import java.util.concurrent.ConcurrentHashMap
import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.getOrElse
import arrow.core.raise.either
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.GameMoveResult
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class GameSession(
  val game: Game,
  val state: GameState,
) {
  fun update(state: GameState) = copy(state = state)
}

data class GameResponse(
  val body: String,
  val idCallback: (String) -> Unit,
) {
  companion object {
    fun noProgress(body: String) = GameResponse(body) {}
  }
}

fun Game.Problem.response() = GameResponse.noProgress(message)

data class GameInteraction(
  val interactionId: String,
  val player: Player.Remote,
  val content: String,
  val inReplyToId: String?,
)

interface GameSessionStore {
  fun get(threadId: String): GameSession?
  fun put(threadId: String, session: GameSession)
}

class InMemoryGameSessionStore : GameSessionStore {
  private val store = ConcurrentHashMap<String, List<Pair<String, GameSession>>>()
  private val index = ConcurrentHashMap<String, GameSession>()
  override fun get(threadId: String): GameSession? = index[threadId]

  override fun put(threadId: String, session: GameSession) {
    store.compute(session.state.sessionId) { _, existing ->
      existing?.let { it + (threadId to session) } ?: listOf(threadId to session)
    }
    index[threadId] = session
  }
}

class GameEngine(
  private val games: NonEmptyList<Game>,
  private val gameSessionStore: GameSessionStore,
) {

  private companion object {
    private data class NewGameCommand(val gameId: String, val params: String?)

    val NewGameRegex = """(?i)play\s+(\w+)(?:\s+(.+))?""".toRegex()
  }

  private val gameListResponse = GameResponse.noProgress(
    """
        Available games:
        ${games.joinToString("\n") { "- ${it.gameName} (play ${it.gameId})" }}

        To start a game, reply with 'play <gameId>'.
    """.trimIndent()
  )

  fun getAvailableGames(): NonEmptyList<Game> {
    return games
  }

  private fun gameById(gameId: String): Game? = games.singleOrNull { it.gameId == gameId }

  suspend fun processMention(interaction: GameInteraction): Either<Game.Problem, GameResponse> = either {

    logger.debug { "Processing interaction: $interaction" }

    interaction.inReplyToId
      ?.let { getGameSession(it) }
      ?.let { gameSession -> processGameMove(gameSession, interaction).bind() }
      ?: startNewGame(interaction).getOrElse { gameListResponse }
  }

  private fun getGameSession(inReplyTo: String): GameSession? =
    gameSessionStore.get(inReplyTo)

  private suspend fun startNewGame(interaction: GameInteraction): Either<Game.Problem, GameResponse> = either {
    val command = parseNewGameCommand(interaction.content).bind()
    val game = gameById(command.gameId) ?: raise(Game.CommonProblem("No game with ID '${command.gameId}'"))
    startNewGame(game, command.params, interaction).bind()
  }


  private fun parseNewGameCommand(content: String): Either<Game.Problem, NewGameCommand> = either {
    val match = NewGameRegex.find(content) ?: raise(Game.CommonProblem("Unparsable new game command '$content'"))
    NewGameCommand(match.groupValues[1].lowercase(), match.groupValues.getOrNull(2))
  }

  private suspend fun startNewGame(
    game: Game,
    params: String?,
    interaction: GameInteraction,
  ): Either<Game.Problem, GameResponse> = either {
    logger.info { "Starting new game: ${game.gameName} for player ${interaction.player} in thread ${interaction.interactionId}" }
    val initialState = game.createInitialState(interaction.interactionId, interaction.player, params).bind()
    val gameSession = GameSession(game, initialState)
    GameResponse("Starting a new game of ${game.gameName}!\n\n${game.generateResponse(initialState)}") {
      gameSessionStore.put(it, gameSession)
    }
  }

  private suspend fun processGameMove(
    gameSession: GameSession,
    interaction: GameInteraction,
  ): Either<Game.Problem, GameResponse> = either {
    val game = gameSession.game

    logger.debug { "Processing move for game ${game.gameName} in thread ${gameSession.state.sessionId}" }

    gameAlreadyFinishedMessage(game, gameSession)
      ?.also { body -> return@either GameResponse(body) { gameSessionStore.put(it, gameSession) } }

    val moveResult = game.processMove(gameSession.state, interaction.player, interaction.content)
    var stateAfterPlayer = when (moveResult) {
      is Either.Left -> return@either GameResponse(moveResult.value.message) { gameSessionStore.put(it, gameSession) }
      is Either.Right -> moveResult.value
    }

    gameFinishedAfterPlayersMoveMessage(game, stateAfterPlayer)
      ?.also { body ->
        return@either GameResponse(body) { gameSessionStore.put(it, gameSession.update(stateAfterPlayer)) }
      }

    while (game.isBotTurn(stateAfterPlayer)) {
      val botMove = game.generateBotMove(stateAfterPlayer).bind()
      stateAfterPlayer = game.processMove(stateAfterPlayer, Player.HostBot, botMove).bind()
      gameFinishedAfterPlayersMoveMessage(game, stateAfterPlayer)
        ?.also { body ->
          return@either GameResponse(body) { gameSessionStore.put(it, gameSession.update(stateAfterPlayer)) }
        }
      if (logger.isDebugEnabled) {
        val message = "Bot move: $botMove\n\n${game.generateResponse(stateAfterPlayer)}"
        logger.debug { message }
      }
    }

    GameResponse(game.generateResponse(stateAfterPlayer)) {
      gameSessionStore.put(it, gameSession.update(stateAfterPlayer))
    }
  }

  private suspend fun gameFinishedAfterPlayersMoveMessage(
    game: Game,
    stateAfterPlayer: GameState,
  ): String? = when (val result = game.gameMoveResult(stateAfterPlayer)) {
    is GameMoveResult.Win -> {
      val response = game.generateResponse(stateAfterPlayer)
      "Game over! Player ${result.winner.mention} won!\n\n$response"
    }

    GameMoveResult.Draw -> {
      val response = game.generateResponse(stateAfterPlayer)
      "Game over! It's a draw!\n\n$response"
    }

    GameMoveResult.Abandon -> "The game was abandoned. No further moves will be accepted."
    GameMoveResult.Continue -> null
  }

  private suspend fun gameAlreadyFinishedMessage(
    game: Game,
    gameSession: GameSession,
  ): String? = when (val result = game.gameMoveResult(gameSession.state)) {
    is GameMoveResult.Win -> "The game is over. Player ${result.winner.mention} won!"
    GameMoveResult.Draw -> "The game is over. It was a draw!"
    GameMoveResult.Abandon -> "The game was abandoned. No further moves will be accepted."
    GameMoveResult.Continue -> null
  }
}
