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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.neo4j.cypher.internal.compatibility.v3_6.runtime.SlotConfiguration
import org.neo4j.cypher.internal.runtime.interpreted.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.pipes.Pipe
import org.neo4j.cypher.internal.runtime.slotted.SlottedExecutionContext
import org.neo4j.values.AnyValue
import org.neo4j.cypher.internal.v3_6.util.test_helpers.CypherFunSuite

object HashJoinSlottedPipeTestHelper extends CypherFunSuite with SlottedPipeTestHelper {

  abstract class Row {
    val l: Longs
    val r: Refs
  }

  case class RowR(refs: AnyValue*) extends Row {
    val r = Refs(refs: _*)
    val l = Longs()
  }

  case class RowL(longs: Long*) extends Row {
    val r = Refs()
    val l = Longs(longs: _*)
  }

  case class RowRL(l: Longs, r: Refs) extends Row

  case class Longs(l: Long*)

  case class Refs(l: AnyValue*)

  def mockPipeFor(slots: SlotConfiguration, rows: Row*): Pipe = {
    val p = mock[Pipe]
    when(p.createResults(any())).thenAnswer(new Answer[Iterator[ExecutionContext]]() {
      override def answer(invocationOnMock: InvocationOnMock): Iterator[ExecutionContext] = {
        rows.toIterator.map { row =>
          val createdRow = SlottedExecutionContext(slots)
          row.l.l.zipWithIndex foreach {
            case (v, idx) => createdRow.setLongAt(idx, v)
          }
          row.r.l.zipWithIndex foreach {
            case (v, idx) => createdRow.setRefAt(idx, v)
          }
          createdRow
        }
      }
    })
    p
  }
}
