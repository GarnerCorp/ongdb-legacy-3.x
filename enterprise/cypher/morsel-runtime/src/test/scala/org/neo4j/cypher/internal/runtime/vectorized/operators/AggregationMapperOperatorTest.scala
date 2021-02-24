/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.cypher.internal.runtime.vectorized.operators

import org.neo4j.cypher.internal.compatibility.v3_5.runtime.RefSlot
import org.neo4j.cypher.internal.runtime.vectorized.{Morsel, MorselExecutionContext, QueryState}
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.storable.Values.stringValue
import org.neo4j.cypher.internal.v3_5.util.symbols.CTAny
import org.neo4j.cypher.internal.v3_5.util.test_helpers.CypherFunSuite

class AggregationMapperOperatorTest extends CypherFunSuite {

  test("single grouping key aggregation") {
    // Given
    val numberOfLongs = 1
    val numberOfReferences = 2
    val groupSlot = RefSlot(0, nullable = false, CTAny)
    val aggregation = new AggregationMapperOperator(
                                                    Array(AggregationOffsets(1, 1, DummyEvenNodeIdAggregation(0))),
                                                    Array(GroupingOffsets(groupSlot, groupSlot,
                                                                          new DummyExpression(stringValue("A"), stringValue("B")))))
    val longs = Array[Long](0,1,2,3,4,5,6,7,8,9)
    val refs = new Array[AnyValue](2*longs.length)
    val data = new Morsel(longs, refs, longs.length)

    // When
    aggregation.operate(MorselExecutionContext(data, numberOfLongs, numberOfReferences), null, QueryState.EMPTY)

    data.refs(0) should equal(stringValue("A"))
    data.refs(1) should equal(Values.longArray(Array(0, 2, 4, 6, 8)))
    data.refs(2) should equal(stringValue("B"))
    data.refs(3) should equal(Values.EMPTY_LONG_ARRAY)
  }

  test("two grouping keys") {
    // Given
    val numberOfLongs = 1
    val numberOfReferences = 3
    val groupSlot1 = RefSlot(0, nullable = false, CTAny)
    val groupSlot2 = RefSlot(1, nullable = false, CTAny)
    val aggregation = new AggregationMapperOperator(
                                                    Array(AggregationOffsets(2, 2, DummyEvenNodeIdAggregation(0))),
                                                    Array(GroupingOffsets(groupSlot1, groupSlot1,
                                                                          new DummyExpression(stringValue("A"), stringValue("B"))),
                                                          GroupingOffsets(groupSlot2, groupSlot2,
                                                            new DummyExpression(stringValue("C"), stringValue("D"), stringValue("E")))

                                                          ))
    val longs = Array[Long](0,1,2,3,4,5,6,7,8,9)
    val refs = new Array[AnyValue](3 * longs.length)
    val data = new Morsel(longs, refs, longs.length)

    // When
    aggregation.operate(MorselExecutionContext(data, numberOfLongs, numberOfReferences), null, QueryState.EMPTY)

    data.refs.take(3 * 6) should equal(Array(
      stringValue("A"),
      stringValue("C"),
      Values.longArray(Array(0, 6)),

      stringValue("B"),
      stringValue("D"),
      Values.EMPTY_LONG_ARRAY,

      stringValue("A"),
      stringValue("E"),
      Values.longArray(Array(2, 8)),

      stringValue("B"),
      stringValue("C"),
      Values.EMPTY_LONG_ARRAY,

      stringValue("A"),
      stringValue("D"),
      Values.longArray(Array(4)),

      stringValue("B"),
      stringValue("E"),
      Values.EMPTY_LONG_ARRAY))
  }

