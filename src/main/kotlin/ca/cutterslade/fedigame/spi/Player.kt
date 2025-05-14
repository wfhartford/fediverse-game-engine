package ca.cutterslade.fedigame.spi

sealed class Player {
  data class Remote(val name: String) : Player() {
    val mention: String = "@$name"
  }
  data object HostBot : Player()
}
