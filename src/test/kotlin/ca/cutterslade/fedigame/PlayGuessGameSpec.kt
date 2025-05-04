package ca.cutterslade.fedigame

import arrow.core.nel
import ca.cutterslade.fedigame.game.guess.NumberGuessingGame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

class PlayGuessGameSpec : FunSpec({
  val engine = GameEngine(
    NumberGuessingGame().nel(),
    InMemoryGameSessionStore()
  )

  test("play a stupid round of the guessing game") {
    val upperBound = 10 // exclusive
    val lowerBound = 1 // inclusive
    fun guess() = (lowerBound..upperBound).random()
    var response = engine.firstRequest("play guess").shouldNotBeNull()
    response.body shouldContain "between 1 and 10"
    var guesses = 0
    var guess: Int
    do {
      guess = guess()
      response = response.request("$guess").shouldNotBeNull()
      guesses++
    } while (!response.body.contains("Congratulations!"))

    response.body shouldContain "You guessed the correct number ($guess) in $guesses attempts"
  }

  test("play an alright round of the guessing game") {
    var upperBound = 10 // exclusive
    var lowerBound = 1 // inclusive
    fun guess() = (lowerBound..upperBound).random()
    var response = engine.firstRequest("play guess").shouldNotBeNull()
    response.body shouldContain "between 1 and 10"
    var guesses = 0
    var guess: Int
    do {
      guess = guess()
      response = response.request("$guess").shouldNotBeNull()
      guesses++
      if (response.body.contains("too high")) upperBound = guess - 1
      else if (response.body.contains("too low")) lowerBound = guess + 1
      upperBound shouldBeGreaterThanOrEqual lowerBound
    } while (!response.body.contains("Congratulations!"))

    response.body shouldContain "You guessed the correct number ($guess) in $guesses attempts"
  }

  test("play a perfect round of the guessing game") {
    var upperBound = 10 // exclusive
    var lowerBound = 1 // inclusive
    fun guess() = (lowerBound + upperBound) / 2
    var response = engine.firstRequest("play guess").shouldNotBeNull()
    response.body shouldContain "between 1 and 10"
    var guesses = 0
    var guess: Int
    do {
      guess = guess()
      response = response.request("$guess").shouldNotBeNull()
      guesses++
      if (response.body.contains("too high")) upperBound = guess - 1
      else if (response.body.contains("too low")) lowerBound = guess + 1
      upperBound shouldBeGreaterThanOrEqual lowerBound
    } while (!response.body.contains("Congratulations!"))

    response.body shouldContain "You guessed the correct number ($guess) in $guesses attempts"
  }
})
