package ca.cutterslade.fedigame.mastodon

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.toMap
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import social.bigbone.api.entity.Account
import social.bigbone.api.entity.Notification
import social.bigbone.api.entity.NotificationType
import social.bigbone.api.entity.Status
import social.bigbone.api.entity.data.Visibility

private val logger = KotlinLogging.logger {}

class MastodonClientSpec : FunSpec({
  lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
  var port = 0
  val dismissedNotifications = mutableListOf<String>()

  // Create a list of test notifications for pagination testing
  val testNotifications = (1..10).map { index ->
    val id = "$index"
    val account = Account("test-account-id", "testuser", "testuser@localhost")
    Notification(
      id,
      NotificationType.MENTION,
      account = account,
      status = Status("test-status-id-$index", content = "Test notification $index", account = account),
    )
  }

  beforeSpec {
    server = embeddedServer(Netty, port = 0) {
      install(ContentNegotiation) {
        json()
      }
      routing {
        get("/") {
          call.respondText("ok")
        }
        get("/.well-known/nodeinfo") {
          logger.info { "/.well-known/nodeinfo request" }
          call.respond(
            mapOf(
              "links" to listOf(
                mapOf(
                  "rel" to "http://nodeinfo.diaspora.software/ns/schema/2.0",
                  "href" to "http://localhost:$port/nodeinfo/2.0"
                )
              )
            )
          )
        }
        get("/nodeinfo/2.0") {
          logger.info { "/nodeinfo/2.0 request" }
          call.respond(
            mapOf(
              "software" to mapOf(
                "name" to "mastodon",
                "version" to "3.1.2"
              )
            )
          )
        }
        get("/api/v2/instance") {
          logger.info { "/api/v2/instance request" }
          call.respond(
            mapOf(
              "domain" to "localhost",
              "title" to MastodonClientSpec::class.simpleName,
              "description" to "Embedded Mastodon instance for testing",
              "source_url" to "https://thisisthesource.com",
              "version" to "3.1.2",
            )
          )
        }
        post("/api/v1/statuses") {
          logger.info { "/api/v1/statuses POST request" }
          val parameters = call.receiveParameters()
          logger.info { "Request body: $parameters" }

          // Extract parameters from the request
          val status = parameters["status"].shouldNotBeNull()
          val visibility = parameters["visibility"].shouldNotBeNull()
          val inReplyToId = parameters["in_reply_to_id"].shouldNotBeNull()
          val sensitive = parameters["sensitive"].shouldNotBeNull()

          // Create a mock response
          call.respond(
            Status(
              id = "test-status-id",
              content = status,
              visibility = Visibility.valueOf(visibility.uppercase()),
              inReplyToId = inReplyToId,
              isSensitive = sensitive.toBoolean(),
              account = Account(
                id = "test-account-id",
                username = "testuser",
                acct = "testuser@localhost"
              )
            )
          )
        }
        get("/api/v1/notifications") {
          val parameters = call.request.queryParameters
          logger.info { "/api/v1/notifications GET request: ${parameters.toMap()}" }

          // Get pagination parameters
          val limit = parameters["limit"]?.toIntOrNull() ?: 20
          val maxId = parameters["max_id"]

          // Filter notifications based on pagination parameters
          val filteredNotifications = maxId?.let { max ->
            val maxIdIndex = testNotifications.indexOfFirst { it.id == max }
            if (maxIdIndex in 0 until testNotifications.size)
              testNotifications.subList(maxIdIndex + 1, minOf(maxIdIndex + 1 + limit, testNotifications.size))
            else
              emptyList()
          } ?: testNotifications.take(limit)

          // Add Link header for pagination if there are more notifications
          if (filteredNotifications.isNotEmpty() && filteredNotifications.last().id != testNotifications.last().id) {
            val nextMaxId = filteredNotifications.last().id
            val linkHead = "<http://localhost:$port/api/v1/notifications?limit=$limit&max_id=$nextMaxId>; rel=\"next\""
            logger.info { "Adding Link header: $linkHead" }
            call.response.headers.append("Link", linkHead)
          }

          call.respond(filteredNotifications)
        }

        post("/api/v1/notifications/{id}/dismiss") {
          logger.info { "/api/v1/notifications/{id}/dismiss POST request" }
          val id = call.parameters["id"].shouldNotBeNull()
          logger.info { "Dismissing notification with ID: $id" }
          dismissedNotifications.add(id)
          call.respond(mapOf<String, String>())
        }
      }
    }.start()

    port = server.engine.resolvedConnectors().first().port
  }

  afterSpec {
    server.stop(1000, 2000)
  }

  beforeEach { dismissedNotifications.clear() }

  test("server responds with ok") {
    val client = HttpClient(CIO)
    val response = client.get("http://localhost:$port/")
    response.bodyAsText() shouldBe "ok"
    client.close()
  }

  mapOf(
    "BigBone" to {
      BigBoneMastodonClient(
        ConfigFactory.parseMap(
          mapOf(
            "mastodon.instance-name" to "localhost",
            "mastodon.port" to port,
            "mastodon.disable-https" to true,
            "mastodon.access-token" to "hard-coded-test-access-token"
          )
        )
      )
    },
    "ktor" to {
      KtorMastodonClient(
        ConfigFactory.parseMap(
          mapOf(
            "mastodon.instance-name" to "localhost",
            "mastodon.port" to port,
            "mastodon.disable-https" to true,
            "mastodon.access-token" to "hard-coded-test-access-token"
          )
        )
      )
    },
  ).forEach { (name, factory) ->
    context("$name client") {
      test("client can get instance details") {
        val client = factory()
        val details = client.instanceDetails()

        details.shouldBeRight {
          logger.warn(it.cause) { "Unable to get instance details" }
          it.toString()
        }.apply {
          domain shouldBe "localhost"
          title shouldBe MastodonClientSpec::class.simpleName
          description shouldBe "Embedded Mastodon instance for testing"
          version shouldBe "3.1.2"
        }
      }

      test("client can post an unlisted status in reply to another status") {
        val client = factory()
        val testMessage = "This is a test message"
        val replyToId = "original-status-id"

        val result = client.unlistedPost(testMessage, replyToId)

        result.shouldBeRight {
          logger.warn(it.cause) { "Unable to post unlisted status" }
          it.toString()
        }.apply {
          id shouldBe "test-status-id"
          content shouldBe testMessage
          inReplyToId shouldBe replyToId
          account.id shouldBe "test-account-id"
          account.username shouldBe "testuser"
          account.qualifiedName shouldBe "testuser@localhost"
        }
      }

      test("client can dismiss a notification") {
        val client = factory()
        val notificationId = "test-notification-id"

        val result = client.dismissNotification(notificationId)

        result.shouldBeRight {
          logger.warn(it.cause) { "Unable to dismiss notification" }
          it.toString()
        }
        dismissedNotifications shouldContain notificationId
      }

      test("client can get notification flow") {
        val client = factory()

        val notifications = client.notificationFlow()
          .take(2)
          .toList()

        notifications.size shouldBe 2

        val firstNotification = notifications[0]
        firstNotification.shouldBeRight { problem ->
          logger.warn(problem.cause) { "Unable to get notification" }
          problem.toString()
        }.apply {
          id shouldBe "1"
          status.id shouldBe "test-status-id-1"
          status.content shouldBe "Test notification 1"
          status.account.id shouldBe "test-account-id"
          status.account.username shouldBe "testuser"
          status.account.qualifiedName shouldBe "testuser@localhost"
        }

        val secondNotification = notifications[1]
        secondNotification.shouldBeRight { problem ->
          logger.warn(problem.cause) { "Unable to get notification" }
          problem.toString()
        }.apply {
          id shouldBe "2"
          status.id shouldBe "test-status-id-2"
          status.content shouldBe "Test notification 2"
          status.account.id shouldBe "test-account-id"
          status.account.username shouldBe "testuser"
          status.account.qualifiedName shouldBe "testuser@localhost"
        }
      }

      test("client can page through notifications using several requests") {
        val client = factory()

        // Set a small limit to force pagination
        val pageSize = 3

        // Take more notifications than are returned in a single page
        val notificationEithers = client.notificationFlow(pageSize)
          .take(8)
          .toList()

        // Verify we got the expected number of notifications
        notificationEithers.size shouldBe 8
        val notifications = notificationEithers.map { it.shouldBeRight() }

        // Verify the IDs of the notifications we received
        val notificationIds = notifications.map { it.id }

        // We should have received notifications 1 through 8 in order
        notificationIds shouldBe (1..8).map { it.toString() }

        // Verify the content of a few notifications to ensure they're correct
        notifications[0].apply {
          id shouldBe "1"
          status.content shouldBe "Test notification 1"
        }

        notifications[4].apply {
          id shouldBe "5"
          status.content shouldBe "Test notification 5"
        }

        notifications[7].apply {
          id shouldBe "8"
          status.content shouldBe "Test notification 8"
        }
      }
    }
  }
})
