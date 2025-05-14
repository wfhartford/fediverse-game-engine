package ca.cutterslade.fedigame.game.guess

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.random.Random
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.GameMoveResult
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/** The state of a number guessing game. */
data class NumberGuessingGameState(
  override val sessionId: String,
  val player: Player.Remote,
  override val status: GameState.Status,
  val targetNumber: Int,
  val attempts: Int,
  val lastGuess: Int? = null,
) : GameState

@OptIn(ExperimentalContracts::class)
private fun GameState.mine(): NumberGuessingGameState {
  contract {
    returns() implies (this@mine is NumberGuessingGameState)
  }
  return this as? NumberGuessingGameState ?: throw IllegalStateException("Invalid game state type")
}

/**
 * A simple sample game implementation to demonstrate the game engine. This
 * is a very basic number guessing game where the player tries to guess a
 * number between 1 and 10.
 */
class NumberGuessingGame : Game {
  override val gameId: String = "guess"
  override val gameName: String = "Number Guessing Game"

  override suspend fun createInitialState(
    gameSessionId: String,
    player: Player.Remote,
    params: String?,
  ): Either<Game.Problem, GameState> {
    val targetNumber = Random.nextInt(10) + 1 // 1-10
    return NumberGuessingGameState(
      sessionId = gameSessionId,
      player = player,
      targetNumber = targetNumber,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    ).right()
  }

  override suspend fun processMove(state: GameState, player: Player, move: String): Either<Game.Problem, GameState> =
    either {


      // Extract the guess from the move text
      val guess = ensureNotNull(extractGuess(move)) { Game.CommonProblem("Please provide a number between 1 and 10") }

      ensure(guess in 1..10) { Game.CommonProblem("Please guess a number between 1 and 10") }

      val attempts = state.mine().attempts + 1
      val correct = guess == state.targetNumber

      state.copy(
        attempts = attempts,
        lastGuess = guess,
        status = if (correct) GameState.Status.COMPLETED else GameState.Status.WAITING_FOR_PLAYER
      )
    }

  override suspend fun generateResponse(state: GameState): String {
    val lastGuess = state.mine().lastGuess
    return when {
      state.status == GameState.Status.COMPLETED -> "Congratulations! You guessed the correct number (${state.targetNumber}) in ${state.attempts} attempts!"
      lastGuess == null -> "I'm thinking of a number between 1 and 10. Can you guess what it is?"
      lastGuess < state.targetNumber -> "Your guess (${state.lastGuess}) is too low. Try again!"
      else -> "Your guess (${state.lastGuess}) is too high. Try again!"
    }
  }

  override suspend fun gameMoveResult(state: GameState): GameMoveResult {
    return if (state.mine().status == GameState.Status.COMPLETED) GameMoveResult.Win(state.player) else GameMoveResult.Continue
  }

  override suspend fun isBotTurn(state: GameState): Boolean {
    // In this game, the bot never makes moves
    return false
  }

  override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> =
    Game.CommonProblem("Bot doesn't make moves in the number guessing game").left()

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
