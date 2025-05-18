package ca.cutterslade.fedigame.game

import arrow.core.nonEmptyListOf
import ca.cutterslade.fedigame.game.guess.NumberGuessingGame
import ca.cutterslade.fedigame.game.tictactoe.TicTacToeGame

fun allGames() = nonEmptyListOf(
  NumberGuessingGame(),
  TicTacToeGame(),
)
