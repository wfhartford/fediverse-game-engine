package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import ca.cutterslade.fedigame.game.guess.NumberGuessingGame
import ca.cutterslade.fedigame.game.tictactoe.TicTacToeGame
import ca.cutterslade.fedigame.spi.Game

suspend fun main() {
  val engine = GameEngine(nonEmptyListOf(NumberGuessingGame(), TicTacToeGame()), InMemoryGameSessionStore())

  println("Enter a command (play <game id> or quit):")
  engine.getAvailableGames().forEach { game -> println(" - ${game.gameId}: ${game.gameName}") }
  val gameResult = playGame(engine)
  println("Gameplay finished: $gameResult")
}

suspend fun playGame(engine: GameEngine) = either {
  val line = readLine() ?: raise(Game.CommonProblem("EOF"))
  val response = engine.firstRequest(line).bind()
  gameLoop(response).bind()
}

tailrec suspend fun gameLoop(response: HarnessResponse): Either<Game.Problem, Nothing> {
  println(response.body)
  val line = readLine() ?: return Game.CommonProblem("EOF").left()
  val nextResponse = response.request(line).getOrElse { return it.left() }
  return gameLoop(nextResponse)
}
