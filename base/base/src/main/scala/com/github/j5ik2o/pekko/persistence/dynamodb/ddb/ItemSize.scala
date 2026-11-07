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
package com.github.j5ik2o.pekko.persistence.dynamodb.ddb

import java.nio.charset.StandardCharsets

/** Estimates the DynamoDB item size of an encoded item.
  *
  * The estimate follows the documented DynamoDB rule. Attribute names count as UTF-8 bytes. A string counts its UTF-8
  * bytes, a number counts one byte per two significant digits, a binary value counts its bytes, and a boolean or null
  * counts one byte. A list or map adds three bytes plus one byte per element or pair. Numbers round up, so the estimate
  * never under-estimates.
  */
object ItemSize {

  val MaxItemSizeBytes: Int = 400 * 1024

  val MaxTransactionSizeBytes: Long = 4L * 1024 * 1024

  val MaxItemsPerTransaction: Int = 100

  private val ListOverheadBytes: Int = 3
  private val MapOverheadBytes: Int  = 3
  private val BooleanSizeBytes: Int  = 1

  def of(values: AttributeMap): Int =
    values.entries.map { case (name, value) => stringSize(name.value) + valueSize(value) }.sum

  def stringSize(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length

  def numberSize(value: BigDecimal): Int = {
    val text = value.bigDecimal.stripTrailingZeros.toPlainString
    text.length / 2 + 2
  }

  private def valueSize(value: DdbValue): Int = value match {
    case DdbValue.Str(string) => stringSize(string)
    case DdbValue.Num(number) => numberSize(number)
    case DdbValue.Bin(bytes)  => bytes.length
    case DdbValue.Bool(_)     => BooleanSizeBytes
    case DdbValue.Null        => BooleanSizeBytes
    case DdbValue.StrSet(strings) =>
      strings.toVector.map(stringSize).sum
    case DdbValue.NumSet(numbers) =>
      numbers.toVector.map(numberSize).sum
    case DdbValue.BinSet(binaries) =>
      binaries.toVector.map(_.length).sum
    case DdbValue.L(elements) =>
      ListOverheadBytes + elements.size + elements.map(valueSize).sum
    case DdbValue.M(entries) =>
      MapOverheadBytes + entries.size + entries.map { case (key, entryValue) =>
        stringSize(key) + valueSize(entryValue)
      }.sum
  }
}
