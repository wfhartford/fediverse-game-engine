package ca.cutterslade.fedigame.mastodon

import arrow.core.Either
import kotlinx.coroutines.flow.Flow

interface MastodonClient {
  fun notificationFlow(limit: Int = 20): Flow<Either<MastodonClientProblem, Notification>>
  suspend fun dismissNotification(id: String): Either<MastodonClientProblem, Unit>
  suspend fun unlistedPost(body: String, inReplyTo: String): Either<MastodonClientProblem, Status>
  suspend fun instanceDetails(): Either<MastodonClientProblem, Instance>
}

sealed interface MastodonClientProblem {
  val cause: Throwable?
    get() = null

  data class Exception(override val cause: Throwable) : MastodonClientProblem
}

data class Notification(val id: String, val status: Status)
data class Status(val id: String, val account: Account, val content: String, val inReplyToId: String?)
data class Account(val id: String, val username: String, val qualifiedName: String)
data class Instance(
  val domain: String,
  val title: String,
  val version: String,
  val sourceUrl: String,
  val description: String,
)
