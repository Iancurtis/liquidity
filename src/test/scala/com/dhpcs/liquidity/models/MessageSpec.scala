package com.dhpcs.liquidity.models

import java.security.KeyPairGenerator
import java.util.UUID

import com.dhpcs.json.JsResultUniformity
import com.dhpcs.jsonrpc.{JsonRpcNotificationMessage, JsonRpcRequestMessage, JsonRpcResponseMessage}
import com.google.common.io.BaseEncoding
import org.scalatest.OptionValues._
import org.scalatest._
import play.api.data.validation.ValidationError
import play.api.libs.json._

class MessageSpec extends FunSpec with Matchers {

  def commandReadError(jsonRpcRequestMessage: JsonRpcRequestMessage, jsError: Option[JsError]) =
    it(s"should fail to decode with error $jsError") {
      val jsResult = Command.read(jsonRpcRequestMessage)
      jsError.fold(jsResult shouldBe empty)(
        jsResult.value should equal(_)(after being ordered[Command])
      )
    }

  def commandRead(implicit jsonRpcRequestMessage: JsonRpcRequestMessage, command: Command) =
    it(s"should decode to $command") {
      Command.read(jsonRpcRequestMessage) should be(Some(JsSuccess(command)))
    }

  def commandWrite(implicit command: Command, id: Either[String, Int], jsonRpcRequestMessage: JsonRpcRequestMessage) =
    it(s"should encode to $jsonRpcRequestMessage") {
      Command.write(command, id) should be(jsonRpcRequestMessage)
    }

  def ordered[A] = new JsResultUniformity[A]

  describe("A Command") {
    describe("with an invalid method") {
      it should behave like commandReadError(
        JsonRpcRequestMessage(
          "invalidMethod",
          Right(Json.obj()),
          Right(0)
        ),
        None
      )
    }
    describe("of type CreateZoneCommand") {
      describe("with params of the wrong type") {
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            Left(Json.arr()),
            Right(0)
          ),
          Some(
            JsError(List(
              (__, List(ValidationError("command parameters must be named")))
            ))
          )
        )
      }
      describe("with empty params") {
        it should behave like commandReadError(
          JsonRpcRequestMessage(
            "createZone",
            Right(Json.obj()),
            Right(0)
          ),
          Some(
            JsError(List(
              (__ \ "name", List(ValidationError("error.path.missing"))),
              (__ \ "zoneType", List(ValidationError("error.path.missing"))),
              (__ \ "equityHolderMember", List(ValidationError("error.path.missing"))),
              (__ \ "equityHolderAccount", List(ValidationError("error.path.missing")))
            ))
          )
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      implicit val createZoneCommand = CreateZoneCommand(
        "Dave's zone",
        "test",
        Member("Banker", PublicKey(publicKeyBytes)),
        Account("Bank", Set.empty)
      )
      implicit val id = Right(0)
      implicit val jsonRpcRequestMessage = JsonRpcRequestMessage(
        "createZone",
        Right(
          Json.obj(
            "name" -> "Dave's zone",
            "zoneType" -> "test",
            "equityHolderMember" -> Json.obj(
              "name" -> "Banker",
              "publicKey" -> BaseEncoding.base64.encode(publicKeyBytes)
            ),
            "equityHolderAccount" -> Json.obj(
              "name" -> "Bank",
              "owners" -> JsArray()
            )
          )
        ),
        Right(0)
      )
      it should behave like commandRead
      it should behave like commandWrite
    }
  }

  def responseReadError(jsonRpcResponseMessage: JsonRpcResponseMessage, method: String, jsError: JsError) =
    it(s"should fail to decode with error $jsError") {
      (Response.read(jsonRpcResponseMessage, method) should equal(jsError))(after being ordered[Response])
    }

  def responseRead(implicit jsonRpcResponseMessage: JsonRpcResponseMessage, method: String, response: Response) =
    it(s"should decode to $response") {
      Response.read(jsonRpcResponseMessage, method) should be(JsSuccess(response))
    }

  def responseWrite(implicit response: Response,
                    id: Either[String, Int],
                    jsonRpcResponseMessage: JsonRpcResponseMessage) =
    it(s"should encode to $jsonRpcResponseMessage") {
      Response.write(response, id) should be(jsonRpcResponseMessage)
    }

