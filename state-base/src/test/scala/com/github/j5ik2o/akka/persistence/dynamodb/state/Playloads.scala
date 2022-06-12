package com.github.j5ik2o.akka.persistence.dynamodb.state

import akka.serialization._

final case class MyPayload(data: String)

class MyPayloadSerializer extends Serializer {
  val MyPayloadClass: Class[MyPayload] = classOf[MyPayload]

  def identifier: Int          = 77123
  def includeManifest: Boolean = true

  def toBinary(o: AnyRef): Array[Byte] = o match {
    case MyPayload(data) => s"${data}".getBytes("UTF-8")
    case _               => throw new Exception("Unknown object for serialization")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest match {
    case Some(MyPayloadClass) => MyPayload(s"${new String(bytes, "UTF-8")}")
    case Some(c)              => throw new Exception(s"unexpected manifest ${c}")
    case None                 => throw new Exception("no manifest")
  }
}