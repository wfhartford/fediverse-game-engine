package ca.cutterslade.fedigame

import java.util.concurrent.ConcurrentHashMap
import arrow.core.NonEmptyList
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Interface representing a turn-based game. Implementations of this
 * interface define the rules and logic for specific games.
 */
interface Game {
  /** The unique identifier for this game type. */
  val gameId: String

  /** A human-readable name for this game. */
  val gameName: String

  /**
   * Create a new game state for a new game.
   *
   * @param gameSessionId The unique identifier for this game session
   * @param player The identifier of the player who started the game
   * @return A new game state
   */
  fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): GameState

  /**
   * Process a player's move and update the game state.
   *
   * @param state The current game state
   * @param player The identifier of the player making the move
   * @param move The move as a string
   * @return The updated game state
   */
  fun processMove(state: GameState, player: Player, move: String): GameState

  /**
   * Generate a response to the player based on the current game state.
   *
   * @param state The current game state
   * @return A string response to send to the player
   */
  fun generateResponse(state: GameState): String

  /**
   * Get the result of the latest game move.
   *
   * @param state The current game state
   * @return The final result of the game, or null if the game is not yet
   *    finished
   */
  fun gameMoveResult(state: GameState): GameMoveResult

  /**
   * Check if it's the bot's turn to make a move.
   *
   * @param state The current game state
   * @return True if it's the bot's turn, false otherwise
   */
  fun isBotTurn(state: GameState): Boolean

  /**
   * Generate a move for the bot.
   *
   * @param state The current game state
   * @return The bot's move as a string
   */
  fun generateBotMove(state: GameState): String
}

interface GameSessionStore {
  fun get(threadId: String): GameSession?
  fun put(threadId: String, state: GameSession)
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

sealed class GameMoveResult {
  data class Win(val winner: Player) : GameMoveResult()
  data object Draw : GameMoveResult()
  data object Abandon : GameMoveResult()
  data object Continue : GameMoveResult()
}

sealed class Player {
  data class Remote(val id: String) : Player()
  data object HostBot : Player()
}

/**
 * Interface representing the state of a game. This is a marker interface
 * that game implementations will extend with game-specific state.
 */
interface GameState {
  /** Enum representing the status of a game. */
  enum class Status {
    WAITING_FOR_PLAYER,
    WAITING_FOR_BOT,
    COMPLETED,
    ABANDONED
  }

  /** The unique identifier for this game session. */
  val sessionId: String

  /** The identifier of the player who started the game. */
  val player: Player

