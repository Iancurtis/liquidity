package com.dhpcs.liquidity.server

import java.io.{ByteArrayInputStream, File}
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import akka.util.ByteString
import cats.data.Validated
import cats.effect.IO
import cats.instances.list._
import cats.syntax.traverse._
import com.dhpcs.liquidity.model.ProtoBindings._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.LiquidityServerSpec._
import com.dhpcs.liquidity.ws.protocol.ProtoBindings._
import com.dhpcs.liquidity.ws.protocol._
import com.google.protobuf.CodedInputStream
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import doobie._
import doobie.implicits._
import org.scalactic.TripleEqualsSupport.Spread
import org.scalactic.source.Position
import org.scalatest.Inside._
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.libs.json.{JsString, JsValue, Json}
import scalapb.json4s.JsonFormat

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.sys.process.{Process, ProcessBuilder}

class LiquidityServerSpec
    extends FreeSpec
    with BeforeAndAfterAll
    with Eventually
    with IntegrationPatience
    with ScalaFutures {

  "LiquidityServer" - {
    "accepts and projects create zone commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      ()
    }
    "accepts join zone commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      joinZone(createdZone.id, createdZone).futureValue
      ()
    }
    "accepts quit zone commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      joinZone(createdZone.id, createdZone).futureValue
      quitZone(createdZone.id).futureValue
      ()
    }
    "accepts and projects change zone name commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val changedName = changeZoneName(createdZone.id).futureValue
      zoneNameChanged(createdZone, changedName)
      ()
    }
    "accepts and projects create member commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      memberCreated(createdZone, createdMember)
      ()
    }
    "accepts and projects update member commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val updatedMember =
        updateMember(zoneWithCreatedMember.id, createdMember).futureValue
      memberUpdated(zoneWithCreatedMember, updatedMember)
      ()
    }
    "accepts and projects create account commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val (createdAccount, _) =
        createAccount(zoneWithCreatedMember.id, owner = createdMember.id).futureValue
      accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
      ()
    }
    "accepts and projects update account commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val (createdAccount, _) =
        createAccount(zoneWithCreatedMember.id, owner = createdMember.id).futureValue
      val (zoneWithCreatedAccount, _) =
        accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
      val updatedAccount =
        updateAccount(zoneWithCreatedAccount.id, createdAccount).futureValue
      accountUpdated(zoneWithCreatedAccount, updatedAccount)
      ()
    }
    "accepts and projects add transaction commands" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val createdMember = createMember(createdZone.id).futureValue
      val zoneWithCreatedMember = memberCreated(createdZone, createdMember)
      val (createdAccount, _) = createAccount(
        zoneWithCreatedMember.id,
        owner = createdMember.id).futureValue
      val (zoneWithCreatedAccount, updatedBalances) =
        accountCreated(zoneWithCreatedMember, createdBalances, createdAccount)
      val addedTransaction = addTransaction(zoneWithCreatedAccount.id,
                                            zoneWithCreatedAccount,
                                            to = createdAccount.id).futureValue
      transactionAdded(zoneWithCreatedAccount,
                       updatedBalances,
                       addedTransaction)
      ()
    }
    "sends PingCommands when left idle" in {
      val (createdZone, createdBalances) = createZone().futureValue
      zoneCreated(createdZone, createdBalances)
      val zoneNotificationTestProbe =
        zoneNotificationSource(createdZone.id, selfSignedJwt)
          .runWith(TestSink.probe)
      inside(zoneNotificationTestProbe.requestNext()) {
        case ZoneStateNotification(_, _) => ()
      }
      inside(zoneNotificationTestProbe.requestNext()) {
        case ClientJoinedNotification(_, _) => ()
      }
      zoneNotificationTestProbe.within(10.seconds)(
        inside(zoneNotificationTestProbe.requestNext()) {
          case PingNotification(()) => ()
        }
      )
      zoneNotificationTestProbe.cancel()
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    assert(dockerCompose(projectName, "up", "-d", "--remove-orphans").! === 0)
    val (_, mysqlPort) =
      externalDockerComposeServicePorts(projectName, "mysql", 3306).head
    val transactor = Transactor.fromDriverManager[IO](
      driver = "com.mysql.jdbc.Driver",
      url =
        s"jdbc:mysql://localhost:$mysqlPort/?" +
          "useSSL=false&" +
          "cacheCallableStmts=true&" +
          "cachePrepStmts=true&" +
          "cacheResultSetMetadata=true&" +
          "cacheServerConfiguration=true&" +
          "useLocalSessionState=true&" +
          "useLocalSessionState=true&" +
          "useServerPrepStmts=true",
      user = "root",
      pass = ""
    )
    val connectionTest = for (_ <- sql"SELECT 1".query[Int].unique) yield ()
    eventually(
      connectionTest
        .transact(transactor)
        .unsafeRunSync()
    )
    execSqlFile("schemas/journal.sql")
      .transact(transactor)
      .unsafeRunSync()
    execSqlFile("schemas/analytics.sql")
      .transact(transactor)
      .unsafeRunSync()
    execSqlFile("schemas/administrators.sql")
      .transact(transactor)
      .unsafeRunSync()
    addAdministrator(PublicKey(rsaPublicKey.getEncoded))
      .transact(transactor)
      .unsafeRunSync()
    val pc = patienceConfig.copy(timeout = scaled(Span(30, Seconds)))
    eventually {
      def statusIsOk(serviceName: String): Unit = {
        val (_, akkaHttpPort) =
          externalDockerComposeServicePorts(projectName, serviceName, 8080).head
        val response = Http()
          .singleRequest(
            HttpRequest(
              uri = Uri(s"http://localhost:$akkaHttpPort/status/terse")
            )
          )
          .futureValue
        assert(response.status === StatusCodes.OK)
        assert(
          Unmarshal(response.entity).to[JsValue].futureValue === JsString("OK"))
        ()
      }
      statusIsOk("zone-host")
      statusIsOk("client-relay")
      statusIsOk("analytics")
    }(pc, Position.here)
  }

  private[this] val projectName = UUID.randomUUID().toString

  private[this] implicit val system: ActorSystem = ActorSystem()
  private[this] implicit val mat: Materializer = ActorMaterializer()
  private[this] implicit val ec: ExecutionContext = system.dispatcher

  private[this] def createZone()(implicit ec: ExecutionContext)
    : Future[(Zone, Map[AccountId, BigDecimal])] =
    for (zoneResponse <- sendCreateZoneCommand(
           CreateZoneCommand(
             equityOwnerPublicKey = PublicKey(rsaPublicKey.getEncoded),
             equityOwnerName = Some("Dave"),
             equityOwnerMetadata = None,
             equityAccountName = None,
             equityAccountMetadata = None,
             name = Some("Dave's Game"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case CreateZoneResponse(Validated.Valid(zone)) =>
            assert(zone.accounts.size === 1)
            assert(zone.members.size === 1)
            val equityAccount = zone.accounts(zone.equityAccountId)
            val equityAccountOwner =
              zone.members(equityAccount.ownerMemberIds.head)
            assert(
              equityAccount === Account(
                equityAccount.id,
                ownerMemberIds = Set(equityAccountOwner.id),
                name = None,
                metadata = None
              )
            )
            assert(
              equityAccountOwner === Member(
                equityAccountOwner.id,
                ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)),
                name = Some("Dave"),
                metadata = None
              )
            )
            assert(
              zone.created === Spread(
                pivot = Instant.now().toEpochMilli,
                tolerance = 5000
              )
            )
            assert(
              zone.expires === zone.created + java.time.Duration
                .ofDays(30)
                .toMillis
            )
            assert(zone.transactions === Map.empty)
            assert(zone.name === Some("Dave's Game"))
            assert(zone.metadata === None)
            (
              zone,
              Map(zone.equityAccountId -> BigDecimal(0))
            )

          case _ =>
            fail()
        }

  private[this] def zoneCreated(zone: Zone,
                                balances: Map[AccountId, BigDecimal])
    : (Zone, Map[AccountId, BigDecimal]) =
    (
      awaitZoneProjection(zone),
      awaitZoneBalancesProjection(zone.id, balances)
    )

  private[this] def zoneNotificationSource(
      zoneId: ZoneId,
      selfSignedJwt: String): Source[ZoneNotification, NotUsed] = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    val byteSource = Source
      .fromFuture(
        Http()
          .singleRequest(
            HttpRequest(
              uri = Uri(s"http://localhost:$akkaHttpPort").withPath(
                Uri.Path("/zone") / zoneId.value
              ),
              headers = Seq(
                Authorization(OAuth2BearerToken(selfSignedJwt)),
                Accept(
                  MediaRange(
                    MediaType.customBinary(mainType = "application",
                                           subType = "x-protobuf",
                                           comp = MediaType.NotCompressible)
                  )
                )
              )
            )
          )
      )
      .flatMapConcat { response =>
        assert(response.status === StatusCodes.OK)
        assert(
          response.entity.contentType === ContentType(
            MediaType.customBinary(mainType = "application",
                                   subType = "x-protobuf",
                                   comp = MediaType.NotCompressible,
                                   params = Map("delimited" -> "true"))
          )
        )
        response.entity.dataBytes
      }
      .flatMapConcat(Source(_))
    val delimitedByteArraySource = byteSource.statefulMapConcat { () =>
      // Messages are length-delimited by varints where the MSB is set for all
      // but the last byte. (See
      // https://developers.google.com/protocol-buffers/docs/encoding#varints).
      sealed abstract class State
      final case class ReadingSize(sizeBytes: Array[Byte]) extends State
      final case class ReadingData(dataBytes: Array[Byte], position: Int)
          extends State
      var state: State = ReadingSize(Array.emptyByteArray)
      byte =>
        state match {
          case ReadingSize(sizeBytes) =>
            val updatedSizeBytes = sizeBytes :+ byte
            if ((byte & 0x80) == 0) {
              val size = CodedInputStream.readRawVarint32(
                updatedSizeBytes.head.toInt,
                new ByteArrayInputStream(updatedSizeBytes.tail)
              )
              state = ReadingData(Array.fill(size)(0), position = 0)
            } else {
              state = ReadingSize(updatedSizeBytes)
            }
            Seq.empty

          case ReadingData(dataBytes, position) =>
            dataBytes(position) = byte
            if (position == dataBytes.length - 1) {
              state = ReadingSize(Array.emptyByteArray)
              Seq(dataBytes)
            } else {
              state = ReadingData(dataBytes, position + 1)
              Seq.empty
            }
        }
    }
    delimitedByteArraySource.map { delimitedByteArray =>
      val protoZoneNotification =
        proto.ws.protocol.ZoneNotification.parseFrom(delimitedByteArray)
      val zoneNotification =
        ProtoBinding[ZoneNotification, proto.ws.protocol.ZoneNotification, Any]
          .asScala(protoZoneNotification)(())
      zoneNotification
    }
  }

  private[this] def joinZone(zoneId: ZoneId, zone: Zone)(
      implicit ec: ExecutionContext): Future[Unit] = {
    val zoneNotificationTestProbe =
      zoneNotificationSource(zoneId, selfSignedJwt).runWith(TestSink.probe)
    inside(zoneNotificationTestProbe.requestNext()) {
      case ZoneStateNotification(_, _) => ()
    }
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           JoinZoneCommand
         ))
      yield
        zoneResponse match {
          case JoinZoneResponse(Validated.Valid(zoneAndConnectedClients)) =>
            val (_zone, _connectedClients) = zoneAndConnectedClients
            assert(_zone === zone)
            assert(
              _connectedClients.values.toSet ===
                Set(PublicKey(rsaPublicKey.getEncoded))
            )
            inside(zoneNotificationTestProbe.requestNext()) {
              case ClientJoinedNotification(_, publicKey) =>
                assert(publicKey === PublicKey(rsaPublicKey.getEncoded))
            }
            zoneNotificationTestProbe.cancel()
            ()

          case _ =>
            fail()
        }
  }

  private[this] def quitZone(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Future[Unit] =
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           QuitZoneCommand
         )) yield {
      assert(zoneResponse === QuitZoneResponse(Validated.valid(())))
      ()
    }

  private[this] def changeZoneName(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Future[Option[String]] = {
    val changedName = None
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           ChangeZoneNameCommand(name = changedName)
         )) yield {
      assert(zoneResponse === ChangeZoneNameResponse(Validated.valid(())))
      changedName
    }
  }

  private[this] def zoneNameChanged(zone: Zone, name: Option[String]): Zone =
    awaitZoneProjection(zone.copy(name = name))

  private[this] def createMember(zoneId: ZoneId)(
      implicit ec: ExecutionContext): Future[Member] =
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           CreateMemberCommand(
             ownerPublicKeys = Set(PublicKey(rsaPublicKey.getEncoded)),
             name = Some("Jenny"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case CreateMemberResponse(Validated.Valid(member)) =>
            assert(
              member.ownerPublicKeys === Set(
                PublicKey(rsaPublicKey.getEncoded)))
            assert(member.name === Some("Jenny"))
            assert(member.metadata === None)
            member

          case _ =>
            fail()
        }

  private[this] def memberCreated(zone: Zone, member: Member): Zone =
    awaitZoneProjection(
      zone.copy(
        members = zone.members + (member.id -> member)
      )
    )

  private[this] def updateMember(zoneId: ZoneId, member: Member)(
      implicit ec: ExecutionContext): Future[Member] = {
    val updatedMember = member.copy(name = None)
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           UpdateMemberCommand(
             updatedMember
           )
         )) yield {
      assert(zoneResponse === UpdateMemberResponse(Validated.valid(())))
      updatedMember
    }
  }

  private[this] def memberUpdated(zone: Zone, member: Member): Zone =
    awaitZoneProjection(
      zone.copy(
        members = zone.members + (member.id -> member)
      )
    )

  private[this] def createAccount(zoneId: ZoneId, owner: MemberId)(
      implicit ec: ExecutionContext): Future[(Account, BigDecimal)] =
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           CreateAccountCommand(
             ownerMemberIds = Set(owner),
             name = Some("Jenny's Account"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case CreateAccountResponse(Validated.Valid(account)) =>
            assert(account.ownerMemberIds === Set(owner))
            assert(account.name === Some("Jenny's Account"))
            account -> BigDecimal(0)

          case _ =>
            fail()
        }

  private[this] def accountCreated(
      zone: Zone,
      balances: Map[AccountId, BigDecimal],
      account: Account): (Zone, Map[AccountId, BigDecimal]) =
    (
      awaitZoneProjection(
        zone.copy(
          accounts = zone.accounts + (account.id -> account)
        )
      ),
      awaitZoneBalancesProjection(
        zone.id,
        balances + (account.id -> BigDecimal(0))
      )
    )

  private[this] def updateAccount(zoneId: ZoneId, account: Account)(
      implicit ec: ExecutionContext): Future[Account] = {
    val updatedAccount = account.copy(name = None)
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           UpdateAccountCommand(
             actingAs = account.ownerMemberIds.head,
             updatedAccount
           )
         )) yield {
      assert(zoneResponse === UpdateAccountResponse(Validated.valid(())))
      updatedAccount
    }
  }

  private[this] def accountUpdated(zone: Zone, account: Account): Zone =
    awaitZoneProjection(
      zone.copy(
        accounts = zone.accounts + (account.id -> account)
      )
    )

  private[this] def addTransaction(zoneId: ZoneId, zone: Zone, to: AccountId)(
      implicit ec: ExecutionContext): Future[Transaction] =
    for (zoneResponse <- sendZoneCommand(
           zoneId,
           AddTransactionCommand(
             actingAs = zone.accounts(zone.equityAccountId).ownerMemberIds.head,
             from = zone.equityAccountId,
             to = to,
             value = BigDecimal(5000),
             description = Some("Jenny's Lottery Win"),
             metadata = None
           )
         ))
      yield
        zoneResponse match {
          case AddTransactionResponse(Validated.Valid(transaction)) =>
            assert(transaction.from === zone.equityAccountId)
            assert(transaction.to === to)
            assert(transaction.value === BigDecimal(5000))
            assert(
              transaction.creator === zone
                .accounts(zone.equityAccountId)
                .ownerMemberIds
                .head)
            assert(
              transaction.created === Spread(
                pivot = Instant.now().toEpochMilli,
                tolerance = 5000
              )
            )
            assert(transaction.description === Some("Jenny's Lottery Win"))
            assert(transaction.metadata === None)
            transaction

          case _ =>
            fail()
        }

  private[this] def transactionAdded(
      zone: Zone,
      balances: Map[AccountId, BigDecimal],
      transaction: Transaction): (Zone, Map[AccountId, BigDecimal]) = {
    (
      awaitZoneProjection(
        zone.copy(
          transactions = zone.transactions + (transaction.id -> transaction)
        )
      ),
      awaitZoneBalancesProjection(
        zone.id,
        balances +
          (transaction.from -> (balances(transaction.from) - transaction.value)) +
          (transaction.to -> (balances(transaction.to) + transaction.value))
      )
    )
  }

  private[this] def sendCreateZoneCommand(createZoneCommand: CreateZoneCommand)(
      implicit ec: ExecutionContext): Future[ZoneResponse] =
    sendZoneCommand(
      Uri.Path.Empty,
      ProtoBinding[CreateZoneCommand,
                   proto.ws.protocol.ZoneCommand.CreateZoneCommand,
                   Any]
        .asProto(createZoneCommand)(())
        .toByteArray
    )

  private[this] def sendZoneCommand(zoneId: ZoneId, zoneCommand: ZoneCommand)(
      implicit ec: ExecutionContext): Future[ZoneResponse] =
    sendZoneCommand(
      Uri.Path / zoneId.value,
      ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand, Any]
        .asProto(zoneCommand)(())
        .toByteArray
    )

  private[this] def sendZoneCommand(zoneSubPath: Uri.Path, entity: Array[Byte])(
      implicit ec: ExecutionContext): Future[ZoneResponse] = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    for {
      httpResponse <- Http().singleRequest(
        HttpRequest(
          method = HttpMethods.PUT,
          uri = Uri(s"http://localhost:$akkaHttpPort")
            .withPath(Uri.Path("/zone") ++ zoneSubPath),
          headers = Seq(
            Authorization(OAuth2BearerToken(selfSignedJwt)),
            Accept(
              MediaRange(
                MediaType.customBinary(mainType = "application",
                                       subType = "x-protobuf",
                                       comp = MediaType.NotCompressible)
              )
            )
          ),
          entity = HttpEntity(
            ContentType(
              MediaType.customBinary(mainType = "application",
                                     subType = "x-protobuf",
                                     comp = MediaType.NotCompressible)
            ),
            entity
          )
        )
      )
      _ = assert(httpResponse.status === StatusCodes.OK)
      byteString <- Unmarshal(httpResponse.entity).to[ByteString]
      protoZoneResponse = proto.ws.protocol.ZoneResponse.parseFrom(
        byteString.toArray
      )
    } yield
      ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse, Any].asScala(
        protoZoneResponse
      )(())
  }

  private[this] def awaitZoneProjection(zone: Zone): Zone = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    eventually {
      val response = Http()
        .singleRequest(
          HttpRequest(
            uri = Uri(s"http://localhost:$akkaHttpPort")
              .withPath(Uri.Path("/analytics/zone") / zone.id.value)
          ).withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
        )
        .futureValue
      assert(response.status === StatusCodes.OK)
      assert(
        Unmarshal(response.entity).to[JsValue].futureValue ===
          Json.parse(
            JsonFormat.toJsonString(
              ProtoBinding[Zone, proto.model.Zone, Any].asProto(zone)(())
            )
          )
      )
    }
    zone
  }

  private[this] def awaitZoneBalancesProjection(
      zoneId: ZoneId,
      balances: Map[AccountId, BigDecimal]): Map[AccountId, BigDecimal] = {
    val (_, akkaHttpPort) =
      externalDockerComposeServicePorts(projectName, "client-relay", 8080).head
    eventually {
      val response = Http()
        .singleRequest(
          HttpRequest(
            uri = Uri(s"http://localhost:$akkaHttpPort")
              .withPath(Uri.Path("/analytics/zone") / zoneId.value / "balances")
          ).withHeaders(Authorization(OAuth2BearerToken(selfSignedJwt)))
        )
        .futureValue
      assert(response.status === StatusCodes.OK)
      assert(
        Unmarshal(response.entity)
          .to[JsValue]
          .futureValue
          .as[Map[String, BigDecimal]] === balances.map {
          case (accountId, balance) => accountId.value -> balance
        })
    }
    balances
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    assert(dockerCompose(projectName, "logs", "mysql").! === 0)
    assert(dockerCompose(projectName, "logs", "zone-host").! === 0)
    assert(dockerCompose(projectName, "logs", "client-relay").! === 0)
    assert(dockerCompose(projectName, "logs", "analytics").! === 0)
    assert(dockerCompose(projectName, "down", "--volumes").! === 0)
    super.afterAll()
  }
}

