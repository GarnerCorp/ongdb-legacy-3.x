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

import org.neo4j.cypher.internal.compatibility.v3_5.runtime.{SlotConfiguration, SlottedIndexedProperty}
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.pipes.{IndexSeek, IndexSeekMode, NodeIndexSeeker, QueryState => OldQueryState}
import org.neo4j.cypher.internal.runtime.vectorized._
import org.neo4j.cypher.internal.v3_5.logical.plans.{IndexOrder, QueryExpression}
import org.neo4j.internal.kernel.api._
import org.neo4j.values.storable.Value
import org.neo4j.cypher.internal.v3_5.expressions.LabelToken

class NodeIndexSeekOperator(offset: Int,
                            label: LabelToken,
                            properties: Array[SlottedIndexedProperty],
                            indexOrder: IndexOrder,
                            argumentSize: SlotConfiguration.Size,
                            override val valueExpr: QueryExpression[Expression],
                            override val indexMode: IndexSeekMode = IndexSeek)
  extends StreamingOperator with NodeIndexSeeker {

  private val indexPropertyIndices: Array[Int] = properties.zipWithIndex.filter(_._1.getValueFromIndex).map(_._2)
  private val indexPropertySlotOffsets: Array[Int] = properties.flatMap(_.maybeCachedNodePropertySlot)
  private val needsValues: Boolean = indexPropertyIndices.nonEmpty

  override def init(context: QueryContext, state: QueryState, currentRow: MorselExecutionContext): ContinuableOperatorTask = {
    val queryState = new OldQueryState(context, resources = null, params = state.params)
    val indexReference = reference(context)
    val nodeCursor = indexSeek(queryState, indexReference, needsValues, indexOrder, currentRow)
    new OTask(nodeCursor)
  }

  override val propertyIds: Array[Int] = properties.map(_.propertyKeyId)

  private var reference: IndexReference = IndexReference.NO_INDEX

  private def reference(context: QueryContext): IndexReference = {
    if (reference == IndexReference.NO_INDEX) {
      reference = context.indexReference(label.nameId.id, propertyIds:_*)
    }
    reference
  }

  class OTask(nodeCursors: Iterator[NodeValueIndexCursor]) extends ContinuableOperatorTask {

    private var nodeCursor: NodeValueIndexCursor = _
    private var _canContinue: Boolean = true

    private def next(): Boolean = {
      while (true) {
        if (nodeCursor != null && nodeCursor.next())
          return true
        else if (nodeCursors.hasNext)
          nodeCursor = nodeCursors.next()
        else {
          _canContinue = false
          return false
        }
      }
      false // because scala compiler doesn't realize that this line is unreachable
    }

    override def operate(currentRow: MorselExecutionContext,
                         context: QueryContext,
                         state: QueryState): Unit = {

      while (currentRow.hasMoreRows && next()) {
        currentRow.setLongAt(offset, nodeCursor.nodeReference())
        var i = 0
        while (i < indexPropertyIndices.length) {
          currentRow.setCachedPropertyAt(indexPropertySlotOffsets(i), nodeCursor.propertyValue(indexPropertyIndices(i)))
          i += 1
        }
        currentRow.moveToNextRow()
      }

      currentRow.finishedWriting()
    }

    override def canContinue: Boolean = _canContinue
  }

}

class NodeWithValues(val nodeId: Long, val values: Array[Value])
