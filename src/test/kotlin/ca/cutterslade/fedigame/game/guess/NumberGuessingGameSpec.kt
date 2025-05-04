package ca.cutterslade.fedigame.game.guess

import ca.cutterslade.fedigame.GameMoveResult
import ca.cutterslade.fedigame.GameState
import ca.cutterslade.fedigame.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class NumberGuessingGameSpec : FunSpec({
  val game = NumberGuessingGame()

  test("game should have correct ID and name") {
    game.gameId shouldBe "guess"
    game.gameName shouldBe "Number Guessing Game"
  }

  test("createInitialState should initialize game state correctly") {
    val player = Player.Remote("player123")
    val state = game.createInitialState("thread123", player, null)

    state.shouldBeInstanceOf<NumberGuessingGameState>().apply {
      sessionId shouldBe "thread123"
      player shouldBe player
      status shouldBe GameState.Status.WAITING_FOR_PLAYER
      attempts shouldBe 0
      lastGuess shouldBe null
    }

    val targetNumber = state.targetNumber
    (targetNumber in 1..10) shouldBe true
  }

  test("processMove should update game state correctly for incorrect guess") {
    val player = Player.Remote("player123")
    val initialState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )

    // Test with a guess that's too low
    val lowGuessMove = "I guess 3"
    val lowGuessState = game.processMove(initialState, player, lowGuessMove)

    lowGuessState.shouldBeInstanceOf<NumberGuessingGameState>().apply {
      attempts shouldBe 1
      lastGuess shouldBe 3
      status shouldBe GameState.Status.WAITING_FOR_PLAYER
    }

    // Test with a guess that's too high
    val highGuessMove = "I guess 8"
    val highGuessState = game.processMove(initialState, player, highGuessMove)

    highGuessState.shouldBeInstanceOf<NumberGuessingGameState>().apply {
      attempts shouldBe 1
      lastGuess shouldBe 8
      status shouldBe GameState.Status.WAITING_FOR_PLAYER
    }
  }

  test("processMove should update game state correctly for correct guess") {
    val player = Player.Remote("player123")
    val initialState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )

    val correctGuessMove = "I guess 5"
    val correctGuessState = game.processMove(initialState, player, correctGuessMove)

    correctGuessState.shouldBeInstanceOf<NumberGuessingGameState>().apply {
      attempts shouldBe 1
      lastGuess shouldBe 5
      status shouldBe GameState.Status.COMPLETED
    }
  }

  test("processMove should throw exception for invalid guess") {
    val player = Player.Remote("player123")
    val initialState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )

    // Test with a guess outside the valid range
    val invalidGuessMove = "I guess 15"
    val exception = runCatching { game.processMove(initialState, player, invalidGuessMove) }
      .exceptionOrNull()

    exception.shouldBeInstanceOf<IllegalArgumentException>()
      .message shouldBe "Please guess a number between 1 and 10"
  }

  test("generateResponse should return appropriate message for initial state") {
    val player = Player.Remote("player123")
    val initialState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )

    game.generateResponse(initialState) shouldContain "I'm thinking of a number between 1 and 10"
  }

  test("generateResponse should return appropriate message for low guess") {
    val player = Player.Remote("player123")
    val lowGuessState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 1,
      status = GameState.Status.WAITING_FOR_PLAYER,
      lastGuess = 3
    )

    game.generateResponse(lowGuessState) shouldContain "too low"
  }

  test("generateResponse should return appropriate message for high guess") {
    val player = Player.Remote("player123")
    val highGuessState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 1,
      status = GameState.Status.WAITING_FOR_PLAYER,
      lastGuess = 8
    )

    game.generateResponse(highGuessState) shouldContain "too high"
  }

  test("generateResponse should return appropriate message for correct guess") {
    val player = Player.Remote("player123")
    val correctGuessState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 1,
      status = GameState.Status.COMPLETED,
      lastGuess = 5
    )

    game.generateResponse(correctGuessState).let {
      it shouldContain "Congratulations"
      it shouldContain "5"
      it shouldContain "1 attempts"
    }
  }

  test("gameMoveResult should return Continue for non-completed state") {
    val player = Player.Remote("player123")
    val nonCompletedState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 1,
      status = GameState.Status.WAITING_FOR_PLAYER,
      lastGuess = 3
    )

    game.gameMoveResult(nonCompletedState) shouldBe GameMoveResult.Continue
  }

  test("gameMoveResult should return Win for completed state") {
    val player = Player.Remote("player123")
    val completedState = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 1,
      status = GameState.Status.COMPLETED,
      lastGuess = 5
    )

    game.gameMoveResult(completedState)
      .shouldBeInstanceOf<GameMoveResult.Win>()
      .winner shouldBe player
  }

  test("isBotTurn should always return false") {
    val player = Player.Remote("player123")
    val state = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )

    game.isBotTurn(state) shouldBe false
  }

  test("generateBotMove should throw UnsupportedOperationException") {
    val player = Player.Remote("player123")
    val state = NumberGuessingGameState(
      sessionId = "thread123",
      player = player,
      targetNumber = 5,
      attempts = 0,
      status = GameState.Status.WAITING_FOR_PLAYER
    )

    runCatching { game.generateBotMove(state) }.exceptionOrNull()
      .shouldBeInstanceOf<UnsupportedOperationException>()
      .message shouldContain "Bot doesn't make moves"
  }
})
