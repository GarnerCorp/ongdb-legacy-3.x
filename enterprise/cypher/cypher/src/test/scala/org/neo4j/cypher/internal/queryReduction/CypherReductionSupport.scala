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
package org.neo4j.cypher.internal.queryReduction

import org.neo4j.cypher.internal.compatibility.CommunityRuntimeContextCreator
import org.neo4j.cypher.internal.compatibility.v3_5.WrappedMonitors
import org.neo4j.cypher.internal.compiler.v3_5._
import org.neo4j.cypher.internal.compiler.v3_5.phases.{LogicalPlanState, PlannerContextCreator}
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.idp.{IDPQueryGraphSolver, IDPQueryGraphSolverMonitor, SingleComponentPlanner, cartesianProductsOrValueJoins}
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.{CachedMetricsFactory, SimpleMetricsFactory}
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.{Cardinalities, ProvidedOrders, Solveds}
import org.neo4j.cypher.internal.planner.v3_5.spi.{IDPPlannerName, PlanContext, PlannerNameFor, PlanningAttributes}
import org.neo4j.cypher.internal.queryReduction.DDmin.Oracle
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted._
import org.neo4j.cypher.internal.spi.codegen.GeneratedQueryStructure
import org.neo4j.cypher.internal.{CommunityRuntimeFactory, EnterpriseRuntimeContextCreator, MasterCompiler, RewindableExecutionResult}
import org.neo4j.cypher.{CypherRuntimeOption, GraphIcing}
import org.neo4j.internal.kernel.api.Transaction
import org.neo4j.internal.kernel.api.security.LoginContext
import org.neo4j.kernel.impl.coreapi.{InternalTransaction, PropertyContainerLocker}
import org.neo4j.kernel.impl.query.clientconnection.ClientConnectionInfo.EMBEDDED_CONNECTION
import org.neo4j.kernel.impl.query.{Neo4jTransactionalContextFactory, TransactionalContextFactory}
import org.neo4j.kernel.monitoring.Monitors
import org.neo4j.logging.NullLog
import org.neo4j.test.TestGraphDatabaseFactory
import org.neo4j.values.virtual.VirtualValues.EMPTY_MAP
import org.neo4j.cypher.internal.v3_5.ast._
import org.neo4j.cypher.internal.v3_5.ast.prettifier.{ExpressionStringifier, Prettifier}
import org.neo4j.cypher.internal.v3_5.ast.semantics.SemanticState
import org.neo4j.cypher.internal.v3_5.frontend.phases.CompilationPhaseTracer.NO_TRACING
import org.neo4j.cypher.internal.v3_5.frontend.phases._
import org.neo4j.cypher.internal.v3_5.rewriting.{Deprecations, RewriterStepSequencer}
import org.neo4j.cypher.internal.v3_5.util.attribution.SequentialIdGen
import org.neo4j.cypher.internal.v3_5.util.test_helpers.{CypherFunSuite, CypherTestSupport}

import scala.util.Try

object CypherReductionSupport {
  private val stepSequencer = RewriterStepSequencer.newPlain _
  private val metricsFactory = CachedMetricsFactory(SimpleMetricsFactory)
  private val config = CypherPlannerConfiguration(
    queryCacheSize = 0,
    statsDivergenceCalculator = StatsDivergenceCalculator.divergenceNoDecayCalculator(0, 0),
    useErrorsOverWarnings = false,
    idpMaxTableSize = 128,
    idpIterationDuration = 1000,
    errorIfShortestPathFallbackUsedAtRuntime = false,
    errorIfShortestPathHasCommonNodesAtRuntime = false,
    legacyCsvQuoteEscaping = false,
    csvBufferSize = CSVResources.DEFAULT_BUFFER_SIZE,
    nonIndexedLabelWarningThreshold = 0,
    planWithMinimumCardinalityEstimates = true,
    lenientCreateRelationship = true)
  private val kernelMonitors = new Monitors
  private val compiler = CypherPlanner(WrappedMonitors(kernelMonitors), stepSequencer, metricsFactory, config, defaultUpdateStrategy,
    MasterCompiler.CLOCK, PlannerContextCreator)

  private val monitor = kernelMonitors.newMonitor(classOf[IDPQueryGraphSolverMonitor])
  private val searchMonitor = kernelMonitors.newMonitor(classOf[IndexSearchMonitor])
  private val singleComponentPlanner = SingleComponentPlanner(monitor)
  private val queryGraphSolver = IDPQueryGraphSolver(singleComponentPlanner, cartesianProductsOrValueJoins, monitor)

  val prettifier = Prettifier(ExpressionStringifier())
}

/**
  * Do not mixin GraphDatabaseTestSupport when using this object.
  */
trait CypherReductionSupport extends CypherTestSupport with GraphIcing {
  self: CypherFunSuite  =>

  private var graph: GraphDatabaseCypherService = _
  private var contextFactory: TransactionalContextFactory = _

  override protected def initTest() {
    super.initTest()
    graph = new GraphDatabaseCypherService(new TestGraphDatabaseFactory().newImpermanentDatabase())
    contextFactory = Neo4jTransactionalContextFactory.create(graph, new PropertyContainerLocker())
  }

  override protected def stopTest() {
    try {
      super.stopTest()
    }
    finally {
      if (graph != null) graph.shutdown()
    }
  }

  private val rewriting = PreparatoryRewriting(Deprecations.V1) andThen
    SemanticAnalysis(warn = true).adds(BaseContains[SemanticState])

