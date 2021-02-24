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

import org.neo4j.cypher.ExecutionEngineFunSuite
import org.neo4j.cypher.internal.runtime.PathImpl
import org.neo4j.cypher.internal.runtime.planDescription.InternalPlanDescription.Arguments.{EstimatedRows, ExpandExpression}
import org.neo4j.graphdb.Node
import org.neo4j.internal.cypher.acceptance.comparisonsupport.ComparePlansWithAssertion
import org.neo4j.internal.cypher.acceptance.comparisonsupport.Configs
import org.neo4j.internal.cypher.acceptance.comparisonsupport.CypherComparisonSupport
import org.neo4j.internal.cypher.acceptance.comparisonsupport.TestConfiguration
import org.neo4j.cypher.internal.v3_5.expressions.SemanticDirection
import org.scalatest.Matchers

class PatternExpressionImplementationAcceptanceTest extends ExecutionEngineFunSuite with Matchers with CypherComparisonSupport {

  // TESTS WITH CASE EXPRESSION

  test("match (n) return case when id(n) >= 0 then (n)-->() otherwise 42 as p") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) return case when id(n) >= 0 then (n)-->() else 42 end as p",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("Expand(All)")))

    result.toList.head("p").asInstanceOf[Seq[_]] should have size 2
  }

  test("pattern expression without any bound nodes") {

    val start = createLabeledNode("A")
    relate(start, createNode())
    relate(start, createNode())

    val query = "return case when true then (:A)-->() else 42 end as p"
    val result = executeWith(Configs.InterpretedAndSlotted - Configs.Version2_3 - Configs.RulePlanner, query,
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("Expand(All)")))

    result.toList.head("p").asInstanceOf[Seq[_]] should have size 2
  }

  test("match (n) return case when id(n) < 0 then (n)-->() otherwise 42 as p") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) return case when id(n) < 0 then (n)-->() else 42 end as p",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("Expand(All)")))

    result.toList.head("p").asInstanceOf[Long] should equal(42)
  }

  test("match (n) return case when n:A then (n)-->(:C) when n:B then (n)-->(:D) else 42 end as p") {
    val start = createLabeledNode("A")
    val c = createLabeledNode("C")
    val rel1 = relate(start, c)
    val rel2 = relate(start, c)
    val start2 = createLabeledNode("B")
    val d = createLabeledNode("D")
    val rel3 = relate(start2, d)
    val rel4 = relate(start2, d)

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) return case when n:A then (n)-->(:C) when n:B then (n)-->(:D) else 42 end as p")
      .toList.map(_.mapValues {
        case l: Seq[Any] => l.toSet
        case x => x
      }).toSet

    result should equal(Set(
      Map("p" -> Set(PathImpl(start, rel2, c), PathImpl(start, rel1, c))),
      Map("p" -> 42),
      Map("p" -> Set(PathImpl(start2, rel4, d), PathImpl(start2, rel3, d))),
      Map("p" -> 42)
    ))
  }

  test("match (n) with case when id(n) >= 0 then (n)-->() else 42 end as p, count(n) as c return p, c") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted,
      """match (n)
        |with case
        |       when id(n) >= 0 then (n)-->()
        |       else 42
        |     end as p, count(n) as c
        |return p, c order by c""".stripMargin)
      .toList.head("p").asInstanceOf[Seq[_]]

    result should have size 2
  }

  test("match (n) with case when id(n) < 0 then (n)-->() else 42 end as p, count(n) as c return p, c") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) with case when id(n) < 0 then (n)-->() else 42 end as p, count(n) as c return p, c")
      .toList.head("p").asInstanceOf[Long]

    result should equal(42)
  }

  test("match (n) with case when n:A then (n)-->(:C) when n:B then (n)-->(:D) else 42 end as p, count(n) as c return p, c") {
    val start = createLabeledNode("A")
    val c = createLabeledNode("C")
    val rel1 = relate(start, c)
    val rel2 = relate(start, c)
    val start2 = createLabeledNode("B")
    val d = createLabeledNode("D")
    val rel3 = relate(start2, d)
    val rel4 = relate(start2, d)

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) with case when n:A then (n)-->(:C) when n:B then (n)-->(:D) else 42 end as p, count(n) as c return p, c")
      .toList.map(_.mapValues {
        case l: Seq[Any] => l.toSet
        case x => x
      }).toSet

    result should equal(Set(
      Map("c" -> 1, "p" -> Set(PathImpl(start, rel2, c), PathImpl(start, rel1, c))),
      Map("c" -> 1, "p" -> Set(PathImpl(start2, rel4, d), PathImpl(start2, rel3, d))),
      Map("c" -> 2, "p" -> 42)
    ))
  }

  test("match (n) where (case when id(n) >= 0 then length((n)-->()) else 42 end) > 0 return n") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted + Configs.Morsel, "match (n) where (case when id(n) >= 0 then length((n)-->()) else 42 end) > 0 return n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))

    result.toList should equal(List(
      Map("n" -> start)
    ))
  }

  test("match (n) where (case when id(n) < 0 then length((n)-->()) else 42 end) > 0 return n") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted + Configs.Morsel, "match (n) where (case when id(n) < 0 then length((n)-->()) else 42 end) > 0 return n")

    result should have size 3
  }

  test("match (n) where (case when id(n) < 0 then length((n)-[:X]->()) else 42 end) > 0 return n") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted + Configs.Morsel, "match (n) where (case when id(n) < 0 then length((n)-[:X]->()) else 42 end) > 0 return n")

    result should have size 3
  }

  test("match (n) where (case when id(n) < 0 then length((n)-->(:X)) else 42 end) > 0 return n") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) where (case when id(n) < 0 then length((n)-->(:X)) else 42 end) > 0 return n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))

    result should have size 3
  }

  test("match (n) where (case when n:A then length((n)-->(:C)) when n:B then length((n)-->(:D)) else 42 end) > 1 return n") {
    val start = createLabeledNode("A")
    relate(start, createLabeledNode("C"))
    relate(start, createLabeledNode("C"))
    val start2 = createLabeledNode("B")
    relate(start2, createLabeledNode("D"))
    val start3 = createNode()
    relate(start3, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) where (n)-->() AND (case when n:A then length((n)-->(:C)) when n:B then length((n)-->(:D)) else 42 end) > 1 return n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))

    result.toList should equal(List(
      Map("n" -> start),
      Map("n" -> start3)
    ))
  }

  test("MATCH (n:FOO) WITH n, COLLECT(DISTINCT { res:CASE WHEN EXISTS ((n)-[:BAR*]->()) THEN 42 END }) as x RETURN n, x") {
    val node1 = createLabeledNode("FOO")
    val node2 = createNode()
    relate(node1, node2, "BAR")
    val result = executeWith(Configs.InterpretedAndSlotted - Configs.Cost3_1,
      """
        |MATCH (n:FOO)
        |WITH n, COLLECT (DISTINCT{
        |res:CASE WHEN EXISTS((n)-[:BAR*]->()) THEN 42 END
        |}) as x RETURN n, x
      """.stripMargin
    )

    result.toList should equal(List(Map("n" -> node1, "x" -> List(Map("res" -> 42)))))
  }

  test("case expressions and pattern expressions") {
    val n1 = createLabeledNode(Map("prop" -> 42), "A")

    relate(n1, createNode())
    relate(n1, createNode())
    relate(n1, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted,
      """match (a:A)
        |return case
        |         WHEN a.prop = 42 THEN []
        |         ELSE (a)-->()
        |       END as X
        |         """.stripMargin,
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))

    result.toList should equal(List(Map("X" -> Seq())))
  }

  test("should not use full expand 1") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    executeWith(Configs.InterpretedAndSlotted, "match (n) return case when id(n) >= 0 then (n)-->() else 42 end as p",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("Expand(All)")))
  }

  test("should not use full expand 2") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    executeWith(Configs.InterpretedAndSlotted, "match (n) return case when id(n) < 0 then (n)-->() else 42 end as p",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("Expand(All)")))
  }

  // TESTS WITH EXTRACT

  test("match (n) return extract(x IN (n)-->() | head(nodes(x)) )  as p") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) return extract(x IN (n)-->() | head(nodes(x)) )  as p")

    result.toList.head("p").asInstanceOf[Seq[_]] should equal(List(start, start))
  }

  test("match (n:A) with extract(x IN (n)-->() | head(nodes(x)) ) as p, count(n) as c return p, c") {
    val start = createLabeledNode("A")
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n:A) with extract(x IN (n)-->() | head(nodes(x)) ) as p, count(n) as c return p, c")
      .toList.head("p").asInstanceOf[Seq[_]]

    result should equal(List(start, start))
  }

  test("match (n) where n IN extract(x IN (n)-->() | head(nodes(x)) ) return n") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    val result = executeWith(Configs.InterpretedAndSlotted, "match (n) where n IN extract(x IN (n)-->() | head(nodes(x)) ) return n")
      .toList

    result should equal(List(
      Map("n" -> start)
    ))
  }

  // TESTS WITH PLANNING ASSERTIONS

  test("should use full expand") {
    val start = createNode()
    relate(start, createNode())
    relate(start, createNode())

    executeWith(Configs.InterpretedAndSlotted, "match (n)-->(b) with (n)-->() as p, count(b) as c return p, c",
      planComparisonStrategy = ComparePlansWithAssertion(_ should includeSomewhere.aPlan("Expand(All)"), expectPlansToFail = Configs.RulePlanner))
  }

  test("should use varlength expandInto when variables are bound") {
    val a = createLabeledNode("Start")
    val b = createLabeledNode("End")
    relate(a, b)

    executeWith(Configs.InterpretedAndSlotted, "match (a:Start), (b:End) with (a)-[*]->(b) as path, count(a) as c return path, c",
      planComparisonStrategy = ComparePlansWithAssertion(_ should includeSomewhere.aPlan("VarLengthExpand(Into)"),
        expectPlansToFail = Configs.RulePlanner + Configs.Version2_3))
  }

  // FAIL: <default version> <default planner> runtime=slotted returned different results than <default version> <default planner> runtime=interpreted List() did not contain the same elements as List(Map("r" -> (20000)-[T,0]->(20001)))
  test("should not use a label scan as starting point when statistics are bad") {
    graph.inTx {
      (1 to 10000).foreach { i =>
        createLabeledNode("A")
        createNode()
      }
    }
    relate(createNode(), createLabeledNode("A"), "T")

    executeWith(Configs.InterpretedAndSlotted, "PROFILE MATCH ()-[r]->() WHERE ()-[r]-(:A) RETURN r",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("NodeByLabelScan")))
  }

  test("should consider cardinality input when planning pattern expression in where clause") {
    // given
    val node = createLabeledNode("A")
    createLabeledNode("A")
    createLabeledNode("A")
    relate(node, createNode(), "HAS")

    val result = executeWith(Configs.InterpretedAndSlotted, "MATCH (n:A) WHERE (n)-[:HAS]->() RETURN n")

    val argumentPLan = result.executionPlanDescription().cd("NodeByLabelScan")
    val estimatedRows = argumentPLan.arguments.collect { case n: EstimatedRows => n }.head
    estimatedRows should equal(EstimatedRows(3.0))
  }

  test("should consider cardinality input when planning in return") {
    // given
    val node = createLabeledNode("A")
    createLabeledNode("A")
    createLabeledNode("A")
    val endNode = createNode()
    val rel = relate(node, endNode, "HAS")

    executeWith(Configs.InterpretedAndSlotted, "MATCH (n:A) RETURN (n)-[:HAS]->() as p",
      planComparisonStrategy = ComparePlansWithAssertion((planDescription) => {
        planDescription.find("Argument") shouldNot be(empty)
        planDescription.cd("Argument").arguments should equal(List(EstimatedRows(1)))
        planDescription.find("Expand(All)") shouldNot be(empty)
        val expandArgs = planDescription.cd("Expand(All)").arguments.toSet
        expandArgs should contain(EstimatedRows(0.25))
        expandArgs collect {
          case ExpandExpression("n", _, Seq("HAS"), _, SemanticDirection.OUTGOING, 1, Some(1)) => true
        } should not be empty
      }, Configs.RulePlanner + Configs.Version2_3))
  }

  test("should be able to execute aggregating-functions on pattern expressions") {
    // given
    val node = createLabeledNode("A")
    createLabeledNode("A")
    createLabeledNode("A")
    relate(node, createNode(), "HAS")

    executeWith(Configs.InterpretedAndSlotted, "MATCH (n:A) RETURN count((n)-[:HAS]->()) as c",
      planComparisonStrategy = ComparePlansWithAssertion(_ should includeSomewhere.aPlan("Expand(All)"),
        expectPlansToFail = Configs.RulePlanner + Configs.Version2_3))
  }

  test("use getDegree for simple pattern expression with length clause, outgoing") {
    setup()

    executeWith(Configs.InterpretedAndSlotted, "MATCH (n:X) WHERE LENGTH((n)-->()) > 2 RETURN n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("use getDegree for simple pattern expression with length clause, incoming") {
    setup()

    executeWith(Configs.InterpretedAndSlotted, "MATCH (n:X) WHERE LENGTH((n)<--()) > 2 RETURN n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("use getDegree for simple pattern expression with length clause, both") {
    setup()

    executeWith(Configs.InterpretedAndSlotted, "MATCH (n:X) WHERE LENGTH((n)--()) > 2 RETURN n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("use getDegree for simple pattern expression with rel-type ORs") {
    setup()

    executeWith(Configs.InterpretedAndSlotted + Configs.Morsel, "MATCH (n) WHERE length((n)-[:X|Y]->()) > 2 RETURN n",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }


  private val configurationWithPatternExpressionFix = Configs.InterpretedAndSlotted - Configs.Cost2_3 - TestConfiguration("3.4 runtime=slotted")

  test("solve pattern expressions in set node properties") {
    setup()

    executeWith(configurationWithPatternExpressionFix,
      "MATCH (n) SET n.friends = size((n)<--())",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("solve pattern expressions in set relationship properties") {
    setup()

    executeWith(configurationWithPatternExpressionFix,
      "MATCH (n)-[r]-() SET r.friends = size((n)<--())",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("solve pattern expressions in set node/relationship properties") {
    setup()

    executeWith(configurationWithPatternExpressionFix - Configs.Rule2_3,
      "MATCH (n)-[r]-() UNWIND [n,r] AS x SET x.friends = size((n)<--())",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("solve pattern expressions in set node properties from map") {
    setup()

    executeWith(configurationWithPatternExpressionFix,
      "MATCH (n) SET n += {friends: size((n)<--())}",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  test("solve pattern expressions in set relationship properties from map") {
    setup()

    executeWith(configurationWithPatternExpressionFix,
      "MATCH (n)-[r]-() SET r += {friends: size((n)<--())}",
      planComparisonStrategy = ComparePlansWithAssertion(_ shouldNot includeSomewhere.aPlan("RollUpApply")))
  }

  private def setup(): (Node, Node) = {
    val n1 = createLabeledNode("X")
    val n2 = createLabeledNode("X")

    relate(n1, createNode())
    relate(n1, createNode())
    relate(n1, createNode())
    relate(createNode(), n2)
    relate(createNode(), n2)
    relate(createNode(), n2)
    (n1, n2)
  }
}
