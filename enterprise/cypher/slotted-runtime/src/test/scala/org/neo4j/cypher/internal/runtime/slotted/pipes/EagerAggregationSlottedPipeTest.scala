/*
 * Copyright (c) 2018-2020 "Graph Foundation"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of ONgDB.
 *
 * ONgDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.slotted.pipes

import org.neo4j.cypher.internal.compatibility.v3_6.runtime.{Slot, SlotConfiguration}
import org.neo4j.cypher.internal.runtime.interpreted.QueryStateHelper
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{CountStar, Expression}
import org.neo4j.cypher.internal.runtime.slotted.expressions.ReferenceFromSlot
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values.{intValue, longValue}
import org.neo4j.cypher.internal.v3_6.util.symbols._
import org.neo4j.cypher.internal.v3_6.util.test_helpers.CypherFunSuite

class EagerAggregationSlottedPipeTest extends CypherFunSuite with SlottedPipeTestHelper {
  test("should aggregate count(*) on two grouping columns") {
    val slots = SlotConfiguration.empty
      .newReference("a", nullable = false, CTInteger)
      .newReference("b", nullable = false, CTInteger)
      .newReference("count(*)", nullable = false, CTInteger)

    def source = FakeSlottedPipe(List(
      Map[String, Any]("a" -> 1, "b" -> 1),
      Map[String, Any]("a" -> 1, "b" -> 1),
      Map[String, Any]("a" -> 1, "b" -> 2),
      Map[String, Any]("a" -> 2, "b" -> 2)), slots)

    val grouping = createReturnItemsFor(slots,"a", "b")
    val aggregation = Map(slots("count(*)").offset -> CountStar())
    def aggregationPipe = EagerAggregationSlottedPipe(source, slots, grouping, aggregation)()

    testableResult(aggregationPipe.createResults(QueryStateHelper.empty), slots) should be(List(
      Map[String, AnyValue]("a" -> intValue(1), "b" -> intValue(1), "count(*)" -> longValue(2)),
      Map[String, AnyValue]("a" -> intValue(1), "b" -> intValue(2), "count(*)" -> longValue(1)),
      Map[String, AnyValue]("a" -> intValue(2), "b" -> intValue(2), "count(*)" -> longValue(1))
    ))
  }

  private def createReturnItemsFor(slots: SlotConfiguration, names: String*): Map[Slot, Expression] = names.map(k => slots(k) -> ReferenceFromSlot(slots(k).offset)).toMap

}
