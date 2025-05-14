package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.nonEmptyListOf
import arrow.core.right
import arrow.core.left
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.GameMoveResult
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.assertions.arrow.core.shouldBeRight

class GameEngineSpec : FunSpec({
  data class MockGamesState(
    override val sessionId: String,
    override val status: GameState.Status,
    val lastMove: String,
    val parameters: String?,
    val nextPlayer: Player,
  ) : GameState

  // Create a mock game for testing
  val mockGame = object : Game {
    override val gameId: String = "mock"
    override val gameName: String = "Mock Game"

    override suspend fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): Either<Game.Problem, GameState> {
      return MockGamesState(gameSessionId, GameState.Status.WAITING_FOR_PLAYER, "", params, player).right()
    }

    override suspend fun processMove(state: GameState, player: Player, move: String): Either<Game.Problem, GameState> {
      check(state is MockGamesState) { "Invalid game state type" }
      return MockGamesState(
        state.sessionId,
        when {
          move.contains("win") -> GameState.Status.COMPLETED
          move.contains("draw") -> GameState.Status.COMPLETED
          move.contains("abandon") -> GameState.Status.ABANDONED
          move.contains("bot") -> GameState.Status.WAITING_FOR_BOT
          else -> GameState.Status.WAITING_FOR_PLAYER
        },
        move,
        (state as? MockGamesState)?.parameters,
        (state as? MockGamesState)?.nextPlayer!!
      ).right()
    }

    override suspend fun generateResponse(state: GameState): String {
      check(state is MockGamesState) { "Invalid game state type" }

      return when (state.status) {
        GameState.Status.COMPLETED -> "Game completed"
        GameState.Status.ABANDONED -> "Game abandoned"
        GameState.Status.WAITING_FOR_BOT -> "Waiting for bot"
        GameState.Status.WAITING_FOR_PLAYER if state.lastMove.contains("params") -> {
          val params = state.parameters ?: "No parameters"
          println("[DEBUG_LOG] Returning parameters: $params")
          params
        }

        GameState.Status.WAITING_FOR_PLAYER -> "Waiting for player"
        else -> "Unknown state"
      }
    }

    override suspend fun gameMoveResult(state: GameState): GameMoveResult {
      check(state is MockGamesState) { "Invalid game state type" }
      return when {
        state.lastMove.contains("draw") -> GameMoveResult.Draw
        state.lastMove.contains("win") -> GameMoveResult.Win(state.nextPlayer)
        state.lastMove.contains("abandon") -> GameMoveResult.Abandon
        else -> GameMoveResult.Continue
      }
    }

    override suspend fun isBotTurn(state: GameState): Boolean {
      return state.status == GameState.Status.WAITING_FOR_BOT
    }

    override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> {
      return "Bot move".right()
    }
  }

  // Create a test game engine with the mock game
  val gameEngine = GameEngine(
    nonEmptyListOf(mockGame),
    InMemoryGameSessionStore()
  )

  test("getAvailableGames should return the list of registered games") {
    val games = gameEngine.getAvailableGames()
    games.size shouldBe 1
    games.first().apply {
      gameId shouldBe "mock"
      gameName shouldBe "Mock Game"
    }
  }

  test("processMention should return game list message for non-game related mentions") {
    gameEngine.firstRequest("Hello, how are you?")
      .shouldBeRight().body shouldContain "To start a game"
  }

  test("processMention should start a new game when given a valid game command") {
    val response = gameEngine.firstRequest("play mock").shouldNotBeNull()
    response.shouldBeRight().body shouldContain "Starting a new game of Mock Game"
  }

  test("processMention should handle game moves in an existing game session") {
    // First start a new game
    val startResponse = gameEngine.firstRequest("play mock").shouldBeRight()

    // Now make a move in the game
    val moveResponse = startResponse.request("my move").shouldBeRight()
    moveResponse.body shouldContain "Waiting for player"
  }

  test("processMention should handle winning moves") {
    // First start a new game
    val startResponse = gameEngine.firstRequest("play mock").shouldBeRight()

    // Now make a winning move in the game
    val moveResponse = startResponse.request("win move").shouldBeRight()
    moveResponse.body shouldContain "Player ${DefaultPlayer.mention} won"
  }

  test("processMention should handle invalid game IDs") {
    gameEngine.firstRequest("play nonexistent")
      .shouldBeRight().body shouldContain "To start a game"
  }

  test("processMention should handle case-insensitive game IDs") {
    val response = gameEngine.firstRequest("play MOCK").shouldBeRight()
    response.body shouldContain "Starting a new game of Mock Game"
  }

  test("processMention should handle game with parameters") {
    val startResponse = gameEngine.firstRequest("play mock with some parameters").shouldBeRight()
    startResponse.body shouldContain "Starting a new game of Mock Game"
    println("[DEBUG_LOG] Start response body: ${startResponse.body}")

    val moveResponse = startResponse.request("params").shouldBeRight()
    println("[DEBUG_LOG] Move response body: ${moveResponse.body}")
    moveResponse.body shouldContain "with some parameters"
  }

  test("processMention should handle multiple games") {
    val anotherMockGame = object : Game {
      override val gameId: String = "another"
      override val gameName: String = "Another Mock Game"

      override suspend fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): Either<Game.Problem, GameState> {
        return object : GameState {
          override val sessionId: String = gameSessionId
          override val status: GameState.Status = GameState.Status.WAITING_FOR_PLAYER
        }.right()
      }

      override suspend fun processMove(state: GameState, player: Player, move: String): Either<Game.Problem, GameState> {
        return state.right()
      }

      override suspend fun generateResponse(state: GameState): String {
        return "Another mock game response"
      }

      override suspend fun gameMoveResult(state: GameState): GameMoveResult {
        return GameMoveResult.Continue
      }

      override suspend fun isBotTurn(state: GameState): Boolean {
        return false
      }

      override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> {
        return "Another bot move".right()
      }
    }

    val multiGameEngine = GameEngine(
      nonEmptyListOf(mockGame, anotherMockGame),
      InMemoryGameSessionStore()
    )

    multiGameEngine.getAvailableGames().size shouldBe 2

    val response = multiGameEngine.firstRequest("play another").shouldBeRight()
    response.body shouldContain "Starting a new game of Another Mock Game"
  }

  test("processMention should handle invalid moves") {
    // Create a game that throws an exception for invalid moves
    val exceptionGame = object : Game {
      override val gameId: String = "exception"
      override val gameName: String = "Exception Game"

      override suspend fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): Either<Game.Problem, GameState> {
        return object : GameState {
          override val sessionId: String = gameSessionId
          override val status: GameState.Status = GameState.Status.WAITING_FOR_PLAYER
        }.right()
      }

      override suspend fun processMove(state: GameState, player: Player, move: String): Either<Game.Problem, GameState> {
        if (move.contains("invalid")) {
          return Game.CommonProblem("Invalid move").left()
        }
        return state.right()
      }

      override suspend fun generateResponse(state: GameState): String {
        return "Exception game response"
      }

      override suspend fun gameMoveResult(state: GameState): GameMoveResult {
        return GameMoveResult.Continue
      }

      override suspend fun isBotTurn(state: GameState): Boolean {
        return false
      }

      override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> {
        return "Exception bot move".right()
      }
    }

    val exceptionGameEngine = GameEngine(
      nonEmptyListOf(exceptionGame),
      InMemoryGameSessionStore()
    )

    // First start a new game
    val startResponse = exceptionGameEngine.firstRequest("play exception").shouldBeRight()

    // Now make an invalid move
    val problem = startResponse.request("invalid move").shouldBeLeft()
    problem.message shouldBe "Invalid move"
  }
})