  test("three grouping keys") {
    // Given
    val numberOfLongs = 1
    val numberOfReferences = 4
    val groupSlot1 = RefSlot(0, nullable = false, CTAny)
    val groupSlot2 = RefSlot(1, nullable = false, CTAny)
    val groupSlot3 = RefSlot(2, nullable = false, CTAny)
    val aggregation = new AggregationMapperOperator(
                                                    Array(AggregationOffsets(3, 3, DummyEvenNodeIdAggregation(0))),
                                                    Array(GroupingOffsets(groupSlot1, groupSlot1,
                                                                          new DummyExpression(stringValue("A"), stringValue("B"))),
                                                          GroupingOffsets(groupSlot2, groupSlot2,
                                                                          new DummyExpression(stringValue("C"), stringValue("D"))),
                                                          GroupingOffsets(groupSlot3, groupSlot3,
                                                                          new DummyExpression(stringValue("E"), stringValue("F")))


                                                    ))
    val longs = Array[Long](0,1,2,3,4,5,6,7,8,9)
    val refs = new Array[AnyValue](4 * longs.length)
    val data = new Morsel(longs, refs, longs.length)

    // When
    aggregation.operate(MorselExecutionContext(data, numberOfLongs, numberOfReferences), null, QueryState.EMPTY)

    data.refs(0) should equal(stringValue("A"))
    data.refs(1) should equal(stringValue("C"))
    data.refs(2) should equal(stringValue("E"))
    data.refs(3) should equal(Values.longArray(Array(0,2,4,6,8)))
    data.refs(4) should equal(stringValue("B"))
    data.refs(5) should equal(stringValue("D"))
    data.refs(6) should equal(stringValue("F"))
    data.refs(7) should equal(Values.EMPTY_LONG_ARRAY)
  }

  test("more than three grouping keys") {
    // Given

    val numberOfLongs = 1
    val numberOfReferences = 6
    val groupSlot1 = RefSlot(0, nullable = false, CTAny)
    val groupSlot2 = RefSlot(1, nullable = false, CTAny)
    val groupSlot3 = RefSlot(2, nullable = false, CTAny)
    val groupSlot4 = RefSlot(3, nullable = false, CTAny)
    val groupSlot5 = RefSlot(4, nullable = false, CTAny)
    val aggregation = new AggregationMapperOperator(
                                                    Array(AggregationOffsets(5, 5, DummyEvenNodeIdAggregation(0))),
                                                    Array(GroupingOffsets(groupSlot1, groupSlot1,
                                                                          new DummyExpression(stringValue("A"), stringValue("B"))),
                                                          GroupingOffsets(groupSlot2, groupSlot2,
                                                                          new DummyExpression(stringValue("C"), stringValue("D"))),
                                                          GroupingOffsets(groupSlot3, groupSlot3,
                                                                          new DummyExpression(stringValue("E"), stringValue("F"))),
                                                          GroupingOffsets(groupSlot4, groupSlot4,
                                                                          new DummyExpression(stringValue("G"), stringValue("H"))),
                                                          GroupingOffsets(groupSlot5, groupSlot5,
                                                                          new DummyExpression(stringValue("I"), stringValue("J")))


                                                    ))
    val longs = Array[Long](0,1,2,3,4,5,6,7,8,9)
    val refs = new Array[AnyValue](6 * longs.length)
    val data = new Morsel(longs, refs, longs.length)

    // When
    aggregation.operate(MorselExecutionContext(data, numberOfLongs, numberOfReferences), null, QueryState.EMPTY)

    data.refs(0) should equal(stringValue("A"))
    data.refs(1) should equal(stringValue("C"))
    data.refs(2) should equal(stringValue("E"))
    data.refs(3) should equal(stringValue("G"))
    data.refs(4) should equal(stringValue("I"))
    data.refs(5) should equal(Values.longArray(Array(0,2,4,6,8)))
    data.refs(6) should equal(stringValue("B"))
    data.refs(7) should equal(stringValue("D"))
    data.refs(8) should equal(stringValue("F"))
    data.refs(9) should equal(stringValue("H"))
    data.refs(10) should equal(stringValue("J"))
    data.refs(11) should equal(Values.EMPTY_LONG_ARRAY)
  }
}
