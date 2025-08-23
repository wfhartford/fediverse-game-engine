package ca.cutterslade.fedigame.game

import arrow.core.Nel
import arrow.core.nonEmptyListOf
import ca.cutterslade.fedigame.game.checkers.CheckersGame
import ca.cutterslade.fedigame.game.guess.NumberGuessingGame
import ca.cutterslade.fedigame.game.tictactoe.TicTacToeGame
import ca.cutterslade.fedigame.spi.Game

fun allGames(): Nel<Game> = nonEmptyListOf(
  NumberGuessingGame,
  TicTacToeGame,
  CheckersGame
)