object LiquidityServerSpec {

  private val (rsaPrivateKey: RSAPrivateKey, rsaPublicKey: RSAPublicKey) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair
    (keyPair.getPrivate, keyPair.getPublic)
  }
  private val selfSignedJwt =
    JwtJson.encode(
      Json.obj(
        "sub" -> okio.ByteString.of(rsaPublicKey.getEncoded: _*).base64()
      ),
      rsaPrivateKey,
      JwtAlgorithm.RS256
    )

  private def externalDockerComposeServicePorts(
      projectName: String,
      serviceName: String,
      internalPort: Int
  ): Map[String, Int] = {
    val serviceInstances =
      dockerCompose(projectName, "ps", "-q", serviceName).!!.split('\n')
    val ExternalIpAndPortRegex = "^(.+):(\\d+)$".r
    serviceInstances.zipWithIndex.map {
      case (instanceId, index) =>
        val ExternalIpAndPortRegex(_, externalPort) =
          dockerCompose(projectName,
                        "port",
                        "--index",
                        (index + 1).toString,
                        serviceName,
                        internalPort.toString).!!.trim
        instanceId -> externalPort.toInt
    }.toMap
  }

  private def dockerCompose(projectName: String,
                            commandArgs: String*): ProcessBuilder =
    Process(
      command = Seq(
        "docker-compose",
        "--project-name",
        projectName,
        "--file",
        new File("server/src/it/docker-compose.yml").getCanonicalPath) ++
        commandArgs,
      cwd = None,
      extraEnv = "TAG" -> BuildInfo.version
    )

  private def execSqlFile(path: String): ConnectionIO[Unit] =
    scala.io.Source
      .fromFile(path)
      .mkString("")
      .split(';')
      .filter(!_.trim.isEmpty)
      .map(Fragment.const0(_).update.run)
      .toList
      .sequence
      .map(_ => ())

  private def addAdministrator(publicKey: PublicKey): ConnectionIO[Unit] = {
    import SqlAdministratorStore.PublicKeyMeta
    for (_ <- sql"""
             INSERT INTO liquidity_administrators.administrators (public_key)
               VALUES ($publicKey)
           """.update.run)
      yield ()
  }
}
