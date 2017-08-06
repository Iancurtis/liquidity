package com.dhpcs.liquidity.actor.protocol

import com.dhpcs.liquidity.proto.binding.ProtoBinding

object ProtoBindings {

  implicit final val JoinZoneCommandProtoBinding
    : ProtoBinding[JoinZoneCommand.type, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => JoinZoneCommand
    )

  implicit final val QuitZoneCommandProtoBinding
    : ProtoBinding[QuitZoneCommand.type, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => QuitZoneCommand
    )

  implicit final val UnitProtoBinding: ProtoBinding[Unit, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => ()
    )

  implicit final val ZoneTerminatedNotificationProtoBinding
    : ProtoBinding[ZoneTerminatedNotification.type, com.google.protobuf.ByteString, Any] =
    ProtoBinding.instance(
      _ => com.google.protobuf.ByteString.EMPTY,
      (_, _) => ZoneTerminatedNotification
    )

}