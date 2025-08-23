package ca.cutterslade.fedigame.game

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import ca.cutterslade.fedigame.spi.Game

object ChessCheckersBoard {
  enum class GroupType { File, Rank }
  interface GroupValue<T : GroupValue<T>> {
    val char: Char
    fun forChar(char: Char): Either<Game.Problem, T>
    val ordinal: Int
  }

  enum class FileValue : GroupValue<FileValue> {
    A, B, C, D, E, F, G, H;

    override val char: Char = name[0].lowercaseChar()
    override fun forChar(char: Char): Either<Game.Problem, FileValue> = either {
      val upper = char.uppercaseChar()
      ensure(char in 'A'..'H') { Game.CommonProblem("Expected $char to be in range A..H but got $upper") }
      valueOf(upper.toString())
    }

    override fun toString() = "FileValue($char)"
  }

  enum class RankValue : GroupValue<RankValue> {
    R1, R2, R3, R4, R5, R6, R7, R8;

    override val char: Char = name[1]
    override fun forChar(char: Char): Either<Game.Problem, RankValue> = either {
      val upper = char.uppercaseChar()
      ensure(char in '1'..'8') { Game.CommonProblem("Expected $char to be in range A..H but got $upper") }
      valueOf("R${char}")
    }
    fun boardEnd() = this == R1 || this == R8

    override fun toString() = "RankValue($char)"
  }

  enum class Shade(val char: String) { Light("◻"), Dark("◼") }
  class Square private constructor(val fileValue: FileValue, val rankValue: RankValue) {
    val shade: Shade
      get() = if ((fileValue.ordinal + rankValue.ordinal) % 2 == 0) Shade.Dark else Shade.Light
    val file: File
      get() = files[fileValue.ordinal]
    val rank: Rank
      get() = ranks[rankValue.ordinal]
    val shortString: String
      get() = "${fileValue.char}${rankValue.char}"

    override fun toString(): String = "Square($shortString)"
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Square) return false
      if (fileValue != other.fileValue) return false
      if (rankValue != other.rankValue) return false
      return true
    }

    override fun hashCode(): Int {
      var result = fileValue.hashCode()
      result = 31 * result + rankValue.hashCode()
      return result
    }

    companion object {
      val squares: Map<Pair<FileValue, RankValue>, Square> = FileValue.entries.flatMap { file ->
        RankValue.entries.map { rank ->
          Pair(file, rank) to Square(file, rank)
        }
      }.toMap()

      fun of(shortString: String): Either<Game.Problem, Square> = either {
        val file = FileValue.entries[shortString[0] - 'a']
        val rank = RankValue.entries[shortString[1] - '1']
        of(file, rank)
      }

      fun of(file: FileValue, rank: RankValue) =
        squares[Pair(file, rank)] ?: throw IllegalStateException("No square at: ${file.char}${rank.char}")
    }
  }

  sealed class Group<T : GroupValue<T>>(val type: GroupType, val groupValue: T) {
    abstract val squares: List<Square>
    val ordinal: Int
      get() = groupValue.ordinal
    val char: Char
      get() = groupValue.char
  }

  data class File(val file: FileValue, override val squares: List<Square>) : Group<FileValue>(GroupType.File, file)
  data class Rank(val rank: RankValue, override val squares: List<Square>) : Group<RankValue>(GroupType.Rank, rank)

  val squares: List<Square> =
    FileValue.entries.flatMap { file -> RankValue.entries.map { rank -> Square.of(file, rank) } }
  val files: List<File> = FileValue.entries.map { file -> File(file, squares.filter { it.fileValue == file }) }
  val ranks: List<Rank> = RankValue.entries.map { rank -> Rank(rank, squares.filter { it.rankValue == rank }) }

  private const val RenderFiles = "+X+a+b+c+d+e+f+g+h+X+"
  fun renderBoard(pieces: Map<Square, ChessCheckersPiece> = mapOf()): String {
    val renderedRanks = ranks.reversed()
      .map { rank -> "|${rank.char}|${rank.squares.joinToString("|") { pieces[it]?.symbol ?: it.shade.char }}|${rank.char}|" }
    return (listOf(RenderFiles) + renderedRanks + listOf(RenderFiles)).joinToString("\n")
  }
}

enum class ChessCheckersPieceShade {
  Light, Dark;

  fun otherShade() = when (this) {
    Light -> Dark
    Dark -> Light
  }
}

interface ChessCheckersPiece {
  val shade: ChessCheckersPieceShade
  val symbol: String
}
