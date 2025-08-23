package ca.cutterslade.fedigame.spi

/**
 * Interface representing the state of a game. This is a marker interface
 * that game implementations will extend with game-specific state.
 */
interface GameState {
  /** Enum representing the status of a game. */
  enum class Status {
    WAITING_FOR_PLAYER,
    WAITING_FOR_BOT,
    COMPLETED,
    ABANDONED;

    companion object {
      val FinalStates = setOf(ABANDONED, COMPLETED)
    }
  }

  /** The unique identifier for this game session. */
  val sessionId: String

  /** The current status of the game (in progress, completed, etc.). */
  val status: Status
}
