package ca.cutterslade.fedigame.spi

import arrow.core.Either

/**
 * Interface representing a turn-based game. Implementations of this
 * interface define the rules and logic for specific games.
 */
interface Game {
  interface Problem {
    val message: String
  }

  data class CommonProblem(override val message: String) : Problem

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
  suspend fun createInitialState(
    gameSessionId: String,
    player: Player.Remote,
    params: String?,
  ): Either<Problem, GameState>

  /**
   * Process a player's move and update the game state.
   *
   * @param state The current game state
   * @param player The identifier of the player making the move
   * @param move The move as a string
   * @return The updated game state
   */
  suspend fun processMove(state: GameState, player: Player, move: String): Either<Problem, GameState>

  /**
   * Generate a response to the player based on the current game state.
   *
   * @param state The current game state
   * @return A string response to send to the player
   */
  suspend fun generateResponse(state: GameState): String

  /**
   * Get the result of the latest game move.
   *
   * @param state The current game state
   * @return The final result of the game, or null if the game is not yet
   *    finished
   */
  suspend fun gameMoveResult(state: GameState): GameMoveResult

  /**
   * Check if it's the bot's turn to make a move.
   *
   * @param state The current game state
   * @return True if it's the bot's turn, false otherwise
   */
  suspend fun isBotTurn(state: GameState): Boolean

  /**
   * Generate a move for the bot.
   *
   * @param state The current game state
   * @return The bot's move as a string
   */
  suspend fun generateBotMove(state: GameState): Either<Problem, String>
}
