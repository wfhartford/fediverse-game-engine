package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.nonEmptyListOf
import arrow.core.right
import ca.cutterslade.fedigame.mastodon.MastodonClient
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.GameMoveResult
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import social.bigbone.api.entity.Account
import social.bigbone.api.entity.Notification
import social.bigbone.api.entity.Status
import social.bigbone.api.exception.BigBoneRequestException

// Mock implementation of GameState for testing
class MockGameState(
  override val sessionId: String,
  override val status: GameState.Status,
  val player: Player.Remote,
) : GameState

// Mock implementation of Game for testing
class MockGame : Game {
  override val gameId: String = "mock"
  override val gameName: String = "Mock Game"

  override suspend fun createInitialState(
    gameSessionId: String,
    player: Player.Remote,
    params: String?,
  ): Either<Game.Problem, GameState> {
    return MockGameState(gameSessionId, GameState.Status.WAITING_FOR_PLAYER, player).right()
  }

  override suspend fun processMove(
    state: GameState,
    player: Player,
    move: String,
  ): Either<Game.Problem, GameState> {
    val mockState = state as MockGameState
    return MockGameState(mockState.sessionId, GameState.Status.WAITING_FOR_PLAYER, mockState.player).right()
  }

  override suspend fun generateResponse(state: GameState): String {
    return "This is a mock response for the game state"
  }

  override suspend fun gameMoveResult(state: GameState): GameMoveResult {
    return GameMoveResult.Continue
  }

  override suspend fun isBotTurn(state: GameState): Boolean {
    return false
  }

  override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> {
    return "bot move".right()
  }
}

@Suppress("UnusedFlow")
class MastodonBotSpec : FunSpec({
  test("handleMentions should process an empty notifications flow") {
    // Create mocks for dependencies
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)

    // Create a real GameEngine with a mock Game
    val mockGame = MockGame()
    val gameEngine = GameEngine(nonEmptyListOf(mockGame), InMemoryGameSessionStore())

    // Create mock notification with empty flow
    coEvery { mockMastodonClient.notificationFlow() } returns flowOf()

    // Create the bot with real GameEngine and mocked MastodonClient
    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    // Execute the method under test
    val result = bot.handleMentions()

    // Verify the result
    result shouldBe 0

    // Verify interactions with dependencies
    coVerify { mockMastodonClient.notificationFlow() }
  }

  test("handleMentions should handle BigBoneRequestException") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)

    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    // Set up mock to throw exception
    val errorMessage = "API rate limit exceeded"
    coEvery { mockMastodonClient.notificationFlow() } throws BigBoneRequestException(errorMessage)

    // Create the bot with real GameEngine and mocked MastodonClient
    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    // Execute the method under test and verify exception
    val exception = shouldThrow<MastodonBot.MastodonBotException> {
      bot.handleMentions()
    }

    // Verify the exception message
    exception.message shouldContain errorMessage

    // Verify interactions with dependencies
    coVerify { mockMastodonClient.notificationFlow() }
  }

  test("handleMentions should process a notification with a status") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)

    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    val account = Account(id = "user123", acct = "user123")
    val status = Status(id = "status123", account = account, content = "play mock")
    val notification = Notification(id = "notification123", status = status)

    coEvery { mockMastodonClient.notificationFlow() } returns flowOf(notification)

    coEvery { mockMastodonClient.dismissNotification(any()) } returns Unit

    val replyStatus = Status(id = "reply123")
    coEvery { mockMastodonClient.unlistedPost(any(), any()) } returns replyStatus

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()

    result shouldBe 1

    coVerify { mockMastodonClient.notificationFlow() }
    coVerify { mockMastodonClient.dismissNotification("notification123") }
    coVerify { mockMastodonClient.unlistedPost("""
      @user123 Starting a new game of Mock Game!

      This is a mock response for the game state

      Test suffix
    """.trimIndent(), "status123") }
  }
})
