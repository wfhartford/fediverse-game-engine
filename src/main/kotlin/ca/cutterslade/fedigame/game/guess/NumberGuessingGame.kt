package ca.cutterslade.fedigame.game.guess

import kotlin.random.Random
import ca.cutterslade.fedigame.Game
import ca.cutterslade.fedigame.GameMoveResult
import ca.cutterslade.fedigame.GameState
import ca.cutterslade.fedigame.Player
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/** The state of a number guessing game. */
data class NumberGuessingGameState(
  override val sessionId: String,
  override val player: Player.Remote,
  override val status: GameState.Status,
  val targetNumber: Int,
  val attempts: Int,
  val lastGuess: Int? = null,
) : GameState

/**
 * A simple sample game implementation to demonstrate the game engine. This
 * is a very basic number guessing game where the player tries to guess a
 * number between 1 and 10.
 */
class NumberGuessingGame : Game {
  override val gameId: String = "guess"
  override val gameName: String = "Number Guessing Game"

  override fun createInitialState(threadId: String, player: Player.Remote, params: String?): GameState {
    val targetNumber = Random.nextInt(10) + 1 // 1-10
    return NumberGuessingGameState(
      sessionId = threadId,
      player = player,
      targetNumber = targetNumber,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )
  }

  override fun processMove(state: GameState, player: Player, move: String): GameState {
    check(state is NumberGuessingGameState) { "Invalid game state type" }

    // Extract the guess from the move text
    val guess = extractGuess(move) ?: throw IllegalArgumentException("Please provide a number between 1 and 10")

    if (guess < 1 || guess > 10) {
      throw IllegalArgumentException("Please guess a number between 1 and 10")
    }

    val attempts = state.attempts + 1
    val correct = guess == state.targetNumber

    return state.copy(
      attempts = attempts,
      lastGuess = guess,
      status = if (correct) GameState.Status.COMPLETED else GameState.Status.WAITING_FOR_PLAYER
    )
  }

  override fun generateResponse(state: GameState): String {
    check(state is NumberGuessingGameState) { "Invalid game state type" }
    val lastGuess = state.lastGuess
    return when {
      state.status == GameState.Status.COMPLETED -> "Congratulations! You guessed the correct number (${state.targetNumber}) in ${state.attempts} attempts!"
      lastGuess == null -> "I'm thinking of a number between 1 and 10. Can you guess what it is?"
      lastGuess < state.targetNumber -> "Your guess (${state.lastGuess}) is too low. Try again!"
      else -> "Your guess (${state.lastGuess}) is too high. Try again!"
    }
  }

  override fun gameMoveResult(state: GameState): GameMoveResult {
    check(state is NumberGuessingGameState) { "Invalid game state type" }
    return if (state.status == GameState.Status.COMPLETED) GameMoveResult.Win(state.player) else GameMoveResult.Continue
  }

  override fun isBotTurn(state: GameState): Boolean {
    // In this game, the bot never makes moves
    return false
  }

  override fun generateBotMove(state: GameState): String {
    // Bot doesn't make moves in this game
    throw UnsupportedOperationException("Bot doesn't make moves in the number guessing game")
  }

  /**
   * Extract a number guess from the move text.
   *
   * @param move The move text
   * @return The extracted number, or null if no number was found
   */
  private fun extractGuess(move: String): Int? {
    // Look for a number in the text
    val regex = """\b(\d+)\b""".toRegex()
    val match = regex.find(move) ?: return null
    return match.groupValues[1].toIntOrNull()
  }
}
