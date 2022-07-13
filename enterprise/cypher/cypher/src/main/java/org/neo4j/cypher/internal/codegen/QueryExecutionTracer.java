/*
 * Copyright (c) 2018-2020 "Graph Foundation"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of ONgDB Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found
 * in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
package org.neo4j.cypher.internal.codegen;

//import org.neo4j.cypher.internal.compiler.v3_1.codegen.QueryExecutionEvent;
import org.neo4j.cypher.internal.runtime.compiled.codegen.QueryExecutionEvent;
import org.neo4j.cypher.internal.v3_6.util.attribution.Id;

public interface QueryExecutionTracer
{
    QueryExecutionEvent executeOperator( Id queryId );

    QueryExecutionTracer NONE = queryId -> QueryExecutionEvent.NONE;
}