package ca.cutterslade.fedigame.game.tictactoe

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.getOrElse
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.GameMoveResult
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player

@JvmInline
private value class Row(val index: Int) {
  init {
    check(index in 0 until 3)
  }

  companion object {
    val Rows = nonEmptyListOf(Row(0), Row(1), Row(2))
    val Numbers = nonEmptyListOf("❶", "❷", "❸")
    fun of(ch: Char) = when (ch) {
      '1' -> Row(0)
      '2' -> Row(1)
      '3' -> Row(2)
      else -> error("Illegal row character: $ch")
    }
  }

  val head: String
    get() = Numbers[index]
  val char: Char
    get() = "123"[index]
}

@JvmInline
private value class Column(val index: Int) {
  init {
    check(index in 0 until 3)
  }

  companion object {
    val Columns = nonEmptyListOf(Column(0), Column(1), Column(2))
    val Letters = nonEmptyListOf("\uD83C\uDD50", "\uD83C\uDD51", "\uD83C\uDD52")
    fun of(ch: Char) = when (ch) {
      'a', 'A' -> Column(0)
      'b', 'B' -> Column(1)
      'c', 'C' -> Column(2)
      else -> error("Illegal column character: $ch")
    }
  }

  val head: String
    get() = Letters[index]
  val char: Char
    get() = "ABC"[index]
}

@JvmInline
private value class Cell(val index: Int) {
  init {
    check(index in 0 until 9)
  }

  companion object {
    fun of(row: Row, col: Column) = Cell(row.index * 3 + col.index)
  }

  fun row() = Row(index / 3)
  fun column() = Column(index % 3)
  fun move(): String = "${column().char}${row().char}"
}

sealed class SquareState {
  abstract val symbol: Char

  sealed class PlayerSymbol : SquareState()
  data object PlayerX : PlayerSymbol() {
    override val symbol: Char
      get() = '❌'
  }

  data object PlayerO : PlayerSymbol() {
    override val symbol: Char
      get() = '⭕'
  }

  data object Empty : SquareState() {
    override val symbol: Char
      get() = '⬜'
  }
}

@JvmInline
private value class Board(private val board: NonEmptyList<SquareState>) {
  companion object {
    private fun List<SquareState>.toNonEmpty() = toNonEmptyListOrNull()!!
    val Empty = Board(List(9) { SquareState.Empty }.toNonEmpty())
    val Header = "⬛ ${Column.Columns.map { it.head }.joinToString(" ")}"
    val Lines = nonEmptyListOf(
      nonEmptyListOf(0, 1, 2),
      nonEmptyListOf(3, 4, 5),
      nonEmptyListOf(6, 7, 8),
      nonEmptyListOf(0, 3, 6),
      nonEmptyListOf(1, 4, 7),
      nonEmptyListOf(2, 5, 8),
      nonEmptyListOf(0, 4, 8),
      nonEmptyListOf(2, 4, 6)
    ).map { it.map { Cell(it) } }
  }

  fun render(): String {
    val builder = StringBuilder()
    builder.appendLine(Header)
    Row.Rows.forEach { row ->
      builder.append(row.head)
      Column.Columns.forEach { col ->
        builder.append(" ${board[Cell.of(row, col).index].symbol}")
      }
      builder.appendLine()
    }
    return builder.toString()
  }

  fun with(cell: Cell, symbol: SquareState.PlayerSymbol): Either<String, Board> = either {
    ensure(null == winner()) { "The game is already won" }
    ensure(board[cell.index] is SquareState.Empty) { "Square must be empty" }
    Board(board.toMutableList().also { it[cell.index] = symbol }.toNonEmpty())
  }

  fun winner(): SquareState.PlayerSymbol? = Lines.map { cell -> cell.map { board[it.index] } }
    .mapNotNull { it.allInstanceOfOrNull<SquareState.PlayerSymbol>() }
    .firstOrNull { it.toSet().size == 1 }?.first()

  fun finished(): Boolean = board.none { it is SquareState.Empty }

  fun emptyCells(): NonEmptyList<Cell>? =
    board.mapIndexedNotNull { index, square -> square.takeIf { it is SquareState.Empty }?.let { Cell(index) } }
      .toNonEmptyListOrNull()
}

inline fun <reified T> List<Any>.allInstanceOfOrNull(): List<T>? {
  return takeIf { iterable -> iterable.all { it is T } }?.let {
    @Suppress("UNCHECKED_CAST")
    it as List<T>
  }
}

private data class TicTacToeGameState(
  override val sessionId: String,
  override val status: GameState.Status,
  val board: Board = Board.Empty,
  val nextPlayer: Player,
  // Left: X, Right: O
  val players: Pair<Player, Player> = nextPlayer to Player.HostBot,
) : GameState {
  fun player(symbol: SquareState.PlayerSymbol) = when (symbol) {
    SquareState.PlayerX -> players.first
    SquareState.PlayerO -> players.second
  }

  fun symbol(player: Player): SquareState.PlayerSymbol? = when (player) {
    players.first -> SquareState.PlayerX
    players.second -> SquareState.PlayerO
    else -> null
  }

  fun move(player: Player, cell: Cell): Either<String, TicTacToeGameState> = either {
    ensure(player == nextPlayer) { "Player is moving out of turn" }
    val symbol = ensureNotNull(symbol(player)) { "Player is not part of this game: $player" }
    copy(
      board = board.with(cell, symbol).getOrElse { raise(it) },
      nextPlayer = players.toList().minus(player).single()
    )
  }
}

class TicTacToeGame : Game {
  companion object {
    private val MoveRegex = Regex("(?i)[a-c][1-3]")
  }

  @OptIn(ExperimentalContracts::class)
  private fun GameState.mine(): TicTacToeGameState {
    contract {
      returns() implies (this@mine is TicTacToeGameState)
    }
    return this as? TicTacToeGameState ?: throw IllegalStateException("Invalid game state type")
  }

  override val gameId: String
    get() = "tictactoe"
  override val gameName: String
    get() = "Tic-Tac-Toe"

  override suspend fun createInitialState(
    gameSessionId: String,
    player: Player.Remote,
    params: String?,
  ): Either<Game.Problem, GameState> = TicTacToeGameState(
    sessionId = gameSessionId,
    status = GameState.Status.WAITING_FOR_PLAYER,
    players = player to Player.HostBot,
    nextPlayer = player,
  ).right()

  override suspend fun processMove(
    state: GameState,
    player: Player,
    move: String,
  ): Either<Game.Problem, GameState> = either {
    val moveStr = MoveRegex.find(move)?.groupValues[0]
    ensureNotNull(moveStr) { Game.CommonProblem("Did not contain a valid move: $move") }
    ensureNotNull(moveStr.length == 2) { Game.CommonProblem("Failed to parse move: $moveStr") }
    val cell = Cell.of(Row.of(moveStr[1]), Column.of(moveStr[0]))
    state.mine().move(player, cell).getOrElse { throw IllegalStateException(it) }
  }

  override suspend fun generateResponse(state: GameState): String = state.mine().board.render()

  override suspend fun gameMoveResult(state: GameState): GameMoveResult {
    state.mine().board.winner()?.also { return GameMoveResult.Win(state.player(it)) }
    if (state.board.finished()) return GameMoveResult.Draw
    return GameMoveResult.Continue
  }

  override suspend fun isBotTurn(state: GameState): Boolean = state.mine().nextPlayer == Player.HostBot

  override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> = either {
    ensureNotNull(state.mine().board.emptyCells()?.random()?.move()) { Game.CommonProblem("I can't move") }
  }
}
