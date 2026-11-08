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
package com.github.j5ik2o.pekko.persistence.dynamodb.state.schema

/** One durable state row as it is stored.
  *
  * `revision` is the value written to the revision column, not the revision the caller passed. The v1 and v2 bindings
  * still write different values; D1 settles which one is correct.
  */
final case class StateRow(
    persistenceId: String,
    revision: Long,
    payload: Array[Byte],
    serializerId: Int,
    serializerManifest: Option[String],
    ordering: Long,
    tag: Option[String]
)