  def evaluate(query: String, executeBefore: Option[String] = None, enterprise: Boolean = false): RewindableExecutionResult = {
    val parsingBaseState = queryToParsingBaseState(query, enterprise)
    val statement = parsingBaseState.statement()
    produceResult(query, statement, parsingBaseState, executeBefore, enterprise)
  }

  def reduceQuery(query: String, executeBefore: Option[String] = None, enterprise: Boolean = false)(test: Oracle[Try[RewindableExecutionResult]]): String = {
    val oracle: Oracle[Try[(String, RewindableExecutionResult)]] = (tryTuple) => {
      val tryResult = tryTuple.map(_._2)
      test(tryResult)
    }
    reduceQueryWithCurrentQueryText(query, executeBefore, enterprise)(oracle)
  }

  def reduceQueryWithCurrentQueryText(query: String, executeBefore: Option[String] = None, enterprise: Boolean = false)(test: Oracle[Try[(String, RewindableExecutionResult)]]): String = {
    val parsingBaseState = queryToParsingBaseState(query, enterprise)
    val statement = parsingBaseState.statement()

    val oracle: Oracle[Statement] = (currentStatement) => {
      // Actual query
      val currentlyRunQuery = CypherReductionSupport.prettifier.asString(currentStatement)
      val tryResults = Try((currentlyRunQuery, produceResult(query, currentStatement, parsingBaseState, executeBefore, enterprise)))
      val testRes = test(tryResults)
      testRes
    }

    val smallerStatement = GTRStar(new StatementGTRInput(statement))(oracle)
    CypherReductionSupport.prettifier.asString(smallerStatement)
  }

  private def queryToParsingBaseState(query: String, enterprise: Boolean): BaseState = {
    val startState = LogicalPlanState(query, None, PlannerNameFor(IDPPlannerName.name), PlanningAttributes(new Solveds, new Cardinalities, new ProvidedOrders))
    val parsingContext = createContext(query, CypherReductionSupport.metricsFactory, CypherReductionSupport.config, null, null, enterprise)
    CompilationPhases.parsing(CypherReductionSupport.stepSequencer).transform(startState, parsingContext)
  }

  private def produceResult(query: String,
                            statement: Statement,
                            parsingBaseState: BaseState,
                            executeBefore: Option[String],
                            enterprise: Boolean): RewindableExecutionResult = {
    val explicitTx = graph.beginTransaction(Transaction.Type.explicit, LoginContext.AUTH_DISABLED)
    val implicitTx = graph.beginTransaction(Transaction.Type.`implicit`, LoginContext.AUTH_DISABLED)
    try {
      executeBefore match {
        case None =>
        case Some(setupQuery) =>
          val setupBS = queryToParsingBaseState(setupQuery, enterprise)
          val setupStm = setupBS.statement()
          executeInTx(setupQuery, setupStm, setupBS, implicitTx, enterprise)
      }
      executeInTx(query, statement, parsingBaseState, implicitTx, enterprise)
    } finally {
      explicitTx.failure()
      explicitTx.close()
    }
  }

  private def executeInTx(query: String,
                          statement: Statement,
                          parsingBaseState: BaseState,
                          implicitTx: InternalTransaction,
                          enterprise: Boolean
                         ): RewindableExecutionResult = {
    val neo4jtxContext = contextFactory.newContext(EMBEDDED_CONNECTION, implicitTx, query, EMPTY_MAP)
    val txContextWrapper = TransactionalContextWrapper(neo4jtxContext)
    val planContext = TransactionBoundPlanContext(txContextWrapper, devNullLogger)

    var baseState = parsingBaseState.withStatement(statement)
    val planningContext = createContext(query, CypherReductionSupport.metricsFactory, CypherReductionSupport.config, planContext, CypherReductionSupport.queryGraphSolver, enterprise)


    baseState = rewriting.transform(baseState, planningContext)

    val logicalPlanState = CypherReductionSupport.compiler.planPreparedQuery(baseState, planningContext)
    val readOnly = logicalPlanState.planningAttributes.solveds(logicalPlanState.logicalPlan.id).readOnly

    val runtime = CommunityRuntimeFactory.getRuntime(CypherRuntimeOption.default, planningContext.config.useErrorsOverWarnings)

    val runtimeContextCreator = if (enterprise)
      EnterpriseRuntimeContextCreator(
        GeneratedQueryStructure,
        NullLog.getInstance(),
        CypherReductionSupport.config,
        morselRuntimeState = null)
     else
      CommunityRuntimeContextCreator( NullLog.getInstance(), CypherReductionSupport.config)

    val runtimeContext = runtimeContextCreator.create(planContext, MasterCompiler.CLOCK, Set(), readOnly, enterprise)
    val executionPlan = runtime.compileToExecutable(logicalPlanState, runtimeContext)

    val queryContext = new TransactionBoundQueryContext(txContextWrapper)(CypherReductionSupport.searchMonitor)

    val runtimeResult = executionPlan.run(queryContext, doProfile = false, ValueConversion.asValues(baseState.extractedParams()))
    RewindableExecutionResult(runtimeResult, queryContext)
  }

  private def createContext(query: String, metricsFactory: CachedMetricsFactory,
                            config: CypherPlannerConfiguration,
                            planContext: PlanContext,
                            queryGraphSolver: IDPQueryGraphSolver, enterprise: Boolean) = {
    val logicalPlanIdGen = new SequentialIdGen()
    PlannerContextCreator.create(NO_TRACING, devNullLogger, planContext, query, Set(),
                                 None, WrappedMonitors(new Monitors), metricsFactory,
                                 queryGraphSolver, config, defaultUpdateStrategy, MasterCompiler.CLOCK,
                                 logicalPlanIdGen, null)
  }
}
