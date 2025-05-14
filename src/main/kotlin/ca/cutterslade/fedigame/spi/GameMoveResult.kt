package ca.cutterslade.fedigame.spi

sealed class GameMoveResult {
  data class Win(val winner: Player) : GameMoveResult()
  data object Draw : GameMoveResult()
  data object Abandon : GameMoveResult()
  data object Continue : GameMoveResult()
}
