/*
 * Copyright 2026 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v1

import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, DdbValue }

import java.nio.ByteBuffer
import scala.jdk.CollectionConverters._

private[v1] object V1AttributeValues {

  def toSdk(value: DdbValue): AttributeValue = value match {
    case DdbValue.Str(string)   => new AttributeValue().withS(string)
    case DdbValue.Num(number)   => new AttributeValue().withN(number.bigDecimal.toString)
    case DdbValue.Bin(bytes)    => new AttributeValue().withB(ByteBuffer.wrap(bytes.toArray))
    case DdbValue.Bool(boolean) => new AttributeValue().withBOOL(Boolean.box(boolean))
    case DdbValue.StrSet(strings) =>
      new AttributeValue().withSS(strings.toVector.asJava)
    case DdbValue.NumSet(numbers) =>
      new AttributeValue().withNS(numbers.toVector.map(_.bigDecimal.toString).asJava)
    case DdbValue.BinSet(binaries) =>
      new AttributeValue().withBS(binaries.toVector.map(bytes => ByteBuffer.wrap(bytes.toArray)).asJava)
    case DdbValue.L(elements) =>
      new AttributeValue().withL(elements.map(toSdk).asJava)
    case DdbValue.M(entries) =>
      new AttributeValue().withM(entries.map { case (key, entryValue) => key -> toSdk(entryValue) }.toMap.asJava)
    case DdbValue.Null =>
      new AttributeValue().withNULL(Boolean.box(true))
  }

  def fromSdk(value: AttributeValue): DdbValue = {
    if (isTrue(value.getNULL)) {
      DdbValue.Null
    } else if (value.getS != null) {
      DdbValue.Str(value.getS)
    } else if (value.getN != null) {
      DdbValue.Num(BigDecimal(value.getN))
    } else if (value.getB != null) {
      DdbValue.Bin(bytes(value.getB))
    } else if (value.getBOOL != null) {
      DdbValue.Bool(value.getBOOL.booleanValue())
    } else if (value.getSS != null) {
      DdbValue.StrSet(value.getSS.asScala.toSet)
    } else if (value.getNS != null) {
      DdbValue.NumSet(value.getNS.asScala.map(BigDecimal(_)).toSet)
    } else if (value.getBS != null) {
      DdbValue.BinSet(value.getBS.asScala.map(bytes).toSet)
    } else if (value.getL != null) {
      DdbValue.L(value.getL.asScala.toVector.map(fromSdk))
    } else if (value.getM != null) {
      DdbValue.M(value.getM.asScala.toVector.map { case (key, entryValue) => key -> fromSdk(entryValue) }.sortBy(_._1))
    } else {
      DdbValue.Null
    }
  }

  def toItem(values: AttributeMap): java.util.Map[String, AttributeValue] =
    values.entries.map { case (name, value) => name.value -> toSdk(value) }.toMap.asJava

  def fromItem(values: java.util.Map[String, AttributeValue]): AttributeMap =
    Option(values)
      .map { entries =>
        AttributeMap(
          entries.asScala.toVector.map { case (name, value) => AttributeName(name) -> fromSdk(value) }.sortBy {
            case (name, _) => name.value
          }
        )
      }
      .getOrElse(AttributeMap.empty)

  private def bytes(buffer: ByteBuffer): Vector[Byte] = {
    val copy  = buffer.duplicate()
    val array = new Array[Byte](copy.remaining())
    copy.get(array)
    array.toVector
  }

  private def isTrue(value: java.lang.Boolean): Boolean = value != null && value.booleanValue()
}
