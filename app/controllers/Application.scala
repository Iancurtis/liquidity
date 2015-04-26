package controllers

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util

import actors.ClientConnection.AuthenticatedInboundMessage
import actors.ClientIdentity.PostedInboundAuthenticatedMessage
import actors.ClientIdentityManager.CreateConnectionForIdentity
import actors.{Actors, ClientIdentityManager}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import models._
import org.apache.commons.codec.binary.{Base64, Hex}
import play.api.Play.current
import play.api.libs.EventSource
import play.api.libs.EventSource.EventDataExtractor
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.mvc._
import sun.security.provider.X509Factory

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object Application extends Controller {

  object PublicKey {

    implicit val publicKeyReads =
      __.read[String].map(publicKeyBase64 => new PublicKey(Base64.decodeBase64(publicKeyBase64)))

    implicit val publicKeyWrites = Writes[PublicKey] {
      publicKey => JsString(Base64.encodeBase64String(publicKey.value))

    }

    def getPublicKey(headers: Headers) = Try {
      new PublicKey(
        CertificateFactory.getInstance("X.509").generateCertificate(
          new ByteArrayInputStream(
            Base64.decodeBase64(
              headers("X-SSL-Client-Cert").stripPrefix(X509Factory.BEGIN_CERT).stripSuffix(X509Factory.END_CERT)
            )
          )
        ).asInstanceOf[X509Certificate].getPublicKey.getEncoded
      )
    }

  }

  class PublicKey(val value: Array[Byte]) {

    lazy val base64encoded = Base64.encodeBase64String(value)

    lazy val fingerprint = Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(value))

    override def equals(that: Any) = that match {

      case that: PublicKey => util.Arrays.equals(this.value, that.value)

      case _ => false

    }

    override def toString = base64encoded

  }

  case class PostedInboundMessage(connectionNumber: Int, inboundMessage: InboundMessage)

  object PostedInboundMessage {

    implicit val postedInboundMessageReads = Json.reads[PostedInboundMessage]

  }

  def postAction = Action(parse.json) { request =>

    val triedPublicKey = PublicKey.getPublicKey(request.headers)

    triedPublicKey match {

      case Failure(exception) =>

        BadRequest(Json.obj("status" -> "KO", "error" -> s"public key not given: ${exception.getMessage}"))

      case Success(publicKey) =>

        request.body.validate[PostedInboundMessage].fold(
          invalid = errors => BadRequest(Json.obj("status" -> "KO", "error" -> JsError.toFlatJson(errors))),
          valid = postedInboundMessage => {
            Actors.clientIdentityManager ! PostedInboundAuthenticatedMessage(
              postedInboundMessage.connectionNumber,
              AuthenticatedInboundMessage(publicKey, postedInboundMessage.inboundMessage)
            )
            Ok(Json.obj("status" -> "OK"))
          }
        )

    }

  }

  implicit val ConnectTimeout = Timeout(ClientIdentityManager.StoppingChildRetryDelay * 10)

  implicit val outboundMessageEventDataExtractor = EventDataExtractor[OutboundMessage](
    message => Json.stringify(Json.toJson(message))
  )

  def getAction = Action.async { request =>

    val triedPublicKey = PublicKey.getPublicKey(request.headers)

    triedPublicKey match {

      case Failure(exception) =>

        Future.successful(
          BadRequest(Json.obj("status" -> "KO", "error" -> s"public key not given: ${exception.getMessage}"))
        )

      case Success(publicKey) =>

        (Actors.clientIdentityManager ? CreateConnectionForIdentity(publicKey, request.remoteAddress))
          .mapTo[Enumerator[OutboundMessage]]
          .map(enumerator =>
          Ok.feed(enumerator.through(EventSource())).as("text/event-stream")
          ).recover { case _: AskTimeoutException => GatewayTimeout }

    }

  }

}