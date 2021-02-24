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
package org.neo4j.internal.cypher.acceptance

import org.neo4j.cypher.internal.RewindableExecutionResult
import org.neo4j.cypher.internal.planner.v3_5.spi.GraphStatistics
import org.neo4j.cypher.internal.runtime.planDescription.InternalPlanDescription.Arguments.DbHits
import org.neo4j.cypher.internal.runtime.planDescription.InternalPlanDescription.Arguments.Rows
import org.neo4j.cypher.internal.runtime.planDescription.Argument
import org.neo4j.cypher.internal.runtime.planDescription.InternalPlanDescription
import org.neo4j.cypher.internal.runtime.CreateTempFileTestSupport
import org.neo4j.cypher.internal.runtime.ProfileMode
import org.neo4j.cypher.ExecutionEngineFunSuite
import org.neo4j.cypher.ProfilerStatisticsNotReadyException
import org.neo4j.cypher.TxCounts
import org.neo4j.graphdb.QueryExecutionException
import org.neo4j.internal.cypher.acceptance.comparisonsupport.Configs
import org.neo4j.internal.cypher.acceptance.comparisonsupport.CypherComparisonSupport
import org.neo4j.internal.cypher.acceptance.comparisonsupport.TestConfiguration
import org.neo4j.cypher.internal.v3_5.util.helpers.StringHelper.RichString

import scala.reflect.ClassTag

class ProfilerAcceptanceTest extends ExecutionEngineFunSuite with CreateTempFileTestSupport with CypherComparisonSupport {

  test("profile simple query") {
    createNode()
    createNode()
    createNode()
    val result = profileWithExecute(Configs.All + Configs.Morsel, "MATCH (n) RETURN n")

    result.executionPlanDescription() should (
      includeSomewhere.aPlan("ProduceResults").withRows(3).withDBHits(0) and
        includeSomewhere.aPlan("AllNodesScan").withRows(3).withDBHits(4)
      )
  }

  test("track db hits in Projection") {
    createNode()
    createNode()
    createNode()

    val result = profileWithExecute(Configs.All + Configs.Morsel, "MATCH (n) RETURN (n:Foo)")

    result.executionPlanDescription() should (
      includeSomewhere.aPlan("ProduceResults").withRows(3).withDBHits(0) and
        includeSomewhere.aPlan("AllNodesScan").withRows(3) and
        includeSomewhere.aPlan("Projection").withDBHits(3) and
        includeSomewhere.aPlan("AllNodesScan").withDBHits(4)
      )
  }

  test("profile standalone call") {
    createLabeledNode("Person")
    createLabeledNode("Animal")

    val result = legacyProfile("CALL db.labels")
    result.executionPlanDescription() should includeSomewhere.aPlan("ProcedureCall")
      .withRows(2)
      .withDBHits(1)
      .withExactVariables("label")
      .containingArgument("db.labels() :: (label :: String)")
  }

  test("profile call in query") {
    createLabeledNode("Person")
    createLabeledNode("Animal")

    val result = legacyProfile("MATCH (n:Person) CALL db.labels() YIELD label RETURN *")

    result.executionPlanDescription() should includeSomewhere.aPlan("ProcedureCall")
      .withRows(2)
      .withDBHits(1)
      .withExactVariables("n", "label")
      .containingArgument("db.labels() :: (label :: String)")
  }

