package com.dhpcs.liquidity.server

import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.`Tls-Session-Info`
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.{ContentType, HttpEntity, RemoteAddress}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.pattern.{ask, gracefulStop}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, ReadJournal}
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, Materializer, TLSClientAuth}
import akka.util.Timeout
import com.dhpcs.liquidity.model.PublicKey
import com.dhpcs.liquidity.server.LiquidityServer._
import com.dhpcs.liquidity.server.actors.ClientsMonitorActor.{ActiveClientsSummary, GetActiveClientsSummary}
import com.dhpcs.liquidity.server.actors.ZonesMonitorActor.{ActiveZonesSummary, GetActiveZonesSummary, GetZoneCount, ZoneCount}
import com.dhpcs.liquidity.server.actors.{ClientConnectionActor, ClientsMonitorActor, ZoneValidatorActor, ZonesMonitorActor}
import com.typesafe.config.{Config, ConfigFactory}
import okio.ByteString
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object LiquidityServer {
  private final val KeyStoreFilename = "liquidity.dhpcs.com.keystore.p12"
  private final val EnabledCipherSuites = Seq(
    // Recommended by https://typesafehub.github.io/ssl-config/CipherSuites.html#id4
    "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
    "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"
  )
  private final val EnabledProtocols = Seq(
    "TLSv1.2",
    "TLSv1.1",
    // For Android 4.1 (see https://www.ssllabs.com/ssltest/viewClient.html?name=Android&version=4.1.1)
    "TLSv1"
  )
  private final val RequiredClientKeyLength = 2048

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load
    implicit val system = ActorSystem("liquidity")
    implicit val mat = ActorMaterializer()
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    val zoneValidatorShardRegion = ClusterSharding(system).start(
      typeName = ZoneValidatorActor.ShardName,
      entityProps = ZoneValidatorActor.props,
      settings = ClusterShardingSettings(system),
      extractEntityId = ZoneValidatorActor.extractEntityId,
      extractShardId = ZoneValidatorActor.extractShardId
    )
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(
      getClass.getClassLoader.getResourceAsStream(KeyStoreFilename),
      Array.emptyCharArray
    )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(
      keyStore,
      Array.emptyCharArray
    )
    val server = new LiquidityServer(
      config,
      readJournal,
      zoneValidatorShardRegion,
      keyManagerFactory.getKeyManagers
    )
    sys.addShutdownHook {
      Await.result(server.shutdown(), Duration.Inf)
      Await.result(system.terminate(), Duration.Inf)
    }
  }
}

