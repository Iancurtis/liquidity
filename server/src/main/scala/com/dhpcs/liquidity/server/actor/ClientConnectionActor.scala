package com.dhpcs.liquidity.server.actor

import java.util.UUID

import akka.NotUsed
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{BinaryMessage, Message => WsMessage}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.dhpcs.liquidity.actor.protocol._
import com.dhpcs.liquidity.model._
import com.dhpcs.liquidity.proto
import com.dhpcs.liquidity.proto.binding.ProtoBinding
import com.dhpcs.liquidity.server.actor.ClientConnectionActor._
import com.dhpcs.liquidity.ws.protocol._

import scala.concurrent.duration._

object ClientConnectionActor {

  def props(ip: RemoteAddress, zoneValidatorShardRegion: ActorRef, pingInterval: FiniteDuration)(
      upstream: ActorRef): Props =
    Props(
      classOf[ClientConnectionActor],
      ip,
      zoneValidatorShardRegion,
      pingInterval,
      upstream
    )

  def webSocketFlow(props: ActorRef => Props)(implicit factory: ActorRefFactory,
                                              mat: Materializer): Flow[WsMessage, WsMessage, NotUsed] =
    InFlow
      .via(actorFlow[proto.ws.protocol.ServerMessage, proto.ws.protocol.ClientMessage](props))
      .via(OutFlow)

  private final val InFlow: Flow[WsMessage, proto.ws.protocol.ServerMessage, NotUsed] =
    Flow[WsMessage].flatMapConcat(wsMessage =>
      for (byteString <- wsMessage.asBinaryMessage match {
             case BinaryMessage.Streamed(dataStream) => dataStream.fold(ByteString.empty)((acc, data) => acc ++ data)
             case BinaryMessage.Strict(data)         => Source.single(data)
           }) yield proto.ws.protocol.ServerMessage.parseFrom(byteString.toArray))

  private final val OutFlow: Flow[proto.ws.protocol.ClientMessage, WsMessage, NotUsed] =
    Flow[proto.ws.protocol.ClientMessage].map(
      serverMessage => BinaryMessage(ByteString(serverMessage.toByteArray))
    )

  private def actorFlow[In, Out](props: ActorRef => Props)(implicit factory: ActorRefFactory,
                                                           mat: Materializer): Flow[In, Out, NotUsed] = {
    val (outActor, publisher) = Source
      .actorRef[Out](bufferSize = 16, overflowStrategy = OverflowStrategy.fail)
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
    Flow.fromSinkAndSource(
      Sink.actorRefWithAck(
        factory.actorOf(
          Props(new Actor {
            val flowActor: ActorRef =
              context.watch(context.actorOf(props(outActor).withDeploy(Deploy.local)))
            override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
            override def receive: Receive = {
              case _: Status.Success | _: Status.Failure =>
                flowActor ! PoisonPill
              case _: Terminated =>
                context.stop(self)
                outActor ! Status.Success(())
              case other =>
                flowActor.forward(other)
            }
          }).withDeploy(Deploy.local)
        ),
        onInitMessage = ActorSinkInit,
        ackMessage = ActorSinkAck,
        onCompleteMessage = Status.Success(())
      ),
      Source.fromPublisher(publisher)
    )
  }

  case object ActorSinkInit
  case object ActorSinkAck

  private case object PublishStatus

  private object PingGeneratorActor {

    def props(pingInterval: FiniteDuration): Props = Props(classOf[PingGeneratorActor], pingInterval)

    case object FrameReceivedEvent
    case object FrameSentEvent
    case object SendPingCommand

  }

  private class PingGeneratorActor(pingInterval: FiniteDuration) extends Actor {

    import com.dhpcs.liquidity.server.actor.ClientConnectionActor.PingGeneratorActor._

    context.setReceiveTimeout(pingInterval)

    override def receive: Receive = {
      case ReceiveTimeout                      => context.parent ! SendPingCommand
      case FrameReceivedEvent | FrameSentEvent => ()
    }
  }
}

