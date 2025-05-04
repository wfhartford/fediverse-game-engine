package ca.cutterslade.fedigame

import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import arrow.core.nonEmptyListOf
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
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
  val mentionsLimit = config.getInt("engine.limits.mentions")
  val mentionsDelay = config.getDuration("engine.delays.mentions").toKotlinDuration()

  // Create the game engine and register games
  val gameEngine = GameEngine(
    nonEmptyListOf(SampleGame()),
    InMemoryGameSessionStore(),
  )

  // Create the bot with the game engine
  val bot = MastodonBot(config, gameEngine)

  logger.info { "Available games: ${gameEngine.getAvailableGames().map { it.gameName }}" }

  // Run the bot
  runBlocking {
    try {
      logger.info { "Starting main loop to process mentions" }
      while (true) {
        val count = bot.handleMentions(mentionsLimit)
        logger.info { "Processed $count mentions" }
        delay(mentionsDelay)
      }
    } catch (e: Exception) {
      logger.error(e) { "Error: ${e.message}" }
    }
  }

  logger.info { "Bot execution completed" }
}
