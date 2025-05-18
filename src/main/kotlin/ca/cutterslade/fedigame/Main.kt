package ca.cutterslade.fedigame

import kotlin.time.toKotlinDuration
import arrow.core.nonEmptyListOf
import ca.cutterslade.fedigame.game.allGames
import ca.cutterslade.fedigame.game.guess.NumberGuessingGame
import ca.cutterslade.fedigame.game.tictactoe.TicTacToeGame
import ca.cutterslade.fedigame.mastodon.BigBoneMastodonClient
import com.typesafe.config.ConfigFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Main function to demonstrate the Mastodon bot.
 *
 * This bot uses the typesafe config library to load configuration from application.conf.
 * Configuration can be overridden using environment variables as specified in application.conf.
 */
fun main() {
  // Load configuration from application.conf
  val config = ConfigFactory.load()

  // Get configuration values
  val mentionsDelay = config.getDuration("engine.delays.mentions").toKotlinDuration()

  // Create the game engine and register games
  val gameEngine = GameEngine(
    allGames(),
    InMemoryGameSessionStore(),
  )

  val mastodonClient = BigBoneMastodonClient(config)

  // Create the bot with the game engine
  val bot = MastodonBot(config, gameEngine, mastodonClient)

  logger.info { "Available games: ${gameEngine.getAvailableGames().map { it.gameName }}" }

  // Run the bot
  runBlocking {
    try {
      logger.info { "Starting main loop to process mentions" }
      while (true) {
        val count = bot.handleMentions()
        logger.info { "Processed $count mentions" }
        delay(mentionsDelay)
      }
    } catch (e: Exception) {
      logger.error(e) { "Error: ${e.message}" }
    }
  }

  logger.info { "Bot execution completed" }
}
