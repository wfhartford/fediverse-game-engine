package ca.cutterslade.fedigame.game.checkers

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.abs
import arrow.core.Either
import arrow.core.Nel
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import ca.cutterslade.fedigame.game.ChessCheckersBoard
import ca.cutterslade.fedigame.game.ChessCheckersBoard.Shade
import ca.cutterslade.fedigame.game.ChessCheckersBoard.Square
import ca.cutterslade.fedigame.game.ChessCheckersPiece
import ca.cutterslade.fedigame.game.ChessCheckersPieceShade
import ca.cutterslade.fedigame.spi.Game
import ca.cutterslade.fedigame.spi.GameMoveResult
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

enum class CheckersPiece(
  override val shade: ChessCheckersPieceShade,
  override val symbol: String,
) : ChessCheckersPiece {
  Light(ChessCheckersPieceShade.Light, "🔴"), Dark(ChessCheckersPieceShade.Dark, "⚫"),
  LightKing(ChessCheckersPieceShade.Light, "🚩"), DarkKing(ChessCheckersPieceShade.Dark, "🏴");

  val king: Boolean
    get() = this == LightKing || this == DarkKing

  fun promote() = when (shade) {
    ChessCheckersPieceShade.Light -> LightKing
    ChessCheckersPieceShade.Dark -> DarkKing
  }
}

sealed class Move {
  abstract val from: Square
  abstract val to: Square

  data class Single(override val from: Square, override val to: Square) : Move()
  data class Jump(override val from: Square, override val to: Square) : Move() {
    val jumpedSquare: Square
      get() = Square.of(
        ChessCheckersBoard.FileValue.entries[listOf(from.fileValue.ordinal, to.fileValue.ordinal).average().toInt()],
        ChessCheckersBoard.RankValue.entries[listOf(from.rankValue.ordinal, to.rankValue.ordinal).average().toInt()]
      )
  }

  val direction: ChessCheckersPieceShade
    get() = if (from.rank.ordinal > to.rank.ordinal) ChessCheckersPieceShade.Light else ChessCheckersPieceShade.Dark
  val shortString: String
    get() = "${from.shortString} to ${to.shortString}"

  override fun toString(): String = "Move(${from.shortString} to ${to.shortString})"

  companion object {
    fun of(from: Square, to: Square): Either<Game.Problem, Move> = either {
      val fileDist = abs(from.fileValue.ordinal - to.fileValue.ordinal)
      val rankDist = abs(from.rankValue.ordinal - to.rankValue.ordinal)
      when (fileDist) {
        1 if rankDist == 1 -> Single(from, to)
        2 if rankDist == 2 -> Jump(from, to)
        else -> raise(
          Game.CommonProblem("Move must be a single or jump: ${from.shortString} to ${to.shortString}")
        )
      }
    }

    fun single(from: Square, to: Square) = either {
      of(from, to).bind() as? Single ?: raise(Game.CommonProblem("Not a single move"))
    }

    fun jump(from: Square, to: Square) = either {
      of(from, to).bind() as? Jump ?: raise(Game.CommonProblem("Not a jump move"))
    }
  }
}

