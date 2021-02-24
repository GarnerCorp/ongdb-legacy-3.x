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

import org.neo4j.cypher.internal.compatibility.v3_6.runtime.Slot
import org.neo4j.cypher.internal.runtime.LenientCreateRelationship
import org.neo4j.cypher.internal.runtime.interpreted.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.pipes._
import org.neo4j.kernel.api.StatementConstants.{NO_SUCH_NODE, NO_SUCH_RELATIONSHIP}
import org.neo4j.cypher.internal.v3_6.util.attribution.Id
import org.neo4j.cypher.internal.v3_6.util.{InternalException, InvalidSemanticsException}

/**
  * Extends BaseCreatePipe with slotted methods to create nodes and relationships.
  */
abstract class EntityCreateSlottedPipe(source: Pipe) extends BaseCreatePipe(source) {

  /**
    * Create node and return id.
    */
  protected def createNode(context: ExecutionContext,
                           state: QueryState,
                           command: CreateNodeSlottedCommand): Long = {
    val labelIds = command.labels.map(_.getOrCreateId(state.query).id).toArray
    val nodeId = state.query.createNodeId(labelIds)
    command.properties.foreach(setProperties(context, state, nodeId, _, state.query.nodeOps))
    nodeId
  }

  /**
    * Create relationship and return id.
    */
  protected def createRelationship(context: ExecutionContext,
                                   state: QueryState,
                                   command: CreateRelationshipSlottedCommand): Long = {

    def handleMissingNode(nodeName: String) =
      if (state.lenientCreateRelationship) NO_SUCH_RELATIONSHIP
      else throw new InternalException(LenientCreateRelationship.errorMsg(command.relName, nodeName))

    val startNodeId = command.startNodeIdGetter(context)
    val endNodeId = command.endNodeIdGetter(context)
    val typeId = command.relType.typ(state.query)

    if (startNodeId == NO_SUCH_NODE) handleMissingNode(command.startName)
    else if (endNodeId == NO_SUCH_NODE) handleMissingNode(command.endName)
    else {
      val relationship = state.query.createRelationship(startNodeId, endNodeId, typeId)
      command.properties.foreach(setProperties(context, state, relationship.id(), _, state.query.relationshipOps))
      relationship.id()
    }

//    if (startNodeId == -1) {
//      throw new InternalException(s"Expected to find a node, but found instead: null")
//    }
//    if (endNodeId == -1) {
//      throw new InternalException(s"Expected to find a node, but found instead: null")
//    }
//    val relationship = state.query.createRelationship(startNodeId, endNodeId, typeId)
//    command.properties.foreach(setProperties(context, state, relationship.id(), _, state.query.relationshipOps))
//    relationship.id
  }
}

case class CreateNodeSlottedCommand(idOffset: Int,
                                    labels: Seq[LazyLabel],
                                    properties: Option[Expression])

case class CreateRelationshipSlottedCommand(relIdOffset: Int,
                                            startNodeIdGetter: ExecutionContext => Long,
                                            relType: LazyType,
                                            endNodeIdGetter: ExecutionContext => Long,
                                            properties: Option[Expression],
                                            relName: String,
                                            startName: String,
                                            endName: String)

/**
  * Create nodes and relationships from slotted commands.
  */
case class CreateSlottedPipe(source: Pipe,
                             nodes: IndexedSeq[CreateNodeSlottedCommand],
                             relationships: IndexedSeq[CreateRelationshipSlottedCommand])
                            (val id: Id = Id.INVALID_ID)
  extends EntityCreateSlottedPipe(source) {

  override protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState): Iterator[ExecutionContext] = {
    input.map {
      row =>
        var i = 0
        while (i < nodes.length) {
          val command = nodes(i)
          val nodeId = createNode(row, state, command)
          row.setLongAt(command.idOffset, nodeId)
          i += 1
        }

        i = 0
        while (i < relationships.length) {
          val command = relationships(i)
          val relationshipId = createRelationship(row, state, command)
          row.setLongAt(command.relIdOffset, relationshipId)
          i += 1
        }

        row
    }
  }

  override protected def handleNoValue(key: String): Unit = {
    // do nothing
  }
}

/**
  * Special create node for use in merge. See `MergeCreateNodePipe`.
  */
case class MergeCreateNodeSlottedPipe(source: Pipe,
                                      command: CreateNodeSlottedCommand)
                                     (val id: Id = Id.INVALID_ID)
  extends EntityCreateSlottedPipe(source) {

  override protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState): Iterator[ExecutionContext] = {
    input.map {
      row =>
        row.setLongAt(command.idOffset, createNode(row, state, command))
        row
    }
  }

  override protected def handleNoValue(key: String): Unit =
    throw new InvalidSemanticsException(s"Cannot merge node using null property value for $key")
}

/**
  * Special create relationship for use in merge. See `MergeCreateRelationshipPipe`.
  */
case class MergeCreateRelationshipSlottedPipe(source: Pipe,
                                              command: CreateRelationshipSlottedCommand)
                                             (val id: Id = Id.INVALID_ID)
  extends EntityCreateSlottedPipe(source) {

  override protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState): Iterator[ExecutionContext] = {
    input.map {
      row =>
        row.setLongAt(command.relIdOffset, createRelationship(row, state, command))
        row
    }
  }

  override protected def handleNoValue(key: String): Unit =
    throw new InvalidSemanticsException(s"Cannot merge relationship using null property value for $key")
}
