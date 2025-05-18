package ca.cutterslade.fedigame.mastodon

import kotlinx.coroutines.flow.Flow
import social.bigbone.api.entity.Notification
import social.bigbone.api.entity.Status

interface MastodonClient {
  fun notificationFlow(): Flow<Notification>
  suspend fun dismissNotification(id: String)
  suspend fun unlistedPost(body: String, inReplyTo: String): Status
}