data class CheckersState(
  override val sessionId: String,
  override val status: GameState.Status,
  val playerShades: Map<ChessCheckersPieceShade, Player>,
  val nextPlayer: Player,
  val boardState: Map<Square, CheckersPiece>,
  val lastMove: Move? = null,
) : GameState {
  val playerNotNext: Player
    get() = playerShades.values.single { it != nextPlayer }
  val legalDarkMoves by lazy(LazyThreadSafetyMode.NONE) { enumerateLegalMoves(ChessCheckersPieceShade.Dark) }
  val legalLightMoves by lazy(LazyThreadSafetyMode.NONE) { enumerateLegalMoves(ChessCheckersPieceShade.Light) }
  fun legalMoves(shade: ChessCheckersPieceShade) =
    if (shade == ChessCheckersPieceShade.Dark) legalDarkMoves else legalLightMoves

  fun move(piece: CheckersPiece, move: Move): GameState {
    val newPiece = if (move.to.rankValue.boardEnd()) piece.promote() else piece
    val board = buildMap {
      putAll(boardState)
      remove(move.from)
      if (move is Move.Jump) remove(move.jumpedSquare)
      put(move.to, newPiece)
    }
    if (board.values.any { it.shade != piece.shade }) GameState.Status.WAITING_FOR_PLAYER
    else GameState.Status.COMPLETED
    val next = if (move is Move.Jump) nextPlayer else
      playerShades[piece.shade.otherShade()] ?: throw IllegalStateException("Bad player shades")
    return copy(
      nextPlayer = next,
      boardState = board,
      lastMove = move
    )
  }

  private fun enumerateLegalMoves(playerShade: ChessCheckersPieceShade): Nel<Move>? {
    val playerSquares = boardState.filterValues { it.shade == playerShade }.keys
    logger.debug { "$playerShade owns ${playerSquares.size} squares" }
    if (playerSquares.isEmpty()) return null
    val jumpMoves = playerSquares.flatMap { it.jumpMoves() }
      .also { logger.debug { "Possibly illegal jump moves for $playerShade: ${it.map { it.shortString }}" } }
      .filter { it.isLegal(playerShade) }
      .toNonEmptyListOrNull()
    if (jumpMoves != null) {
      logger.debug { "Legal jump moves for $playerShade: ${jumpMoves.map { it.shortString }}" }
      return jumpMoves
    }
    val singleMoves = playerSquares.flatMap { it.singleMoves() }
      .also { logger.debug { "Possibly illegal single moves for $playerShade: ${it.map { it.shortString }}" } }
      .filter { it.isLegal(playerShade) }
      .toNonEmptyListOrNull()
    if (singleMoves != null) {
      logger.debug { "Legal single moves for $playerShade: ${singleMoves.map { it.shortString }}" }
      return singleMoves
    }
    logger.debug { "No legal moves for $playerShade" }
    return null
  }

  private fun Move.isLegal(playerShade: ChessCheckersPieceShade): Boolean {
    val piece = boardState[from] ?: throw IllegalStateException("No piece at $from")
    if (piece.shade != playerShade) throw IllegalStateException("Not $playerShade's piece")
    if (boardState[to] != null) {
      logger.trace { "Illegal $playerShade move: $shortString because $to is occupied" }
      return false
    }
    if (!piece.king && direction == playerShade) {
      logger.trace { "Illegal $playerShade move: $shortString because $piece is not a king and moving towards $direction" }
      return false
    }
    if (this is Move.Jump) {
      if (boardState[jumpedSquare]?.shade != playerShade.otherShade()) {
        logger.trace { "Illegal $playerShade jump: $shortString because $jumpedSquare is not the opponent's piece" }
        return false
      }
    }
    logger.trace { "Legal $playerShade move: $shortString" }
    return true
  }

  private fun Square.singleMoves(): List<Move.Single> {
    val files = fileValue.ordinal.let { listOf(it + 1, it - 1) }
      .mapNotNull { fileOrdinal -> ChessCheckersBoard.FileValue.entries.getOrNull(fileOrdinal) }
    val ranks = rankValue.ordinal.let { listOf(it + 1, it - 1) }
      .mapNotNull { rankOrdinal -> ChessCheckersBoard.RankValue.entries.getOrNull(rankOrdinal) }
    return files.flatMap { file ->
      ranks.map { rank ->
        Move.single(this, Square.of(file, rank))
          .getOrElse { throw IllegalStateException("Bad Square.singleMoves() implementation") }
      }
    }
  }

  private fun Square.jumpMoves(): List<Move.Jump> {
    val files = fileValue.ordinal.let { listOf(it + 2, it - 2) }
      .mapNotNull { fileOrdinal -> ChessCheckersBoard.FileValue.entries.getOrNull(fileOrdinal) }
    val ranks = rankValue.ordinal.let { listOf(it + 2, it - 2) }
      .mapNotNull { rankOrdinal -> ChessCheckersBoard.RankValue.entries.getOrNull(rankOrdinal) }
    return files.flatMap { file ->
      ranks.map { rank ->
        Move.jump(this, Square.of(file, rank))
          .getOrElse { throw IllegalStateException("Bad Square.jumpMoves() implementation") }
      }
    }
  }
}

