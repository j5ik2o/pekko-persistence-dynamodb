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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb.v2

import com.github.j5ik2o.pekko.persistence.dynamodb.ddb.{ AttributeMap, AttributeName, DdbValue }
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import scala.jdk.CollectionConverters._

private[v2] object V2AttributeValues {

  def toSdk(value: DdbValue): AttributeValue = value match {
    case DdbValue.Str(string)   => AttributeValue.builder().s(string).build()
    case DdbValue.Num(number)   => AttributeValue.builder().n(number.bigDecimal.toString).build()
    case DdbValue.Bin(bytes)    => AttributeValue.builder().b(SdkBytes.fromByteArray(bytes.toArray)).build()
    case DdbValue.Bool(boolean) => AttributeValue.builder().bool(Boolean.box(boolean)).build()
    case DdbValue.StrSet(strings) =>
      AttributeValue.builder().ss(strings.toVector.asJava).build()
    case DdbValue.NumSet(numbers) =>
      AttributeValue.builder().ns(numbers.toVector.map(_.bigDecimal.toString).asJava).build()
    case DdbValue.BinSet(binaries) =>
      AttributeValue.builder().bs(binaries.toVector.map(bytes => SdkBytes.fromByteArray(bytes.toArray)).asJava).build()
    case DdbValue.L(elements) =>
      AttributeValue.builder().l(elements.map(toSdk).asJava).build()
    case DdbValue.M(entries) =>
      AttributeValue
        .builder().m(entries.map { case (key, entryValue) => key -> toSdk(entryValue) }.toMap.asJava).build()
    case DdbValue.Null =>
      AttributeValue.builder().nul(Boolean.box(true)).build()
  }

  def fromSdk(value: AttributeValue): DdbValue = {
    if (value.nul() != null) {
      DdbValue.Null
    } else if (value.s() != null) {
      DdbValue.Str(value.s())
    } else if (value.n() != null) {
      DdbValue.Num(BigDecimal(value.n()))
    } else if (value.b() != null) {
      DdbValue.Bin(value.b().asByteArray().toVector)
    } else if (value.bool() != null) {
      DdbValue.Bool(value.bool().booleanValue())
    } else if (value.hasSs()) {
      DdbValue.StrSet(value.ss().asScala.toSet)
    } else if (value.hasNs()) {
      DdbValue.NumSet(value.ns().asScala.map(BigDecimal(_)).toSet)
    } else if (value.hasBs()) {
      DdbValue.BinSet(value.bs().asScala.map(_.asByteArray().toVector).toSet)
    } else if (value.hasL()) {
      DdbValue.L(value.l().asScala.toVector.map(fromSdk))
    } else if (value.hasM()) {
      DdbValue.M(value.m().asScala.toVector.map { case (key, entryValue) => key -> fromSdk(entryValue) }.sortBy(_._1))
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
}
