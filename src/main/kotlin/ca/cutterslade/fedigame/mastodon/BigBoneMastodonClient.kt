package ca.cutterslade.fedigame.mastodon

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.typesafe.config.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import social.bigbone.MastodonRequest
import social.bigbone.api.Pageable
import social.bigbone.api.Range
import social.bigbone.api.entity.data.Visibility
import social.bigbone.MastodonClient as BigBone
import social.bigbone.api.entity.Account as BigBoneAccount
import social.bigbone.api.entity.Notification as BigBoneNotification
import social.bigbone.api.entity.Status as BigBoneStatus

private val logger = KotlinLogging.logger {}

class BigBoneMastodonClient(
  private val config: Config,
) : MastodonClient {
  private companion object {
    fun <T> pageableFlow(
      limit: Int = 40,
      pageFunction: suspend (range: Range) -> MastodonRequest<Pageable<T>>,
    ): Flow<T> = flow {
      var range = Range(limit = limit)
      while (true) {
        val page = pageFunction(range).execute()
        if (page.part.isEmpty()) break
        emitAll(page.part.asFlow())
        range = page.nextRange(limit)
      }
    }
  }

  private val instanceName: String
    get() = config.getString("mastodon.instance-name")
  private val accessToken: String
    get() = config.getString("mastodon.access-token")
  private val client: BigBone by lazy {
    logger.debug { "Initializing MastodonClient with instance: $instanceName" }
    BigBone.Builder(instanceName)
      .accessToken(accessToken)
      .build()
  }

  override fun notificationFlow(): Flow<Either<MastodonClientProblem, Notification>> =
    pageableFlow { client.notifications.getAllNotifications(range = it) }
      .map { it.notification().right() as Either<MastodonClientProblem, Notification> }
      .catch { emit(MastodonClientProblem.Exception(it).left()) }

  override suspend fun dismissNotification(id: String): Either<MastodonClientProblem, Unit> =
    Either.catch { client.notifications.dismissNotification(id) }
      .mapLeft { MastodonClientProblem.Exception(it) }

  override suspend fun unlistedPost(body: String, inReplyTo: String): Either<MastodonClientProblem, Status> =
    Either.catch {
      client.statuses.postStatus(
        body,
        visibility = Visibility.UNLISTED,
        inReplyToId = inReplyTo
      ).execute().status()
    }.mapLeft { MastodonClientProblem.Exception(it) }

  private fun BigBoneStatus.status(): Status {
    val account = this.account ?: throw IllegalStateException("BigBoneStatus has no account: $this")
    return Status(id, account.account(), content, inReplyToId)
  }

  private fun BigBoneAccount.account(): Account {
    return Account(id, username, acct)
  }

  private fun BigBoneNotification.notification(): Notification {
    val status = this.status ?: throw IllegalStateException("BigBoneNotification has no status: $this")
    return Notification(id, status.status())
  }
}
