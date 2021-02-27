/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.internal.cypher.acceptance

import java.util.concurrent.{Callable, Executors, Future}

import org.neo4j.cypher.ExecutionEngineFunSuite

class SameQueryStressTest extends ExecutionEngineFunSuite {

  private val lookup = """MATCH (theMatrix:Movie {title:'The Matrix'})<-[:ACTED_IN]-(actor)-[:ACTED_IN]->(other:Movie)
                          WHERE other <> theMatrix
                          RETURN actor""".stripMargin

  for {
    runtime <- List("interpreted", "slotted", "compiled")
  } testRuntime(runtime)

  private def testRuntime(runtime: String): Unit = {

    test(s"concurrent query execution in $runtime") {

      // Given
      execute(TestGraph.movies)
      val expected = execute(lookup).dumpToString()

      // When
      val nThreads = 10
      val executor = Executors.newFixedThreadPool(nThreads)
      val futureResultsAsExpected: Seq[Future[Array[String]]] =
        for (i <- 1 to nThreads) yield
          executor.submit(new Callable[Array[String]] {
            override def call(): Array[String] = {
              (for (j <- 1 to 1000) yield {
                execute(lookup).dumpToString()
              }).toArray
            }
          })

      // Then no crashes...
      for (futureResult: Future[Array[String]] <- futureResultsAsExpected;
           result: String <- futureResult.get
      ) {
        // ...and correct results
        result should equal(expected)
      }
      executor.shutdown()
    }
  }
}

