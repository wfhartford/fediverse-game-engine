package ca.cutterslade.fedigame.spi

sealed class Player {
  abstract val name: String
  open val mention: String
    get() = name
  data class Remote(override val name: String) : Player() {
    override val mention: String = "@$name"
  }
  data object HostBot : Player() {
    override val name: String = "HostBot"
  }
}