  test("match (n) where (n)-[:FOO]->() return *") {
    //GIVEN
    relate(createNode(), createNode(), "FOO")

    //WHEN
    val result = profileWithExecute(Configs.InterpretedAndSlotted + Configs.Morsel, "match (n) where (n)-[:FOO]->() return *")

    //THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("Filter").withRows(1).withDBHits(4) and
        includeSomewhere.aPlan("AllNodesScan").withRows(2).withDBHits(3)
      )
  }

  test("match (n:A)-->(x:B) return *") {
    //GIVEN
    relate(createLabeledNode("A"), createLabeledNode("B"))

    //WHEN
    val result = profileWithExecute(Configs.All, "match (n:A)-->(x:B) return *")

    //THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("ProduceResults").withRows(1).withDBHits(0) and
        includeSomewhere.aPlan("Filter").withRows(1).withDBHits(1) and
        includeSomewhere.aPlan("Expand(All)").withRows(1).withDBHits(2) and
        includeSomewhere.aPlan("NodeByLabelScan").withRows(1).withDBHits(2)
      )
  }

  test("PROFILE for Cypher 2.3") {
    val result = graph.execute("cypher 2.3 profile match (n) where (n)-[:FOO]->() return *")

    assert(result.getQueryExecutionType.requestedExecutionPlanDescription, "result not marked with planDescriptionRequested")
    result.getExecutionPlanDescription.toString should include("DB Hits")
  }

  test("PROFILE for Cypher 3.1") {
    val result = graph.execute("cypher 3.1 profile match (n) where (n)-[:FOO]->() return *")

    assert(result.getQueryExecutionType.requestedExecutionPlanDescription, "result not marked with planDescriptionRequested")
    result.getExecutionPlanDescription.toString should include("DB Hits")
  }

  test("match (n) where not (n)-[:FOO]->() return *") {
    //GIVEN
    relate(createNode(), createNode(), "FOO")

    //WHEN
    val result = profileWithExecute(Configs.InterpretedAndSlotted + Configs.Morsel, "match (n) where not (n)-[:FOO]->() return *")

    //THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("Filter").withRows(1).withDBHits(4) and
        includeSomewhere.aPlan("AllNodesScan").withRows(2).withDBHits(3)
      )
  }

  test("unfinished profiler complains [using MATCH]") {
    //GIVEN
    createNode("foo" -> "bar")
    val result = graph.execute("PROFILE match (n) where id(n) = 0 RETURN n")

    //WHEN THEN
    val ex = intercept[QueryExecutionException](result.getExecutionPlanDescription)
    ex.getCause.getCause shouldBe a[ProfilerStatisticsNotReadyException]
    result.close() // ensure that the transaction is closed
  }

  test("unfinished profiler complains [using CALL]") {
    //GIVEN
    createLabeledNode("Person")
    val result = graph.execute("PROFILE CALL db.labels")

    //WHEN THEN
    val ex = intercept[QueryExecutionException](result.getExecutionPlanDescription)
    ex.getCause.getCause shouldBe a[ProfilerStatisticsNotReadyException]
    result.close() // ensure that the transaction is closed
  }

  test("unfinished profiler complains [using CALL within larger query]") {
    //GIVEN
    createLabeledNode("Person")
    val result = graph.execute("PROFILE CALL db.labels() YIELD label WITH label as r RETURN r")

    //WHEN THEN
    val ex = intercept[QueryExecutionException](result.getExecutionPlanDescription)
    ex.getCause.getCause shouldBe a[ProfilerStatisticsNotReadyException]
    result.close() // ensure that the transaction is closed
  }

  test("tracks number of rows") {
    //GIVEN
    // due to the cost model, we need a bunch of nodes for the planner to pick a plan that does lookup by id
    (1 to 100).foreach(_ => createNode())

    val result = profileWithExecute(Configs.All, "match (n) where id(n) = 0 RETURN n")

    //WHEN THEN
    result.executionPlanDescription() should includeSomewhere.aPlan("NodeByIdSeek").withRows(1)
  }

  test("tracks number of graph accesses") {
    //GIVEN
    // due to the cost model, we need a bunch of nodes for the planner to pick a plan that does lookup by id
    (1 to 100).foreach(_ => createNode("foo" -> "bar"))

    val result = profileWithExecute(Configs.All, "match (n) where id(n) = 0 RETURN n.foo")

    //WHEN THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("ProduceResults").withRows(1).withDBHits(0) and
        includeSomewhere.aPlan("Projection").withRows(1).withDBHits(1) and
        includeSomewhere.aPlan("NodeByIdSeek").withRows(1).withDBHits(1)
      )
  }

  test("no problem measuring creation") {
    //GIVEN
    val result = legacyProfile("CREATE (n)")

    //WHEN THEN
    result.executionPlanDescription() should includeSomewhere.aPlan("EmptyResult").withDBHits(0)
  }

  test("tracks graph global queries") {
    createNode()

    //GIVEN
    val result = profileWithExecute(Configs.All + Configs.Morsel, "MATCH (n) RETURN n.foo")

    //WHEN THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("ProduceResults").withRows(1).withDBHits(0) and
        includeSomewhere.aPlan("Projection").withRows(1).withDBHits(1) and
        includeSomewhere.aPlan("AllNodesScan").withRows(1).withDBHits(2)
      )
  }

  test("tracks optional matches") {
    //GIVEN
    createNode()

    // WHEN
    val result = profileWithExecute(Configs.InterpretedAndSlotted, "MATCH (n) optional match (n)-->(x) return x")

    // THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("ProduceResults").withDBHits(0) and
        includeSomewhere.aPlan("OptionalExpand(All)").withDBHits(1) and
        includeSomewhere.aPlan("AllNodesScan").withDBHits(2)
      )
  }

  test("allows optional match to start a query") {
    // WHEN
    val result = profileWithExecute(Configs.InterpretedAndSlotted, "optional match (n) return n")

    // THEN
    result.executionPlanDescription() should includeSomewhere.aPlan("Optional").withRows(1)
  }

  test("should produce profile when using limit") {
    // GIVEN
    createNode()
    createNode()
    createNode()
    val result = profileWithExecute(Configs.All, """MATCH (n) RETURN n LIMIT 1""")

    // WHEN
    result.toList

    // THEN PASS
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("AllNodesScan").withRows(1).withDBHits(2) and
        includeSomewhere.aPlan("ProduceResults").withRows(1).withDBHits(0)
      )

    result.executionPlanDescription()
  }

  test("LIMIT should influence cardinality estimation even when parameterized") {
    (0 until 100).map(i => createLabeledNode("Person"))
    val result = executeWith(Configs.All, s"PROFILE MATCH (p:Person) RETURN p LIMIT {limit}", params = Map("limit" -> 10))
    result.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(GraphStatistics.DEFAULT_LIMIT_CARDINALITY.amount.toInt)
  }

  test("LIMIT should influence cardinality estimation with literal") {
    (0 until 100).map(i => createLabeledNode("Person"))
    val result = executeWith(Configs.All, s"PROFILE MATCH (p:Person) RETURN p LIMIT 10")
    result.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(10)
  }

  test("LIMIT should influence cardinality estimation with literal and parameters") {
    (0 until 100).map(i => createLabeledNode("Person"))
    val result = executeWith(Configs.All, s"PROFILE MATCH (p:Person) WHERE 50 = {fifty} RETURN p LIMIT 10", params = Map("fifty" -> 50))
    result.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(10)
  }

  test("LIMIT should influence cardinality estimation with independent parameterless expression") {
    (0 until 100).map(i => createLabeledNode("Person"))
    val result = executeWith(Configs.InterpretedAndSlotted, s"PROFILE MATCH (p:Person) with 10 as x, p RETURN p LIMIT toInt(ceil(cos(0))) + 4")
    result.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(5)
  }

  test("LIMIT should influence cardinality estimation by default value when expression contains parameter") {
    (0 until 100).map(i => createLabeledNode("Person"))
    val result = executeWith(Configs.InterpretedAndSlotted, s"PROFILE MATCH (p:Person) with 10 as x, p RETURN p LIMIT toInt(sin({limit}))", params = Map("limit" -> 1))
    result.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(GraphStatistics.DEFAULT_LIMIT_CARDINALITY.amount.toInt)
  }

  test("LIMIT should influence cardinality estimation by default value when expression contains rand()") {
    (0 until 100).map(i => createLabeledNode("Person"))
    // NOTE: We cannot executeWith because of random result
    val result = executeSingle(s"PROFILE MATCH (p:Person) with 10 as x, p RETURN p LIMIT toInt(rand()*10)", Map.empty)
    result.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(GraphStatistics.DEFAULT_LIMIT_CARDINALITY.amount.toInt)
  }

  test("LIMIT should influence cardinality estimation by default value when expression contains timestamp()") {
    (0 until 100).map(i => createLabeledNode("Person"))
    //TODO this cannot be run with executeWith since it will occasionally succeed on 2.3 and we have decided not
    //to fix this on 2.3. So if we fix the issue on 2.3 or if we no longer need to depend on 2.3 we should update test
    //to run with `executeWith`
    val r1 = executeSingle(s"PROFILE MATCH (p:Person) with 10 as x, p RETURN p LIMIT timestamp()", Map.empty)
    r1.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(GraphStatistics.DEFAULT_LIMIT_CARDINALITY.amount.toInt)

    val r2 = executeSingle(s"PROFILE CYPHER runtime=slotted MATCH (p:Person) with 10 as x, p RETURN p LIMIT timestamp()", Map.empty)
    r2.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(GraphStatistics.DEFAULT_LIMIT_CARDINALITY.amount.toInt)

    val r3 = executeSingle(s"PROFILE CYPHER runtime=interpreted MATCH (p:Person) with 10 as x, p RETURN p LIMIT timestamp()", Map.empty)
    r3.executionPlanDescription() should includeSomewhere.aPlan("Limit").withEstimatedRows(GraphStatistics.DEFAULT_LIMIT_CARDINALITY.amount.toInt)
  }

  test("should support profiling union queries") {
    val result = profileWithExecute(Configs.InterpretedAndSlotted, "return 1 as A union return 2 as A")
    result.toSet should equal(Set(Map("A" -> 1), Map("A" -> 2)))
  }

  test("should support profiling merge_queries") {
    val result = legacyProfile("merge (a {x: 1}) return a.x as A")
    result.toList.head("A") should equal(1)
  }

  test("should support profiling optional match queries") {
    createLabeledNode(Map("x" -> 1), "Label")
    val result = profileWithExecute(Configs.InterpretedAndSlotted, "match (a:Label {x: 1}) optional match (a)-[:REL]->(b) return a.x as A, b.x as B").toList.head
    result("A") should equal(1)
    result("B") should equal(null.asInstanceOf[Int])
  }

  test("should support profiling optional match and with") {
    createLabeledNode(Map("x" -> 1), "Label")
    val executionResult = profileWithExecute(Configs.InterpretedAndSlotted, "match (n) optional match (n)--(m) with n, m where m is null return n.x as A")
    val result = executionResult.toList.head
    result("A") should equal(1)
  }

  test("should handle PERIODIC COMMIT when profiling") {
    val url = createTempFileURL("cypher", ".csv")(writer => {
      (1 to 100).foreach(writer.println)
    }).cypherEscape

    val query = s"USING PERIODIC COMMIT 10 LOAD CSV FROM '$url' AS line CREATE()"

    // given
    executeWith(Configs.InterpretedAndSlotted - Configs.Cost2_3, query).toList
    deleteAllEntities()
    val initialTxCounts = graph.txCounts

    // when
    val result = legacyProfile(query)

    // then
    val expectedTxCount = 10 // One per 10 rows of CSV file

    graph.txCounts - initialTxCounts should equal(TxCounts(commits = expectedTxCount))
    result.queryStatistics().containsUpdates should equal(true)
    result.queryStatistics().nodesCreated should equal(100)
  }

  test("should not have a problem profiling empty results") {
    val result = profileWithExecute(Configs.InterpretedAndSlotted + Configs.Morsel, "MATCH (n) WHERE (n)-->() RETURN n")

    result shouldBe empty
    result.executionPlanDescription().toString should include("AllNodes")
  }

  test("reports COST planner when showing plan description") {
    val result = graph.execute("CYPHER planner=cost match (n) return n")
    result.resultAsString()
    result.getExecutionPlanDescription.toString should include("Planner COST" + System.lineSeparator())
  }

  test("does not use Apply for aggregation and order by") {
    val a = profileWithExecute(Configs.InterpretedAndSlotted, "match (n) return n, count(*) as c order by c")

    a.executionPlanDescription().toString should not include "Apply"
  }

  //this test asserts a specific optimization in pipe building and is not
  //valid for the compiled runtime
  test("should not use eager plans for distinct") {
    val a = executeSingle("PROFILE CYPHER runtime=interpreted MATCH (n) RETURN DISTINCT n.name", Map.empty)
    a.executionPlanDescription().toString should not include "Eager"
  }

  test("should not show  EstimatedRows in legacy profiling") {
    val result = legacyProfile("create()")
    result.executionPlanDescription().toString should not include "EstimatedRows"
  }

  test("match (p:Person {name:'Seymour'}) return (p)-[:RELATED_TO]->()") {
    //GIVEN
    val seymour = createLabeledNode(Map("name" -> "Seymour"), "Person")
    relate(seymour, createLabeledNode(Map("name" -> "Buddy"), "Person"), "RELATED_TO")
    relate(seymour, createLabeledNode(Map("name" -> "Boo Boo"), "Person"), "RELATED_TO")
    relate(seymour, createLabeledNode(Map("name" -> "Walt"), "Person"), "RELATED_TO")
    relate(seymour, createLabeledNode(Map("name" -> "Waker"), "Person"), "RELATED_TO")
    relate(seymour, createLabeledNode(Map("name" -> "Zooey"), "Person"), "RELATED_TO")
    relate(seymour, createLabeledNode(Map("name" -> "Franny"), "Person"), "RELATED_TO")
    // pad with enough nodes to make index seek considered more efficient than label scan
    createLabeledNode(Map("name" -> "Dummy1"), "Person")
    createLabeledNode(Map("name" -> "Dummy2"), "Person")
    createLabeledNode(Map("name" -> "Dummy3"), "Person")

    graph.createConstraint("Person", "name")

    //WHEN
    val result = profileWithExecute(Configs.InterpretedAndSlotted, "match (p:Person {name:'Seymour'}) return (p)-[:RELATED_TO]->()")

    //THEN
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("Expand(All)").withDBHits(7) and
        includeSomewhere.aPlan("NodeUniqueIndexSeek").withDBHits(3)
      )
  }

  test("should show expand without types in a simple form") {
    val a = profileWithExecute(Configs.All + Configs.Morsel, "match (n)-->() return *")

    a.executionPlanDescription().toString should include("()<--(n)")
  }

  test("should show expand with types in a simple form") {
    val result = profileWithExecute(Configs.All + Configs.Morsel, "match (n)-[r:T]->() return *")

    result.executionPlanDescription().toString should include("()<-[r:T]-(n)")
  }

  test("should report correct dbhits and rows for label scan") {
    // given
    createLabeledNode("Label1")

    // when
    val result = profileWithExecute(Configs.All, "match (n:Label1) return n")

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("NodeByLabelScan").withRows(1).withDBHits(2)
  }

  test("should report correct dbhits and rows for expand") {
    // given
    relate(createNode(), createNode())

    // when
    val result = profileWithExecute(Configs.All + Configs.Morsel, "match (n)-->(x) return x")

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("Expand(All)").withRows(1).withDBHits(3)
  }

  test("should report correct dbhits and rows for literal addition") {
    // when
    val result = profileWithExecute(Configs.All + Configs.Morsel, "return 5 + 3")

    // then
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("Projection").withDBHits(0) and
        includeSomewhere.aPlan("ProduceResults").withRows(1).withDBHits(0)
      )
  }

  test("should report correct dbhits and rows for property addition") {
    // given
    createNode("name" -> "foo")

    // when
    val result = profileWithExecute(Configs.All + Configs.Morsel, "match (n) return n.name + 3")

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("Projection").withRows(1).withDBHits(1)
  }

  test("should report correct dbhits and rows for property subtraction") {
    // given
    createNode("name" -> 10)

    // when
    val result = profileWithExecute(Configs.All + Configs.Morsel, "match (n) return n.name - 3")

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("Projection").withRows(1).withDBHits(1)
  }

  test("should throw if accessing profiled results before they have been materialized") {
    createNode()
    val result = graph.execute("profile match (n) return n")

    val ex = intercept[QueryExecutionException](result.getExecutionPlanDescription)
    ex.getCause.getCause shouldBe a[ProfilerStatisticsNotReadyException]
    result.close() // ensure that the transaction is closed
  }

  test("should handle cartesian products") {
    createNode()
    createNode()
    createNode()
    createNode()

    val result = profileWithExecute(Configs.All, "match (n), (m) return n, m")
    result.executionPlanDescription() should includeSomewhere.aPlan("CartesianProduct").withRows(16)
  }

  test("should properly handle filters") {
    // given
    val n = createLabeledNode(Map("name" -> "Seymour"), "Glass")
    val o = createNode()
    relate(n, o, "R1")
    relate(o, createLabeledNode(Map("name" -> "Zoey"), "Glass"), "R2")
    relate(o, createLabeledNode(Map("name" -> "Franny"), "Glass"), "R2")
    relate(o, createNode(), "R2")
    relate(o, createNode(), "R2")
    graph.createIndex("Glass", "name")

    // when
    val result = profileWithExecute(Configs.All,
      "match (n:Glass {name: 'Seymour'})-[:R1]->(o)-[:R2]->(p:Glass) USING INDEX n:Glass(name) return p.name")

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("Filter").withRows(2)
  }

  test("interpreted runtime projections") {
    // given
    val n = createLabeledNode(Map("name" -> "Seymour"), "Glass")
    val o = createNode()
    relate(n, o, "R1")
    relate(o, createLabeledNode(Map("name" -> "Zoey"), "Glass"), "R2")
    relate(o, createLabeledNode(Map("name" -> "Franny"), "Glass"), "R2")
    relate(o, createNode(), "R2")
    relate(o, createNode(), "R2")
    graph.createIndex("Glass", "name")

    // when
    val result = executeSingle(
      "profile cypher runtime=interpreted match (n:Glass {name: 'Seymour'})-[:R1]->(o)-[:R2]->(p:Glass) USING INDEX n:Glass(name) return p.name", Map.empty)

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("Projection").withDBHits(2)
  }

  test("profile projections") {
    // given
    val n = createLabeledNode(Map("name" -> "Seymour"), "Glass")
    val o = createNode()
    relate(n, o, "R1")
    relate(o, createLabeledNode(Map("name" -> "Zoey"), "Glass"), "R2")
    relate(o, createLabeledNode(Map("name" -> "Franny"), "Glass"), "R2")
    relate(o, createNode(), "R2")
    relate(o, createNode(), "R2")
    graph.createIndex("Glass", "name")

    // when
    val result = executeSingle(
      "profile match (n:Glass {name: 'Seymour'})-[:R1]->(o)-[:R2]->(p:Glass) USING INDEX n:Glass(name) return p.name", Map.empty)

    // then
    result.executionPlanDescription() should includeSomewhere.aPlan("Projection").withDBHits(2)
  }

  test("profile filter") {
    // given
    val n = createLabeledNode(Map("name" -> "Seymour"), "Glass")
    val o = createNode()
    relate(n, o, "R1")
    relate(o, createLabeledNode(Map("name" -> "Zoey"), "Glass"), "R2")
    relate(o, createLabeledNode(Map("name" -> "Franny"), "Glass"), "R2")
    relate(o, createNode(), "R2")
    relate(o, createNode(), "R2")
    graph.createIndex("Glass", "name")

    // when
    val result = executeSingle(
      "profile match (n:Glass {name: 'Seymour'})-[:R1]->(o)-[:R2]->(p) USING INDEX n:Glass(name) WHERE p.name = 'Franny' return p.name", Map.empty)

    // then
    result.executionPlanDescription() should (
      includeSomewhere.aPlan("Projection").withDBHits(1) and
        includeSomewhere.aPlan("Filter").withDBHits(4)
      )
  }

  test("joins with identical scans") {
    //given
    val corp = createLabeledNode("Company")

    //force a plan to have a scan on corp in both the lhs and the rhs of join
    val query =
    """PROFILE MATCH (a:Company) RETURN a
      |UNION
      |MATCH (a:Company) RETURN a""".stripMargin

    //when
    val result = executeSingle(query, Map.empty)

    result.toSet should be(Set(Map("a" -> corp), Map("a" -> corp)))

    //then
    result.executionPlanDescription() should includeSomewhere.aPlan("NodeByLabelScan").withRows(1).withDBHits(2)
  }

  //this test asserts a specific optimization in pipe building and is not
  //valid for the compiled runtime
  test("distinct should not look up properties every time") {
    // GIVEN
    createNode("prop" -> 42)
    createNode("prop" -> 42)

    // WHEN
    val result = executeSingle("PROFILE CYPHER runtime=interpreted MATCH (n) RETURN DISTINCT n.prop", Map.empty)

    // THEN
    result.executionPlanDescription() should includeSomewhere.aPlan("Distinct").withDBHits(2)
  }

  test("profile with filter using nested expressions pipe should report dbhits correctly") {
    // GIVEN
    createLabeledNode(Map("category_type" -> "cat"), "Category")
    createLabeledNode(Map("category_type" -> "cat"), "Category")
    val e1 = createLabeledNode(Map("domain_id" -> "1"), "Entity")
    val e2 = createLabeledNode(Map("domain_id" -> "2"), "Entity")
    val aNode = createNode()
    relate(aNode, e1)
    val anotherNode = createNode()
    relate(anotherNode, e2)

    relate(aNode, createNode(), "HAS_CATEGORY")
    relate(anotherNode, createNode(), "HAS_CATEGORY")

    // WHEN
    val result = profileWithExecute(Configs.InterpretedAndSlotted,
      """MATCH (cat:Category)
        |WITH collect(cat) as categories
        |MATCH (m:Entity)
        |WITH m, categories
        |MATCH (m)<-[r]-(n)
        |WHERE ANY(x IN categories WHERE (n)-[:HAS_CATEGORY]->(x))
        |RETURN count(n)""".stripMargin)

    // THEN
    result.executionPlanDescription() should includeSomewhere.aPlan("Filter").withDBHits(14)
  }

  test("profile pruning var length expand"){
    //some graph
    val a = createLabeledNode("Start")
    val b1 = createLabeledNode("Node")
    val b2 = createLabeledNode("Node")
    val b3 = createLabeledNode("Node")
    val b4 = createLabeledNode("Node")
    relate(a, b1, "T1")
    relate(b1, b2, "T1")
    relate(b2, b3, "T1")
    relate(b2, b4, "T1")

    val query = "profile match (b:Start)-[*3]->(d) return count(distinct d)"
    val result = profileWithExecute(Configs.InterpretedAndSlotted, query)

    result.executionPlanDescription() should includeSomewhere.aPlan("VarLengthExpand(Pruning)").withRows(2).withDBHits(7)

  }

  type Planner = (String, Map[String, Any]) => RewindableExecutionResult

  def profileWithPlanner(planner: Planner, q: String, params: Map[String, Any]): RewindableExecutionResult = {
    val result = planner("profile " + q, params)
    result.executionMode should equal(ProfileMode)

    val planDescription: InternalPlanDescription = result.executionPlanDescription()
    planDescription.flatten.foreach {
      p =>
        if (!p.arguments.exists(_.isInstanceOf[DbHits])) {
          fail("Found plan that was not profiled with DbHits: " + p.name)
        }
        if (!p.arguments.exists(_.isInstanceOf[Rows])) fail("Found plan that was not profiled with Rows: " + p.name)
    }
    result
  }

  def profileWithExecute(configuration: TestConfiguration, q: String): RewindableExecutionResult = {
    val result = executeWith(configuration, "profile " + q)
    result.executionMode should equal(ProfileMode)

    val planDescription: InternalPlanDescription = result.executionPlanDescription()
    planDescription.flatten.foreach {
      p =>
        if (!p.arguments.exists(_.isInstanceOf[DbHits])) {
          fail("Found plan that was not profiled with DbHits: " + p.name)
        }
        if (!p.arguments.exists(_.isInstanceOf[Rows])) fail("Found plan that was not profiled with Rows: " + p.name)
    }
    result
  }

  override def profile(q: String, params: (String, Any)*): RewindableExecutionResult = fail("Don't use profile all together in ProfilerAcceptanceTest")

  def legacyProfile(q: String, params: (String, Any)*): RewindableExecutionResult = profileWithPlanner(executeSingle, q, params.toMap)

  private def getArgument[A <: Argument](plan: InternalPlanDescription)(implicit manifest: ClassTag[A]): A = plan.arguments.collectFirst {
    case x: A => x
  }.getOrElse(fail(s"Failed to find plan description argument where expected. Wanted ${manifest.toString()} but only found ${plan.arguments}"))
}
