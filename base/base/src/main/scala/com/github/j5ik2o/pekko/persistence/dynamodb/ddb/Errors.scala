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

final case class EncodeError(detail: String)

sealed trait DecodeError

object DecodeError {
  final case class Missing(name: AttributeName) extends DecodeError
  final case class WrongType(name: AttributeName, expected: AttributeKind) extends DecodeError
  final case class InvalidValue(name: AttributeName, detail: String) extends DecodeError
}

sealed trait KeyError

object KeyError {
  final case class Missing(name: AttributeName) extends KeyError
  final case class WrongType(name: AttributeName, expected: AttributeKind) extends KeyError
}