object CheckersGame : Game {
  private val moveRegex = Regex("([a-hA-H][1-8]) to ([a-hA-H][1-8])")
  private val darkSide = ChessCheckersBoard.ranks.subList(5, 8).flatMap { it.squares }.filter { it.shade == Shade.Dark }
  private val lightSide =
    ChessCheckersBoard.ranks.subList(0, 3).flatMap { it.squares }.filter { it.shade == Shade.Dark }
  private val initialBoardState =
    darkSide.associateWith { CheckersPiece.Dark } + lightSide.associateWith { CheckersPiece.Light }
  override val gameId = "checkers"
  override val gameName = "Checkers"

  override suspend fun createInitialState(
    gameSessionId: String,
    player: Player.Remote,
    params: String?,
  ): Either<Game.Problem, GameState> = CheckersState(
    gameSessionId, GameState.Status.WAITING_FOR_PLAYER,
    mapOf(ChessCheckersPieceShade.Dark to player, ChessCheckersPieceShade.Light to Player.HostBot),
    player, initialBoardState
  ).right()

  @OptIn(ExperimentalContracts::class)
  private fun GameState.mine(): CheckersState {
    contract {
      returns() implies (this@mine is CheckersState)
    }
    return this as? CheckersState ?: throw IllegalStateException("Invalid game state type")
  }

  override suspend fun processMove(
    state: GameState,
    player: Player,
    move: String,
  ): Either<Game.Problem, GameState> = either {
    logger.debug { "processMove($state, $player, $move)" }
    val myState = state.mine()
    if (myState.status in GameState.Status.FinalStates) raise(Game.CommonProblem("Game is over"))
    if (myState.nextPlayer != player) raise(Game.CommonProblem("Not your turn"))
    val match = moveRegex.find(move) ?: raise(Game.CommonProblem("Invalid move: $move"))
    val move = Move.of(Square.of(match.groupValues[1]).bind(), Square.of(match.groupValues[2]).bind()).bind()
    val piece = myState.boardState[move.from]
      ?: raise(Game.CommonProblem("No piece at ${move.from}"))
    if (player != state.playerShades[piece.shade])
      raise(Game.CommonProblem("Not your piece at ${move.from}"))
    val legalMoves = myState.legalMoves(piece.shade)
    ensureNotNull(legalMoves) { Game.CommonProblem("No legal moves for ${piece.shade}") }
    ensure(move in legalMoves) { Game.CommonProblem("Move $move is not a legal move for ${piece.shade}") }
    myState.move(piece, move)
  }

  override suspend fun generateResponse(state: GameState): String {
    val myState = state.mine()
    val nextPlayer = myState.nextPlayer
    val otherPlayer = myState.playerNotNext
    val previous = if (state.lastMove != null) ", ${otherPlayer.mention} moved ${state.lastMove.shortString}"
    else ""
    return "${nextPlayer.mention}, it's your turn$previous:\n\n${ChessCheckersBoard.renderBoard(state.boardState)}"
  }

  override suspend fun gameMoveResult(state: GameState): GameMoveResult {
    logger.debug { "gameMoveResult($state)" }
    val myState = state.mine()
    val darkMoves = myState.legalMoves(ChessCheckersPieceShade.Dark)
    val lightMoves = myState.legalMoves(ChessCheckersPieceShade.Light)
    return when {
      darkMoves == null && lightMoves == null -> GameMoveResult.Draw
      darkMoves == null -> GameMoveResult.Win(myState.playerShades[ChessCheckersPieceShade.Light]!!)
      lightMoves == null -> GameMoveResult.Win(myState.playerShades[ChessCheckersPieceShade.Dark]!!)
      else -> GameMoveResult.Continue
    }
  }

  override suspend fun isBotTurn(state: GameState): Boolean = state.mine().nextPlayer == Player.HostBot

  override suspend fun generateBotMove(state: GameState): Either<Game.Problem, String> = either {
    val myState = state.mine()
    // Bot is always light
    ensureNotNull(myState.legalMoves(ChessCheckersPieceShade.Light)) { Game.CommonProblem("Game is over") }
      .random().shortString
  }
}