  /** The current status of the game (in progress, completed, etc.). */
  val status: Status
}

/**
 * Class representing a game session. A game session tracks the state of a
 * game and the conversation thread.
 */
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

data class GameInteraction(
  val interactionId: String,
  val player: Player.Remote,
  val content: String,
  val inReplyToId: String?,
)

/**
 * The game engine manages game sessions and integrates with the
 * MastodonBot.
 */
class GameEngine(
  private val games: NonEmptyList<Game>,
  private val gameSessionStore: GameSessionStore,
) {
  /**
   * Get a list of available games.
   *
   * @return A list of available games
   */
  fun getAvailableGames(): Collection<Game> {
    return games
  }

  private fun gameById(gameId: String): Game? = games.singleOrNull { it.gameId == gameId }

  /**
   * Process a mention and determine if it's a game-related command.
   *
   * @param status The status containing the mention
   * @return A response to the mention, or null if it's not a game-related
   *    command
   */
  fun processMention(interaction: GameInteraction): GameResponse? {

    logger.debug { "Processing interaction: $interaction" }

    // Check if this is part of an existing game session
    return interaction.inReplyToId
      ?.let { getGameSession(it) }
      ?.let { gameSession -> processGameMove(gameSession, interaction) }
      ?: startNewGame(interaction)
  }

  private fun getGameSession(inReplyTo: String): GameSession? =
    gameSessionStore.get(inReplyTo)

  private fun startNewGame(interaction: GameInteraction): GameResponse? =
    parseNewGameCommand(interaction.content)
      ?.let { (gameId, params) ->
        gameById(gameId)?.let {
          startNewGame(it, params, interaction)
        }
      }

  /**
   * Parse a game command from a status content.
   *
   * @param content The content of the status
   * @return A pair of game ID and optional parameters, or null if not a game
   *    command
   */
  private fun parseNewGameCommand(content: String): Pair<String, String?>? {
    val regex = """(?i)play\s+(\w+)(?:\s+(.+))?""".toRegex()
    val match = regex.find(content) ?: return null

    val gameId = match.groupValues[1].lowercase()
    val params = match.groupValues.getOrNull(2)

    return gameId to params
  }

  /**
   * Start a new game session.
   *
   * @param game The game to start
   * @param gameSessionId The identifier of the thread
   * @param statusId The identifier of the status
   * @param playerId The identifier of the player starting the game
   * @return A response to send to the player
   */
  private fun startNewGame(
    game: Game,
    params: String?,
    interaction: GameInteraction,
  ): GameResponse {
    logger.info { "Starting new game: ${game.gameName} for player ${interaction.player} in thread ${interaction.interactionId}" }
    val initialState = game.createInitialState(interaction.interactionId, interaction.player, params)
    val gameSession = GameSession(game, initialState)
    return GameResponse("Starting a new game of ${game.gameName}!\n\n${game.generateResponse(initialState)}") {
      gameSessionStore.put(it, gameSession)
    }
  }

  /*
   * XXX ThreadID is the ID of the status that the post is in response to.
   */
  /**
   * Process a move in an existing game session.
   *
   * @param gameSession The game session
   * @param content The content of the status containing the move
   * @param playerId The identifier of the player making the move
   * @param statusId The identifier of the status
   * @return A response to send to the player
   */
  private fun processGameMove(gameSession: GameSession, interaction: GameInteraction): GameResponse {
    val game = gameSession.game

    logger.debug { "Processing move for game ${game.gameName} in thread ${gameSession.state.sessionId}" }

    when (val result = game.gameMoveResult(gameSession.state)) {
      is GameMoveResult.Win -> "The game is over. Player ${result.winner} won!"
      GameMoveResult.Draw -> "The game is over. It was a draw!"
      GameMoveResult.Abandon -> "The game was abandoned. No further moves will be accepted."
      GameMoveResult.Continue -> null
    }?.also { body -> return GameResponse(body) { gameSessionStore.put(it, gameSession) } }

    // Process the player's move
    val stateAfterPlayer = try {
      game.processMove(gameSession.state, interaction.player, interaction.content)
    } catch (e: Exception) {
      logger.error(e) { "Error processing move: ${e.message}" }
      return GameResponse.noProgress(
        "Invalid move: ${e.message ?: "Unknown error"}\n\n${game.generateResponse(gameSession.state)}"
      )
    }

    when (val result = game.gameMoveResult(stateAfterPlayer)) {
      is GameMoveResult.Win -> {
        val response = game.generateResponse(stateAfterPlayer)
        "Game over! Player ${result.winner} won!\n\n$response"
      }

      GameMoveResult.Draw -> {
        val response = game.generateResponse(stateAfterPlayer)
        "Game over! It's a draw!\n\n$response"
      }

      GameMoveResult.Abandon -> "The game was abandoned. No further moves will be accepted."
      GameMoveResult.Continue -> null
    }?.also { body -> return GameResponse(body) { gameSessionStore.put(it, gameSession.update(stateAfterPlayer)) } }

    if (!game.isBotTurn(stateAfterPlayer)) {
      return GameResponse(game.generateResponse(stateAfterPlayer)) {
        gameSessionStore.put(it, gameSession.update(stateAfterPlayer))
      }
    }

    // If it's the bot's turn, make a move
    val botMove = game.generateBotMove(stateAfterPlayer)
    val stateAfterBot = game.processMove(stateAfterPlayer, Player.HostBot, botMove)
    game.gameMoveResult(stateAfterBot).let {
      when (it) {
        is GameMoveResult.Win -> {
          val response = game.generateResponse(stateAfterBot)
          "I made my move: $botMove\n\nGame over! Player ${it.winner} won!\n\n$response"
        }

        GameMoveResult.Draw -> {
          val response = game.generateResponse(stateAfterBot)
          "I made my move: $botMove\n\nGame over! It's a draw!\n\n$response"
        }

        GameMoveResult.Continue -> {
          val response = game.generateResponse(stateAfterBot)
          "I made my move: $botMove\n\n$response"
        }

        GameMoveResult.Abandon -> "The game was abandoned. No further moves will be accepted."
      }
    }.also { return GameResponse(it) { gameSessionStore.put(it, gameSession.update(stateAfterBot)) } }
  }

  /**
   * Generate a response listing available games.
   *
   * @return A response listing available games
   */
  private fun generateGameListResponse(): String =
    getAvailableGames().takeIf { it.isNotEmpty() }?.let { games ->
      val gameList = games.joinToString("\n") { "- ${it.gameName} (play ${it.gameId})" }
      "Available games:\n$gameList\n\nTo start a game, reply with 'play <gameId>'."
    } ?: "No games are currently available. Please try again later."
}