class ClientConnectionActor(ip: RemoteAddress,
                            zoneValidatorShardRegion: ActorRef,
                            pingInterval: FiniteDuration,
                            upstream: ActorRef)
    extends PersistentActor
    with ActorLogging
    with AtLeastOnceDelivery {

  import com.dhpcs.liquidity.server.actor.ClientConnectionActor.PingGeneratorActor._
  import context.dispatcher

  private[this] val mediator          = DistributedPubSub(context.system).mediator
  private[this] val publishStatusTick = context.system.scheduler.schedule(0.seconds, 30.seconds, self, PublishStatus)
  private[this] val pingGeneratorActor =
    context.actorOf(PingGeneratorActor.props(pingInterval).withDeploy(Deploy.local))

  private[this] var nextExpectedMessageSequenceNumbers = Map.empty[ActorRef, Long].withDefaultValue(1L)
  private[this] var commandSequenceNumbers             = Map.empty[ZoneId, Long].withDefaultValue(1L)
  private[this] var pendingDeliveries                  = Map.empty[ZoneId, Set[Long]].withDefaultValue(Set.empty)

  override def persistenceId: String = self.path.name

  override def preStart(): Unit = {
    super.preStart()
    log.info(s"Started for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def postStop(): Unit = {
    publishStatusTick.cancel()
    super.postStop()
    log.info(s"Stopped for ${ip.toOption.getOrElse("unknown IP")}")
  }

  override def receiveCommand: Receive = waitingForActorSinkInit

  private[this] def waitingForActorSinkInit: Receive =
    publishStatus(maybePublicKey = None) orElse sendPingCommand orElse {
      case ActorSinkInit =>
        sender() ! ActorSinkAck
        context.become(waitingForBeginKeyOwnershipProof)
    }

  private[this] def waitingForBeginKeyOwnershipProof: Receive =
    publishStatus(maybePublicKey = None) orElse sendPingCommand orElse {
      case serverMessage: proto.ws.protocol.ServerMessage =>
        sender() ! ActorSinkAck
        pingGeneratorActor ! FrameReceivedEvent
        serverMessage.message match {
          case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
              _: proto.ws.protocol.ServerMessage.Message.CompleteKeyOwnershipProof |
              _: proto.ws.protocol.ServerMessage.Message.Command |
              _: proto.ws.protocol.ServerMessage.Message.Response) =>
            log.warning(s"Stopping due to unexpected message; required BeginKeyOwnershipProof but received $other")
            context.stop(self)
          case proto.ws.protocol.ServerMessage.Message.BeginKeyOwnershipProof(protoBeginKeyOwnershipProof) =>
            val protoKeyOwnershipProofNonce = createKeyOwnershipNonceMessage()
            sendClientMessage(
              proto.ws.protocol.ClientMessage(
                proto.ws.protocol.ClientMessage.Message.KeyOwnershipProofNonce(protoKeyOwnershipProofNonce)
              )
            )
            context.become(
              waitingForCompleteKeyOwnershipProof(protoBeginKeyOwnershipProof, protoKeyOwnershipProofNonce))
        }
    }

  private[this] def waitingForCompleteKeyOwnershipProof(
      beginKeyOwnershipProofMessage: proto.ws.protocol.BeginKeyOwnershipProof,
      keyOwnershipProofNonceMessage: proto.ws.protocol.KeyOwnershipProofNonce): Receive =
    publishStatus(maybePublicKey = None) orElse sendPingCommand orElse {
      case serverMessage: proto.ws.protocol.ServerMessage =>
        sender() ! ActorSinkAck
        pingGeneratorActor ! FrameReceivedEvent
        serverMessage.message match {
          case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
              _: proto.ws.protocol.ServerMessage.Message.BeginKeyOwnershipProof |
              _: proto.ws.protocol.ServerMessage.Message.Command |
              _: proto.ws.protocol.ServerMessage.Message.Response) =>
            log.warning(s"Stopping due to unexpected message; required CompleteKeyOwnershipProof but received $other")
            context.stop(self)
          case proto.ws.protocol.ServerMessage.Message.CompleteKeyOwnershipProof(completeKeyOwnershipProofMessage) =>
            if (!isValidKeyOwnershipProof(beginKeyOwnershipProofMessage,
                                          keyOwnershipProofNonceMessage,
                                          completeKeyOwnershipProofMessage)) {
              log.warning(
                s"Stopping due to invalid key ownership proof for public key $beginKeyOwnershipProofMessage " +
                  s"and nonce $keyOwnershipProofNonceMessage; received signature was $completeKeyOwnershipProofMessage")
              context.stop(self)
            } else
              context.become(receiveActorSinkMessages(PublicKey(beginKeyOwnershipProofMessage.publicKey.toByteArray)))
        }
    }

  private[this] def receiveActorSinkMessages(publicKey: PublicKey): Receive =
    publishStatus(maybePublicKey = Some(publicKey)) orElse commandReceivedConfirmation orElse sendPingCommand orElse {
      case serverMessage: proto.ws.protocol.ServerMessage =>
        sender() ! ActorSinkAck
        pingGeneratorActor ! FrameReceivedEvent
        serverMessage.message match {
          case other @ (proto.ws.protocol.ServerMessage.Message.Empty |
              _: proto.ws.protocol.ServerMessage.Message.BeginKeyOwnershipProof |
              _: proto.ws.protocol.ServerMessage.Message.CompleteKeyOwnershipProof) =>
            log.warning(s"Stopping due to unexpected message; required Command or Response but received $other")
            context.stop(self)
          case proto.ws.protocol.ServerMessage.Message.Command(protoCommand) =>
            protoCommand.command match {
              case proto.ws.protocol.ServerMessage.Command.Command.Empty =>
              case proto.ws.protocol.ServerMessage.Command.Command.ZoneCommand(protoZoneCommand) =>
                val zoneCommand = ProtoBinding[ZoneCommand, proto.ws.protocol.ZoneCommand.ZoneCommand]
                  .asScala(protoZoneCommand.zoneCommand)
                handleZoneCommand(publicKey, zoneCommand, protoCommand.correlationId)
            }
          case proto.ws.protocol.ServerMessage.Message.Response(protoResponse) =>
            protoResponse.response match {
              case proto.ws.protocol.ServerMessage.Response.Response.Empty =>
                log.warning("Stopping due to unexpected message; required PingResponse but received Empty")
                context.stop(self)
              case proto.ws.protocol.ServerMessage.Response.Response.PingResponse(_) =>
            }
        }
      case ZoneAlreadyExists(createZoneCommand, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          // Converting it back to the WS type is what ensures we'll then end up generating a fresh ZoneId when we then
          // convert it back again _from_ the WS type.
          handleZoneCommand(publicKey,
                            ProtoBinding[ZoneCommand, ZoneValidatorMessage.ZoneCommand].asScala(createZoneCommand),
                            correlationId)
        )
      case ZoneResponseWithIds(response, correlationId, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          sendServerResponse(ProtoBinding[ZoneResponse, ZoneValidatorMessage.ZoneResponse].asScala(response),
                             correlationId)
        )
      case ZoneNotificationWithIds(notification, sequenceNumber, deliveryId) =>
        exactlyOnce(sequenceNumber, deliveryId)(
          // asScala perhaps isn't the best name; we're just converting from the ZoneValidatorActor protocol equivalent.
          sendClientNotification(
            ProtoBinding[ZoneNotification, ZoneValidatorMessage.ZoneNotification].asScala(notification))
        )
      case ZoneRestarted(zoneId) =>
        nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers.filterKeys(validator =>
          ZoneId(UUID.fromString(validator.path.name)) != zoneId)
        commandSequenceNumbers = commandSequenceNumbers - zoneId
        pendingDeliveries(zoneId).foreach(confirmDelivery)
        pendingDeliveries = pendingDeliveries - zoneId
        sendClientNotification(ZoneTerminatedNotification(zoneId))
    }

  override def receiveRecover: Receive = Actor.emptyBehavior

  private[this] def publishStatus(maybePublicKey: Option[PublicKey]): Receive = {
    case PublishStatus =>
      for (publicKey <- maybePublicKey)
        mediator ! Publish(
          ClientStatusTopic,
          ActiveClientSummary(publicKey)
        )
  }

  private[this] def commandReceivedConfirmation: Receive = {
    case ZoneCommandReceivedConfirmation(zoneId, deliveryId) =>
      confirmDelivery(deliveryId)
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) - deliveryId))
      if (pendingDeliveries(zoneId).isEmpty) {
        pendingDeliveries = pendingDeliveries - zoneId
      }
  }

  private[this] def sendPingCommand: Receive = {
    case SendPingCommand => sendClientCommand(PingCommand, correlationId = -1)
  }

  private[this] def handleZoneCommand(publicKey: PublicKey, zoneCommand: ZoneCommand, correlationId: Long) = {
    // asProto perhaps isn't the best name; we're just converting to the ZoneValidatorActor protocol equivalent.
    val protoZoneCommand = ProtoBinding[ZoneCommand, ZoneValidatorMessage.ZoneCommand].asProto(zoneCommand)
    val zoneId           = protoZoneCommand.zoneId
    val sequenceNumber   = commandSequenceNumbers(zoneId)
    commandSequenceNumbers = commandSequenceNumbers + (zoneId -> (sequenceNumber + 1))
    deliver(zoneValidatorShardRegion.path) { deliveryId =>
      pendingDeliveries = pendingDeliveries + (zoneId -> (pendingDeliveries(zoneId) + deliveryId))
      AuthenticatedZoneCommandWithIds(
        publicKey,
        protoZoneCommand,
        correlationId,
        sequenceNumber,
        deliveryId
      )
    }
  }

  private[this] def exactlyOnce(sequenceNumber: Long, deliveryId: Long)(body: => Unit): Unit = {
    val nextExpectedMessageSequenceNumber = nextExpectedMessageSequenceNumbers(sender())
    if (sequenceNumber <= nextExpectedMessageSequenceNumber) {
      sender() ! MessageReceivedConfirmation(deliveryId)
    }
    if (sequenceNumber == nextExpectedMessageSequenceNumber) {
      nextExpectedMessageSequenceNumbers = nextExpectedMessageSequenceNumbers + (sender() -> (sequenceNumber + 1))
      body
    }
  }

  private[this] def sendClientCommand(clientCommand: ClientCommand, correlationId: Long): Unit =
    sendClientMessage(
      proto.ws.protocol.ClientMessage(
        proto.ws.protocol.ClientMessage.Message.Command(
          proto.ws.protocol.ClientMessage.Command(
            correlationId,
            clientCommand match {
              case PingCommand =>
                proto.ws.protocol.ClientMessage.Command.Command.PingCommand(
                  com.google.protobuf.ByteString.EMPTY
                )
            }
          )
        )
      )
    )

  private[this] def sendClientNotification(clientNotification: ClientNotification): Unit =
    sendClientMessage(
      proto.ws.protocol.ClientMessage(
        proto.ws.protocol.ClientMessage.Message.Notification(
          proto.ws.protocol.ClientMessage.Notification(
            clientNotification match {
              case zoneNotification: ZoneNotification =>
                proto.ws.protocol.ClientMessage.Notification.Notification.ZoneNotification(
                  proto.ws.protocol.ZoneNotification(
                    ProtoBinding[ZoneNotification, proto.ws.protocol.ZoneNotification.ZoneNotification]
                      .asProto(zoneNotification)
                  )
                )

            }
          )
        )
      )
    )

  private[this] def sendServerResponse(serverResponse: ServerResponse, correlationId: Long): Unit =
    sendClientMessage(
      proto.ws.protocol.ClientMessage(
        proto.ws.protocol.ClientMessage.Message.Response(
          proto.ws.protocol.ClientMessage.Response(
            correlationId,
            serverResponse match {
              case zoneResponse: ZoneResponse =>
                proto.ws.protocol.ClientMessage.Response.Response.ZoneResponse(
                  proto.ws.protocol.ZoneResponse(
                    ProtoBinding[ZoneResponse, proto.ws.protocol.ZoneResponse.ZoneResponse]
                      .asProto(zoneResponse)
                  )
                )
            }
          )
        )
      )
    )

  private[this] def sendClientMessage(clientMessage: proto.ws.protocol.ClientMessage): Unit = {
    upstream ! clientMessage
    pingGeneratorActor ! FrameSentEvent
  }
}
