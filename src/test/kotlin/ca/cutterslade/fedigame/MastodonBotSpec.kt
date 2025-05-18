package ca.cutterslade.fedigame

import arrow.core.Either
import arrow.core.nonEmptyListOf
import arrow.core.right
import ca.cutterslade.fedigame.mastodon.Account
import ca.cutterslade.fedigame.mastodon.MastodonClient
import ca.cutterslade.fedigame.mastodon.MastodonClientProblem
import ca.cutterslade.fedigame.mastodon.Notification
import ca.cutterslade.fedigame.mastodon.Status
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

class MockGameState(
  override val sessionId: String,
  override val status: GameState.Status,
  val player: Player.Remote,
) : GameState

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
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)

    val mockGame = MockGame()
    val gameEngine = GameEngine(nonEmptyListOf(mockGame), InMemoryGameSessionStore())

    coEvery { mockMastodonClient.notificationFlow() } returns flowOf()

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()
    result shouldBe 0
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

    val account = Account("user123", "user123", "user123")
    val status = Status("status123", account, "play mock", null)
    val notification = Notification(id = "notification123", status = status)

    coEvery { mockMastodonClient.notificationFlow() } returns flowOf(notification.right())

    coEvery { mockMastodonClient.dismissNotification(any()) } returns Unit.right()

    val botAccount = Account("bot123", "bot123", "bot123")
    val replyStatus = Status("reply123", botAccount, "bot-reply-content", status.id)
    coEvery { mockMastodonClient.unlistedPost(any(), any()) } returns replyStatus.right()

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()

    result shouldBe 1

    coVerify { mockMastodonClient.notificationFlow() }
    coVerify { mockMastodonClient.dismissNotification("notification123") }
    coVerify {
      mockMastodonClient.unlistedPost(
        """
      @user123 Starting a new game of Mock Game!

      This is a mock response for the game state

      Test suffix
    """.trimIndent(), "status123"
      )
    }
  }

  test("handleMentions should handle BigBoneRequestException") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)

    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    val errorMessage = "This is totally unexpected"
    coEvery { mockMastodonClient.notificationFlow() } throws RuntimeException(errorMessage)

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val exception = shouldThrow<RuntimeException> {
      bot.handleMentions()
    }
    exception.message shouldContain errorMessage
    coVerify { mockMastodonClient.notificationFlow() }
  }

  test("handleMentions should handle Left values in notification flow") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)

    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    val problem = MastodonClientProblem.Exception(RuntimeException("Test error"))
    coEvery { mockMastodonClient.notificationFlow() } returns flowOf(Either.Left(problem))

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()
    result shouldBe 0
    coVerify { mockMastodonClient.notificationFlow() }
  }

  test("handleMentions should process multiple notifications with mixed success and failure") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)
    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    val account1 = Account("user1", "user1", "user1")
    val account2 = Account("user2", "user2", "user2")
    val status1 = Status("status1", account1, "play mock", null)
    val status2 = Status("status2", account2, "play mock", null)
    val notification1 = Notification(id = "notification1", status = status1)
    val notification2 = Notification(id = "notification2", status = status2)
    val problem = MastodonClientProblem.Exception(RuntimeException("Test error"))

    coEvery { mockMastodonClient.notificationFlow() } returns flowOf(
      notification1.right(),
      Either.Left(problem),
      notification2.right()
    )

    coEvery { mockMastodonClient.dismissNotification(any()) } returns Unit.right()

    val botAccount = Account("bot123", "bot123", "bot123")
    val replyStatus = Status("reply123", botAccount, "bot-reply-content", "status1")
    coEvery { mockMastodonClient.unlistedPost(any(), any()) } returns replyStatus.right()

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()
    result shouldBe 2

    coVerify(exactly = 1) { mockMastodonClient.dismissNotification("notification1") }
    coVerify(exactly = 1) { mockMastodonClient.dismissNotification("notification2") }
    coVerify(exactly = 2) { mockMastodonClient.unlistedPost(any(), any()) }
  }


  test("handleMentions should handle failed unlistedPost") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)
    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    val account = Account("user123", "user123", "user123")
    val status = Status("status123", account, "play mock", null)
    val notification = Notification(id = "notification123", status = status)

    coEvery { mockMastodonClient.notificationFlow() } returns flowOf(notification.right())
    coEvery { mockMastodonClient.dismissNotification(any()) } returns Unit.right()

    val problem = MastodonClientProblem.Exception(RuntimeException("Failed to post"))
    coEvery { mockMastodonClient.unlistedPost(any(), any()) } returns Either.Left(problem)

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()
    result shouldBe 1

    coVerify { mockMastodonClient.notificationFlow() }
    coVerify { mockMastodonClient.dismissNotification("notification123") }
    coVerify { mockMastodonClient.unlistedPost(any(), "status123") }
  }

  test("handleMentions should handle failed dismissNotification") {
    val config = ConfigFactory.parseMap(
      mapOf(
        "mastodon.post-suffix" to "Test suffix"
      )
    )
    val mockMastodonClient = mockk<MastodonClient>(relaxed = true)
    val gameEngine = GameEngine(nonEmptyListOf(MockGame()), InMemoryGameSessionStore())

    val account = Account("user123", "user123", "user123")
    val status = Status("status123", account, "play mock", null)
    val notification = Notification(id = "notification123", status = status)

    coEvery { mockMastodonClient.notificationFlow() } returns flowOf(notification.right())

    val dismissProblem = MastodonClientProblem.Exception(RuntimeException("Failed to dismiss"))
    coEvery { mockMastodonClient.dismissNotification(any()) } returns Either.Left(dismissProblem)

    val botAccount = Account("bot123", "bot123", "bot123")
    val replyStatus = Status("reply123", botAccount, "bot-reply-content", status.id)
    coEvery { mockMastodonClient.unlistedPost(any(), any()) } returns replyStatus.right()

    val bot = MastodonBot(config, gameEngine, mockMastodonClient)

    val result = bot.handleMentions()
    result shouldBe 1

    coVerify { mockMastodonClient.notificationFlow() }
    coVerify { mockMastodonClient.dismissNotification("notification123") }
    coVerify { mockMastodonClient.unlistedPost(any(), "status123") }
  }

})