  describe("A Response") {
    describe("of type CreateZoneResponse") {
      describe("with empty params") {
        it should behave like responseReadError(
          JsonRpcResponseMessage(
            Right(Json.obj()),
            Some(Right(0))
          ),
          "createZone",
          JsError(List(
            (__ \ "zoneId", List(ValidationError("error.path.missing"))),
            (__ \ "equityHolderMemberId", List(ValidationError("error.path.missing"))),
            (__ \ "equityHolderAccountId", List(ValidationError("error.path.missing")))
          ))
        )
      }
    }
    implicit val createZoneResponse = CreateZoneResponse(
      ZoneId(UUID.fromString("158842d1-38c7-4ad3-ab83-d4c723c9aaf3")),
      MemberId(UUID.fromString("1dcf2284-ceb8-47b4-9b2c-daf3ce21f8e3")),
      AccountId(UUID.fromString("c881da49-f009-427e-8b90-47f15e11ac0d"))
    )
    implicit val id = Right(0)
    implicit val jsonRpcResponseMessage = JsonRpcResponseMessage(
      Right(
        Json.obj(
          "zoneId" -> "158842d1-38c7-4ad3-ab83-d4c723c9aaf3",
          "equityHolderMemberId" -> "1dcf2284-ceb8-47b4-9b2c-daf3ce21f8e3",
          "equityHolderAccountId" -> "c881da49-f009-427e-8b90-47f15e11ac0d"
        )
      ),
      Some(
        Right(0)
      )
    )
    implicit val method = "createZone"
    it should behave like responseRead
    it should behave like responseWrite
  }

  def notificationReadError(jsonRpcNotificationMessage: JsonRpcNotificationMessage, jsError: Option[JsError]) =
    it(s"should fail to decode with error $jsError") {
      val notificationJsResult = Notification.read(jsonRpcNotificationMessage)
      jsError.fold(notificationJsResult shouldBe empty)(
        notificationJsResult.value should equal(_)(after being ordered[Notification])
      )
    }

  def notificationRead(implicit jsonRpcNotificationMessage: JsonRpcNotificationMessage, notification: Notification) =
    it(s"should decode to $notification") {
      Notification.read(jsonRpcNotificationMessage) should be(Some(JsSuccess(notification)))
    }

  def notificationWrite(implicit notification: Notification, jsonRpcNotificationMessage: JsonRpcNotificationMessage) =
    it(s"should encode to $jsonRpcNotificationMessage") {
      Notification.write(notification) should be(jsonRpcNotificationMessage)
    }

  describe("A Notification") {
    describe("with an invalid method") {
      it should behave like notificationReadError(
        JsonRpcNotificationMessage(
          "invalidMethod",
          Right(Json.obj())
        ),
        None
      )
    }
    describe("of type ClientJoinedZoneNotification") {
      describe("with params of the wrong type") {
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "clientJoinedZone",
            Left(Json.arr())
          ),
          Some(
            JsError(List(
              (__, List(ValidationError("notification parameters must be named")))
            ))
          )
        )
      }
      describe("with empty params") {
        it should behave like notificationReadError(
          JsonRpcNotificationMessage(
            "clientJoinedZone",
            Right(Json.obj())
          ),
          Some(
            JsError(List(
              (__ \ "zoneId", List(ValidationError("error.path.missing"))),
              (__ \ "publicKey", List(ValidationError("error.path.missing")))
            ))
          )
        )
      }
      val publicKeyBytes = KeyPairGenerator.getInstance("RSA").generateKeyPair.getPublic.getEncoded
      implicit val clientJoinedZoneNotification = ClientJoinedZoneNotification(
        ZoneId(UUID.fromString("a52e984e-f0aa-4481-802b-74622cb3f6f6")),
        PublicKey(publicKeyBytes)
      )
      implicit val jsonRpcNotificationMessage = JsonRpcNotificationMessage(
        "clientJoinedZone",
        Right(
          Json.obj(
            "zoneId" -> "a52e984e-f0aa-4481-802b-74622cb3f6f6",
            "publicKey" -> BaseEncoding.base64.encode(publicKeyBytes)
          )
        )
      )
      it should behave like notificationRead
      it should behave like notificationWrite
    }
  }

}