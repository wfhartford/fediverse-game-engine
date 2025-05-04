# Mastodon Bot

A simple Mastodon bot implemented in Kotlin using the [BigBone](https://github.com/andregasser/bigbone) library.

## Features

- Authentication with a Mastodon instance
- Posting statuses
- Following/unfollowing users
- Boosting and favoriting posts
- Retrieving the home timeline
- Searching for accounts
- Comprehensive logging using kotlin-logging (a Kotlin wrapper for SLF4J) with Logback

## Getting Started

### Prerequisites

- JDK 21 or later
- Gradle
- A Mastodon account with an access token

### Setup

1. Clone this repository
2. Set the following environment variables:
   - `MASTODON_INSTANCE_URL`: Your Mastodon instance URL (e.g., "https://mastodon.social")
   - `MASTODON_ACCESS_TOKEN`: Your Mastodon access token

### Getting a Mastodon Access Token

1. Log in to your Mastodon instance
2. Go to Settings > Development > New Application
3. Fill in the required fields:
   - Name: Your bot's name
   - Website: Your website (can be blank)
   - Redirect URI: Leave as is
   - Scopes: Select at least `read` and `write`
4. Click "Submit"
5. On the next page, copy the "Access Token"

### Running the Bot

```bash
./gradlew run
```

## Usage

The `MastodonBot` class provides the following methods:

```kotlin
// Create a bot instance
val bot = MastodonBot(instanceUrl, accessToken)

// Post a status
val status = bot.postStatus("Hello, Mastodon!")

// Post a status with the current time
val timeStatus = bot.postTimeStatus("Current time: ")

// Follow a user
val relationship = bot.followAccount(accountId)

// Unfollow a user
val relationship = bot.unfollowAccount(accountId)

// Boost a status
val boostedStatus = bot.boostStatus(statusId)

// Favorite a status
val favoritedStatus = bot.favoriteStatus(statusId)

// Get home timeline
val timeline = bot.getHomeTimeline(limit = 20)

// Search for accounts
val accounts = bot.searchAccounts("mastodon", limit = 5)
```

## Logging

The bot uses kotlin-logging (a Kotlin wrapper for SLF4J) with Logback for logging. This provides a more idiomatic Kotlin approach to logging with features like:

- Lambda expressions for lazy evaluation of log messages
- String interpolation for cleaner log formatting
- Simplified logger initialization
- Automatic exception logging

By default, logs are written to both the console and to log files in the `logs` directory.

### Log Configuration

The logging configuration is defined in `src/main/resources/logback.xml`. Key features include:

- Console logging with timestamp, thread, level, and message
- File logging with rolling policy (size and time-based)
- Default log level set to INFO
- Debug level enabled for the `ca.cutterslade.fedigame` package

### Customizing Logging

To customize logging behavior:

1. Edit the `logback.xml` file to change log patterns, levels, or appenders
2. Set different log levels for specific packages
3. Add or remove appenders as needed

Example of changing the log level for a specific class:

```xml
<logger name="ca.cutterslade.fedigame.MastodonBot" level="DEBUG" />
```

### Using kotlin-logging in Your Code

To use kotlin-logging in your own code, first import the KotlinLogging class:

```
import mu.KotlinLogging
```

Then create a logger and use it in your class:

```kotlin
class YourClass {
    // Create a logger
    private val logger = KotlinLogging.logger {}

    fun someMethod() {
        // Basic logging with string interpolation
        logger.info { "Processing item: $itemName" }

        // Lazy evaluation - expensive operations only execute if debug is enabled
        logger.debug { "Complex calculation result: ${performExpensiveCalculation()}" }

        // Exception logging - automatically includes stack trace
        try {
            // some code that might throw
        } catch (e: Exception) {
            logger.error(e) { "Failed to process item: ${e.message}" }
        }
    }
}
```

## License

This project is open source and available under the [MIT License](LICENSE).