class LiquidityServer(config: Config,
                      readJournal: ReadJournal with CurrentPersistenceIdsQuery,
                      zoneValidatorShardRegion: ActorRef,
                      keyManagers: Array[KeyManager])
                     (implicit system: ActorSystem, mat: Materializer) extends LiquidityService {

  import system.dispatcher

  private[this] val clientsMonitorActor = system.actorOf(ClientsMonitorActor.props, "clients-monitor")
  private[this] val zonesMonitorActor = system.actorOf(ZonesMonitorActor.props(readJournal), "zones-monitor")

  private[this] val httpsConnectionContext = {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagers,
      Array(new X509TrustManager {
        override def getAcceptedIssuers: Array[X509Certificate] = Array()

        override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      }),
      null
    )
    ConnectionContext.https(
      sslContext,
      enabledCipherSuites = Some(EnabledCipherSuites),
      enabledProtocols = Some(EnabledProtocols),
      clientAuth = Some(TLSClientAuth.want)
    )
  }

  private[this] val binding = Http().bindAndHandle(
    route,
    config.getString("liquidity.http.interface"),
    config.getInt("liquidity.http.port"),
    httpsConnectionContext
  )

  private[this] val keepAliveInterval = FiniteDuration(
    config.getDuration("liquidity.http.keep-alive-interval", SECONDS),
    SECONDS
  )

  def shutdown(): Future[Unit] = {
    def stop(target: ActorRef): Future[Unit] =
      gracefulStop(target, 5.seconds).flatMap {
        case true => Future.successful(())
        case false => stop(target)
      }
    for {
      binding <- binding
      _ <- binding.unbind()
      _ <- stop(clientsMonitorActor)
      _ <- stop(zonesMonitorActor)
    } yield ()
  }

  override protected[this] def getStatus: ToResponseMarshallable = {
    def clientsStatus(activeClientsSummary: ActiveClientsSummary): JsObject =
      Json.obj(
        "count" -> activeClientsSummary.activeClientSummaries.size,
        "publicKeyFingerprints" -> activeClientsSummary.activeClientSummaries.map {
          case ClientConnectionActor.ActiveClientSummary(publicKey) => publicKey.fingerprint
        }.sorted
      )
    def activeZonesStatus(activeZonesSummary: ActiveZonesSummary): JsObject =
      Json.obj(
        "count" -> activeZonesSummary.activeZoneSummaries.size,
        "zones" -> activeZonesSummary.activeZoneSummaries.toSeq.sortBy(_.zoneId.id).map {
          case ZoneValidatorActor.ActiveZoneSummary(
          zoneId,
          metadata,
          members, accounts,
          transactions,
          clientConnections
          ) =>
            Json.obj(
              "zoneIdFingerprint" -> ByteString.encodeUtf8(zoneId.id.toString).sha256.hex,
              "metadata" -> metadata,
              "members" -> Json.obj("count" -> members.size),
              "accounts" -> Json.obj("count" -> accounts.size),
              "transactions" -> Json.obj("count" -> transactions.size),
              "clientConnections" -> Json.obj(
                "count" -> clientConnections.size,
                "publicKeyFingerprints" -> clientConnections.map(_.fingerprint).toSeq.sorted
              )
            )
        }
      )
    implicit val timeout = Timeout(5.seconds)
    for {
      activeClientsSummary <- (clientsMonitorActor ? GetActiveClientsSummary).mapTo[ActiveClientsSummary]
      activeZonesSummary <- (zonesMonitorActor ? GetActiveZonesSummary).mapTo[ActiveZonesSummary]
      totalZonesCount <- (zonesMonitorActor ? GetZoneCount).mapTo[ZoneCount]
    } yield HttpEntity(
      ContentType(`application/json`),
      Json.prettyPrint(Json.obj(
        "clients" -> clientsStatus(activeClientsSummary),
        "totalZonesCount" -> totalZonesCount.count,
        "activeZones" -> activeZonesStatus(activeZonesSummary)
      ))
    )
  }

  override protected[this] def extractClientPublicKey(ip: RemoteAddress)(route: PublicKey => Route): Route =
    headerValueByType[`Tls-Session-Info`]()(sessionInfo =>
      sessionInfo.peerCertificates.headOption.map(_.getPublicKey).fold[Route](
        ifEmpty = complete(
          BadRequest,
          s"Client certificate not presented by ${ip.toOption.getOrElse("unknown")}"
        )
      ) {
        case rsaPublicKey: RSAPublicKey =>
          if (rsaPublicKey.getModulus.bitLength != RequiredClientKeyLength) {
            complete(
              BadRequest,
              s"Invalid client public key length from ${ip.toOption.getOrElse("unknown")}"
            )
          } else {
            route(
              PublicKey(rsaPublicKey.getEncoded)
            )
          }
        case _ =>
          complete(
            BadRequest,
            s"Invalid client public key type from ${ip.toOption.getOrElse("unknown")}"
          )
      }
    )

  override protected[this] def webSocketApi(ip: RemoteAddress, publicKey: PublicKey): Flow[Message, Message, NotUsed] =
    ClientConnectionActor.webSocketFlow(ip, publicKey, zoneValidatorShardRegion, keepAliveInterval)
}
