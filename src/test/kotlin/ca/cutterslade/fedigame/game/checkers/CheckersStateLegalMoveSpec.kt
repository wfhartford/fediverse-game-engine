package ca.cutterslade.fedigame.game.checkers

import arrow.core.getOrElse
import ca.cutterslade.fedigame.game.ChessCheckersBoard
import ca.cutterslade.fedigame.game.ChessCheckersPieceShade
import ca.cutterslade.fedigame.game.checkers.CheckersPiece.Dark
import ca.cutterslade.fedigame.game.checkers.CheckersPiece.DarkKing
import ca.cutterslade.fedigame.game.checkers.CheckersPiece.Light
import ca.cutterslade.fedigame.game.checkers.CheckersPiece.LightKing
import ca.cutterslade.fedigame.spi.GameState
import ca.cutterslade.fedigame.spi.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class CheckersStateLegalMoveSpec : FunSpec({
  fun s(s: String) = ChessCheckersBoard.Square.of(s).getOrElse { error("Bad square: $s") }
  fun single(from: String, to: String) = Move.single(s(from), s(to)).getOrElse { error("Bad single: $from to $to") }
  fun jump(from: String, to: String) = Move.jump(s(from), s(to)).getOrElse { error("Bad jump: $from to $to") }

  fun state(board: Map<String, CheckersPiece>): CheckersState {
    val mapped = board.mapKeys { (sq, _) -> s(sq) }
    val players =
      mapOf(ChessCheckersPieceShade.Dark to Player.Remote("P1"), ChessCheckersPieceShade.Light to Player.HostBot)
    return CheckersState(
      sessionId = "test",
      status = GameState.Status.WAITING_FOR_PLAYER,
      playerShades = players,
      nextPlayer = players[ChessCheckersPieceShade.Dark].shouldNotBeNull(),
      boardState = mapped,
      lastMove = null,
    )
  }

  fun expectMoves(
    board: Map<String, CheckersPiece>,
    expectedDark: List<Move>,
    expectedLight: List<Move>,
  ) {
    val st = state(board)
    val darkMoves = st.legalMoves(ChessCheckersPieceShade.Dark)
    val lightMoves = st.legalMoves(ChessCheckersPieceShade.Light)

    if (expectedDark.isEmpty()) {
      darkMoves.shouldBeNull()
    } else {
      // Ensure not null and exactly equal ignoring order
      val dm = darkMoves.shouldNotBeNull()
      dm.toList().shouldContainExactlyInAnyOrder(expectedDark)
    }

    if (expectedLight.isEmpty()) {
      lightMoves.shouldBeNull()
    } else {
      val lm = lightMoves.shouldNotBeNull()
      lm.toList().shouldContainExactlyInAnyOrder(expectedLight)
    }
  }

  test("Dark single moves upward diagonally when empty") {
    expectMoves(
      board = mapOf("d6" to Dark),
      expectedDark = listOf(
        single("d6", "c5"),
        single("d6", "e5"),
      ),
      expectedLight = emptyList(),
    )
  }

  test("Occupied target squares are not legal for singles") {
    expectMoves(
      board = mapOf("d6" to Dark, "c5" to Dark),
      expectedDark = listOf(
        single("d6", "e5"),
        single("c5", "b4"),
        single("c5", "d4"),
      ),
      expectedLight = emptyList(),
    )
  }

  test("Jump over opponent takes precedence over single moves") {
    expectMoves(
      board = mapOf("d6" to Dark, "e5" to Light),
      expectedDark = listOf(
        jump("d6", "f4"),
      ),
      // Light has a jump over d6 to c7
      expectedLight = listOf(
        jump("e5", "c7"),
      ),
    )
  }

  test("Cannot jump over own piece") {
    expectMoves(
      board = mapOf("d6" to Dark, "e5" to Dark),
      expectedDark = listOf(
        single("d6", "c5"),
        single("e5", "d4"),
        single("e5", "f4"),
      ),
      expectedLight = emptyList(),
    )
  }

  test("Light single moves downward diagonally when empty") {
    expectMoves(
      board = mapOf("d3" to Light),
      expectedDark = emptyList(),
      expectedLight = listOf(
        single("d3", "c4"),
        single("d3", "e4"),
      ),
    )
  }

  test("Kings can move in both directions") {
    expectMoves(
      board = mapOf("d4" to LightKing),
      expectedDark = emptyList(),
      expectedLight = listOf(
        single("d4", "c3"),
        single("d4", "e3"),
        single("d4", "c5"),
        single("d4", "e5"),
      ),
    )
  }

  test("Kings can jump backward and forward over opponent - backward case") {
    // Dark king at d5, opponent at c6, landing b7 empty => jump d5->b7 legal
    expectMoves(
      board = mapOf("d5" to DarkKing, "c6" to Light),
      expectedDark = listOf(
        jump("d5", "b7"),
      ),
      // Light at c6 has only singles forward (no legal backward jump for a man)
      expectedLight = listOf(
        single("c6", "b7"),
        single("c6", "d7"),
      ),
    )
  }

  test("Kings can jump backward and forward over opponent - forward case") {
    // Dark king at d5, opponent at e6, landing f7 empty => jump d5->f7 legal
    expectMoves(
      board = mapOf("d5" to DarkKing, "e6" to Light),
      expectedDark = listOf(
        jump("d5", "f7"),
      ),
      expectedLight = listOf(
        single("e6", "d7"),
        single("e6", "f7"),
      ),
    )
  }

  test("If any jump exists, singles from other pieces are not listed") {
    // Dark has a jump from d6 over e5 to f4; another dark at a3 has singles, but only the jump should be returned
    expectMoves(
      board = mapOf(
        "d6" to Dark,
        "e5" to Light,
        "a3" to Dark,
      ),
      expectedDark = listOf(
        jump("d6", "f4"),
      ),
      // Light has a jump e5->c7
      expectedLight = listOf(
        jump("e5", "c7"),
      ),
    )
  }

  test("Jump is illegal if landing square is occupied") {
    // Dark at d6, Light at e5, but f4 occupied by Light -> no jump
    expectMoves(
      board = mapOf(
        "d6" to Dark,
        "e5" to Light,
        "f4" to Light,
      ),
      // Without the jump, singles from d6 should be considered
      expectedDark = listOf(
        single("d6", "c5"),
      ),
      // Light still has the jump e5->c7
      expectedLight = listOf(
        jump("e5", "c7"),
      ),
    )
  }
  test("Multiple jumps from different dark pieces are all returned and singles suppressed") {
    expectMoves(
      board = mapOf(
        "d6" to Dark,
        "b6" to Dark,
        "e5" to Light,
        "c5" to Light,
      ),
      expectedDark = listOf(
        jump("d6", "f4"),
        jump("d6", "b4"),
        jump("b6", "d4"),
      ),
      expectedLight = listOf(
        jump("e5", "c7"),
        jump("c5", "a7"),
        jump("c5", "e7"),
      ),
    )
  }

  test("No legal moves for dark due to edges and self-blocking; light still has singles") {
    expectMoves(
      board = mapOf(
        // Dark pieces stuck at the edge or blocked by own pieces
        "a1" to Dark,
        "c1" to Dark,
        "b2" to Dark,
        // Light has a piece with normal singles
        "g7" to Light,
      ),
      expectedDark = emptyList(),
      expectedLight = listOf(
        single("g7", "h8"),
        single("g7", "f8"),
      ),
    )
  }

  test("Promotion-eligible singles are included for men on both sides") {
    expectMoves(
      board = mapOf(
        "b2" to Dark,
        "g7" to Light,
      ),
      expectedDark = listOf(
        single("b2", "a1"),
        single("b2", "c1"),
      ),
      expectedLight = listOf(
        single("g7", "h8"),
        single("g7", "f8"),
      ),
    )
  }

  test("King jumps suppress singles from men on the same side") {
    expectMoves(
      board = mapOf(
        // Dark king with two jump options
        "d4" to DarkKing,
        // A dark man that would have singles, but they should be suppressed because a jump exists for the side
        "b6" to Dark,
        // Light pieces positioned to enable king jumps but not light jumps
        "c3" to Light,
        "e5" to Light,
      ),
      expectedDark = listOf(
        jump("d4", "b2"),
        jump("d4", "f6"),
      ),
      expectedLight = listOf(
        single("c3", "b4"),
        single("e5", "f6"),
        single("e5", "d6"),
      ),
    )
  }
})
