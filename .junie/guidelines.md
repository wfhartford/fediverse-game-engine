# Fediverse Game Engine Developer Guidelines

## Project Overview
The Fediverse Game Engine is a Kotlin-based application that runs games on the Fediverse through a Mastodon bot. Users can interact with the bot by mentioning it and using commands to play various games.

## Tech Stack
- **Language**: Kotlin 2.1.10 with JVM toolchain 21
- **Build System**: Gradle
- **Key Libraries**:
  - BigBone: Mastodon client library
  - Arrow: Functional programming utilities
  - Kotlinx Coroutines: Asynchronous programming
  - Typesafe Config: Configuration management
  - Kotlin Logging & Logback: Logging
  - Kotest: Testing framework

## Project Structure
```
src/
├── main/kotlin/ca/cutterslade/fedigame/
│   ├── game/                  # Game implementations
│   │   ├── guess/             # Number guessing game
│   │   |── tictactoe/         # Tic-tac-toe game
|   |   └── games.kt           # List of all supported games
│   ├── mastodon/              # Mastodon API integration
│   ├── spi/                   # Service Provider Interfaces
│   ├── GameEngine.kt          # Core game engine
│   ├── Main.kt                # Application entry point
│   ├── MastodonBot.kt         # Bot implementation
│   └── game-engine-test-harness.kt # Test utilities
└── test/
    └── kotlin/ca/cutterslade/fedigame/
        ├── game/              # Game-specific tests
        ├── GameEngineSpec.kt  # Game engine tests
        └── PlayGuessGameSpec.kt # Game play tests
```

## Key Components
1. **GameEngine**: Manages game sessions and processes interactions
2. **MastodonBot**: Handles interactions with the Mastodon API
3. **Game**: Interface implemented by specific games
4. **MastodonClient**: Interface for Mastodon API interactions

## Running the Application
1. Configure the application by creating an `application.conf` file or setting environment variables
2. Run the application using Gradle:
   ```
   ./gradlew run
   ```

## Running Tests
1. Run all tests:
   ```
   ./gradlew test
   ```
2. Run specific tests:
   ```
   ./gradlew test --tests "ca.cutterslade.fedigame.GameEngineSpec"
   ```

## Development Workflow
1. **Adding a new game**:
   - Create a new package for the game under `ca.cutterslade.fedigame.game`
   - Create a new class in the game's package that implements the `Game` interface
   - Register the game in the `allGames()` function in the `games.kt` file

2. **Testing**:
   - Use the test harness (`game-engine-test-harness.kt`) for manual testing
   - Write unit tests using Kotest's FunSpec style
   - Mock components only when it simplifies the test suite, prefer real implementations over mocks when the code to use the real implementations is straightforward.

3. **Configuration**:
   - Use Typesafe Config for configuration
   - Define default values in `application.conf`
   - Override with environment variables when needed

## Best Practices
1. **Error Handling**:
   - Use Arrow's `Either` type for error handling
   - Provide clear error messages for users

2. **Logging**:
   - Use the KotlinLogging library for consistent logging
   - Include appropriate log levels (debug, info, warn, error)

3. **Testing**:
   - Write comprehensive tests for all components
   - Use mocks to isolate components during testing

4. **Code Style**:
   - Follow Kotlin coding conventions
   - Use functional programming patterns where appropriate
   - Keep functions small and focused on a single responsibility
