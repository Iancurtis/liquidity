package com.dhpcs.liquidity.server

import java.net.InetAddress

import akka.NotUsed
import akka.actor.ActorPath
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.`Remote-Address`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, RemoteAddress, StatusCodes}
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.stream.scaladsl.{Flow, Source}
import com.dhpcs.liquidity.model.{AccountId, PublicKey, Zone, ZoneId}
import com.dhpcs.liquidity.proto.model.ZoneState
import com.dhpcs.liquidity.server.HttpController.GeneratedMessageEnvelope
import com.dhpcs.liquidity.server.actor.ClientsMonitorActor.ActiveClientsSummary
import com.dhpcs.liquidity.server.actor.ZonesMonitorActor.ActiveZonesSummary
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import org.scalatest.{FreeSpec, Inside}
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future

class HttpControllerSpec extends FreeSpec with HttpController with ScalatestRouteTest with Inside {

  override def testConfig: Config = ConfigFactory.defaultReference()

  "The HttpController" - {
    "will provide version information" in {
      val getRequest = RequestBuilding.Get("/version")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        val keys = entityAs[JsObject].value.keySet
        assert(keys.contains("version"))
        assert(keys.contains("builtAtString"))
        assert(keys.contains("builtAtMillis"))
      }
    }
    "will accept WebSocket connections" in {
      val wsProbe = WSProbe()
      WS("/ws", wsProbe.flow)
        .addHeader(
          `Remote-Address`(RemoteAddress(InetAddress.getLoopbackAddress))
        ) ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(isWebSocketUpgrade === true)
        val message = "Hello"
        wsProbe.sendMessage(message)
        wsProbe.expectMessage(message)
        wsProbe.sendCompletion()
        wsProbe.expectCompletion()
      }
    }
    "will provide status information" in {
      val getRequest = RequestBuilding.Get("/status")
      getRequest ~> httpRoutes(enableClientRelay = true) ~> check {
        assert(status === StatusCodes.OK)
        assert(contentType === ContentType(`application/json`))
        assert(
          entityAs[JsObject] === Json.obj(
            "clients" -> Json.obj(
              "count"                 -> 0,
              "publicKeyFingerprints" -> Seq.empty[String]
            ),
            "zones" -> Json.obj(
              "count" -> 0,
              "zones" -> Seq.empty[JsObject]
            )
          ))
      }
    }
  }

  override protected[this] def events(persistenceId: String,
                                      fromSequenceNr: Long,
                                      toSequenceNr: Long): Source[GeneratedMessageEnvelope, NotUsed] =
    Source.empty[GeneratedMessageEnvelope]

  override protected[this] def zoneState(zoneId: ZoneId): Future[ZoneState] =
    Future.successful(ZoneState(zone = None, balances = Map.empty, clientConnections = Map.empty))

  override protected[this] def webSocketApi(ip: RemoteAddress): Flow[Message, Message, NotUsed] = Flow[Message]

  override protected[this] def getActiveClientsSummary: Future[ActiveClientsSummary] =
    Future.successful(ActiveClientsSummary(Seq.empty))

  override protected[this] def getActiveZonesSummary: Future[ActiveZonesSummary] =
    Future.successful(ActiveZonesSummary(Set.empty))

  override protected[this] def getZone(zoneId: ZoneId): Future[Option[Zone]] = Future.successful(None)

  override protected[this] def getBalances(zoneId: ZoneId): Future[Map[AccountId, BigDecimal]] =
    Future.successful(Map.empty)

  override protected[this] def getClients(zoneId: ZoneId): Future[Map[ActorPath, (Long, PublicKey)]] =
    Future.successful(Map.empty)

}
