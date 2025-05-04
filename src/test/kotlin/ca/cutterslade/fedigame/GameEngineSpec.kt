package ca.cutterslade.fedigame

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull

class GameEngineSpec : FunSpec({
  data class MockGamesState(
    override val sessionId: String,
    override val player: Player,
    override val status: GameState.Status,
    val lastMove: String,
    val parameters: String?,
  ) : GameState

  // Create a mock game for testing
  val mockGame = object : Game {
    override val gameId: String = "mock"
    override val gameName: String = "Mock Game"

    override fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): GameState {
      return MockGamesState(gameSessionId, player, GameState.Status.WAITING_FOR_PLAYER, "", params)
    }

    override fun processMove(state: GameState, player: Player, move: String): GameState {
      check(state is MockGamesState) { "Invalid game state type" }
      return MockGamesState(
        state.sessionId,
        state.player,
        when {
          move.contains("win") -> GameState.Status.COMPLETED
          move.contains("draw") -> GameState.Status.COMPLETED
          move.contains("abandon") -> GameState.Status.ABANDONED
          move.contains("bot") -> GameState.Status.WAITING_FOR_BOT
          else -> GameState.Status.WAITING_FOR_PLAYER
        },
        move,
        (state as? MockGamesState)?.parameters
      )
    }

    override fun generateResponse(state: GameState): String {
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

    override fun gameMoveResult(state: GameState): GameMoveResult {
      check(state is MockGamesState) { "Invalid game state type" }
      return when {
        state.lastMove.contains("draw") -> GameMoveResult.Draw
        state.lastMove.contains("win") -> GameMoveResult.Win(state.player)
        state.lastMove.contains("abandon") -> GameMoveResult.Abandon
        else -> GameMoveResult.Continue
      }
    }

    override fun isBotTurn(state: GameState): Boolean {
      return state.status == GameState.Status.WAITING_FOR_BOT
    }

    override fun generateBotMove(state: GameState): String {
      return "Bot move"
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

  test("processMention should return null for non-game related mentions") {
    val response = gameEngine.firstRequest("Hello, how are you?")
    response shouldBe null
  }

  test("processMention should start a new game when given a valid game command") {
    val response = gameEngine.firstRequest("play mock").shouldNotBeNull()
    response.body shouldContain "Starting a new game of Mock Game"
  }

  test("processMention should handle game moves in an existing game session") {
    // First start a new game
    val startResponse = gameEngine.firstRequest("play mock").shouldNotBeNull()

    // Now make a move in the game
    val moveResponse = startResponse.request("my move").shouldNotBeNull()
    moveResponse.body shouldContain "Waiting for player"
  }

  test("processMention should handle winning moves") {
    // First start a new game
    val startResponse = gameEngine.firstRequest("play mock").shouldNotBeNull()

    // Now make a winning move in the game
    val moveResponse = startResponse.request("win move").shouldNotBeNull()
    moveResponse.body shouldContain "Player ${DefaultPlayer.mention} won"
  }

  test("processMention should handle invalid game IDs") {
    val response = gameEngine.firstRequest("play nonexistent")
    response shouldBe null
  }

  test("processMention should handle case-insensitive game IDs") {
    val response = gameEngine.firstRequest("play MOCK").shouldNotBeNull()
    response.body shouldContain "Starting a new game of Mock Game"
  }

  test("processMention should handle game with parameters") {
    val startResponse = gameEngine.firstRequest("play mock with some parameters").shouldNotBeNull()
    startResponse.body shouldContain "Starting a new game of Mock Game"
    println("[DEBUG_LOG] Start response body: ${startResponse.body}")

    val moveResponse = startResponse.request("params").shouldNotBeNull()
    println("[DEBUG_LOG] Move response body: ${moveResponse.body}")
    moveResponse.body shouldContain "with some parameters"
  }

  test("processMention should handle multiple games") {
    val anotherMockGame = object : Game {
      override val gameId: String = "another"
      override val gameName: String = "Another Mock Game"

      override fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): GameState {
        return object : GameState {
          override val sessionId: String = gameSessionId
          override val player: Player = player
          override val status: GameState.Status = GameState.Status.WAITING_FOR_PLAYER
        }
      }

      override fun processMove(state: GameState, player: Player, move: String): GameState {
        return state
      }

      override fun generateResponse(state: GameState): String {
        return "Another mock game response"
      }

      override fun gameMoveResult(state: GameState): GameMoveResult {
        return GameMoveResult.Continue
      }

      override fun isBotTurn(state: GameState): Boolean {
        return false
      }

      override fun generateBotMove(state: GameState): String {
        return "Another bot move"
      }
    }

    val multiGameEngine = GameEngine(
      nonEmptyListOf(mockGame, anotherMockGame),
      InMemoryGameSessionStore()
    )

    multiGameEngine.getAvailableGames().size shouldBe 2

    val response = multiGameEngine.firstRequest("play another").shouldNotBeNull()
    response.body shouldContain "Starting a new game of Another Mock Game"
  }

  test("processMention should handle invalid moves") {
    // Create a game that throws an exception for invalid moves
    val exceptionGame = object : Game {
      override val gameId: String = "exception"
      override val gameName: String = "Exception Game"

      override fun createInitialState(gameSessionId: String, player: Player.Remote, params: String?): GameState {
        return object : GameState {
          override val sessionId: String = gameSessionId
          override val player: Player = player
          override val status: GameState.Status = GameState.Status.WAITING_FOR_PLAYER
        }
      }

      override fun processMove(state: GameState, player: Player, move: String): GameState {
        if (move.contains("invalid")) {
          throw IllegalArgumentException("Invalid move")
        }
        return state
      }

      override fun generateResponse(state: GameState): String {
        return "Exception game response"
      }

      override fun gameMoveResult(state: GameState): GameMoveResult {
        return GameMoveResult.Continue
      }

      override fun isBotTurn(state: GameState): Boolean {
        return false
      }

      override fun generateBotMove(state: GameState): String {
        return "Exception bot move"
      }
    }

    val exceptionGameEngine = GameEngine(
      nonEmptyListOf(exceptionGame),
      InMemoryGameSessionStore()
    )

    // First start a new game
    val startResponse = exceptionGameEngine.firstRequest("play exception").shouldNotBeNull()

    // Now make an invalid move
    val moveResponse = startResponse.request("invalid move").shouldNotBeNull()
    moveResponse.body shouldContain "Invalid move"
  }
})
