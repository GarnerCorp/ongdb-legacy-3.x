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
package org.neo4j.cypher.internal.runtime.compiled.expressions

import java.util.regex

import org.neo4j.cypher.internal.compatibility.v3_5.runtime.{LongSlot, RefSlot, SlotConfiguration}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.ast._
import org.neo4j.cypher.internal.compiler.v3_5.helpers.PredicateHelper.isPredicate
import org.neo4j.cypher.internal.runtime.DbAccess
import org.neo4j.cypher.internal.runtime.compiled.expressions.IntermediateRepresentation.{load, method}
import org.neo4j.cypher.internal.runtime.interpreted.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.pipes.NestedPipeExpression
import org.neo4j.cypher.internal.v3_5.logical.plans.{CoerceToPredicate, NestedPlanExpression}
import org.neo4j.cypher.operations.{CypherBoolean, CypherCoercions, CypherFunctions, CypherMath}
import org.neo4j.internal.kernel.api.procs.Neo4jTypes
import org.neo4j.internal.kernel.api.procs.Neo4jTypes.AnyType
import org.neo4j.values.AnyValue
import org.neo4j.values.storable._
import org.neo4j.values.virtual._
import org.neo4j.cypher.internal.v3_5.expressions
import org.neo4j.cypher.internal.v3_5.expressions._
import org.neo4j.cypher.internal.v3_5.util.symbols.{CTAny, CTBoolean, CTDate, CTDateTime, CTDuration, CTFloat, CTGeometry, CTInteger, CTLocalDateTime, CTLocalTime, CTMap, CTNode, CTNumber, CTPath, CTPoint, CTRelationship, CTString, CTTime, CypherType, ListType}
import org.neo4j.cypher.internal.v3_5.util.{CypherTypeException, InternalException}

import scala.collection.mutable

/**
  * Produces IntermediateRepresentation from a Cypher Expression
  */
class IntermediateCodeGeneration(slots: SlotConfiguration) {

  private val namer = new VariableNamer

  private class VariableNamer {
    private var counter: Int = 0
    private val parameters = mutable.Map.empty[String, String]
    private val variables = mutable.Map.empty[String, String]
    def nextVariableName(): String = {
      val nextName = s"v$counter"
      counter += 1
      nextName
    }

    def parameterName(name: String): String = parameters.getOrElseUpdate(name, nextVariableName())
    def variableName(name: String): String = variables.getOrElseUpdate(name, nextVariableName())
  }

  import IntermediateCodeGeneration._
  import IntermediateRepresentation._

  def compileProjection(projections: Map[Int, IntermediateExpression]): IntermediateExpression = {
    val all = projections.toSeq.map {
      case (slot, value) => setRefAt(slot,
                                     None,
                                     if (value.nullCheck.isEmpty) value.ir
                                     else ternary(value.nullCheck.reduceLeft((acc,current) => or(acc, current)),
                                                                              noValue, value.ir))
    }
    IntermediateExpression(block(all:_*), projections.values.flatMap(_.fields).toSeq,
                                projections.values.flatMap(_.variables).toSeq, Set.empty)
  }

  def compileExpression(expression: Expression): Option[IntermediateExpression] = internalCompileExpression(expression, None)

  private def internalCompileExpression(expression: Expression, currentContext: Option[IntermediateRepresentation]): Option[IntermediateExpression] = expression match {

    //functions
    case c: FunctionInvocation => compileFunction(c, currentContext)

    //math
    case Multiply(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("multiply"), l.ir, r.ir),
          l.fields ++ r.fields, l.variables ++ r.variables, l.nullCheck ++ r.nullCheck)
      }

    case expressions.Add(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("add"), l.ir, r.ir),
          l.fields ++ r.fields, l.variables ++ r.variables, l.nullCheck ++ r.nullCheck)
      }

    case UnaryAdd(source) => internalCompileExpression(source, currentContext)

    case expressions.Subtract(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("subtract"), l.ir, r.ir),
          l.fields ++ r.fields, l.variables ++ r.variables, l.nullCheck ++ r.nullCheck)
      }

    case UnarySubtract(source) =>
      for {arg <- internalCompileExpression(source, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("subtract"),
                       getStatic[Values, IntegralValue]("ZERO_INT"), arg.ir), arg.fields, arg.variables, arg.nullCheck)
      }

    case Divide(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("divide"), l.ir, r.ir),
          l.fields ++ r.fields, l.variables ++ r.variables,
          Set(invokeStatic(method[CypherMath, Boolean, AnyValue, AnyValue]("divideCheckForNull"),
                           nullCheck(l)(l.ir), nullCheck(r)(r.ir))))
      }

    case Modulo(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("modulo"), l.ir, r.ir),
          l.fields ++ r.fields, l.variables ++ r.variables, l.nullCheck ++ r.nullCheck)
      }

    case Pow(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherMath, AnyValue, AnyValue, AnyValue]("pow"), l.ir, r.ir),
          l.fields ++ r.fields, l.variables ++ r.variables, l.nullCheck ++ r.nullCheck)
      }

    //literals
    case d: DoubleLiteral => Some(IntermediateExpression(
      invokeStatic(method[Values, DoubleValue, Double]("doubleValue"), constant(d.value)), Seq.empty, Seq.empty,
      Set.empty))
    case i: IntegerLiteral => Some(IntermediateExpression(
      invokeStatic(method[Values, LongValue, Long]("longValue"), constant(i.value)), Seq.empty, Seq.empty, Set.empty))
    case s: expressions.StringLiteral => Some(IntermediateExpression(
      invokeStatic(method[Values, TextValue, String]("stringValue"), constant(s.value)), Seq.empty, Seq.empty,
      Set.empty))
    case _: Null => Some(IntermediateExpression(noValue, Seq.empty, Seq.empty, Set(constant(true))))
    case _: True => Some(IntermediateExpression(truthValue, Seq.empty, Seq.empty, Set.empty))
    case _: False => Some(IntermediateExpression(falseValue, Seq.empty, Seq.empty, Set.empty))
    case ListLiteral(args) =>
      val in = args.flatMap(internalCompileExpression(_, currentContext))
      if (in.size < args.size) None
      else {
        val fields: Seq[Field] = in.foldLeft(Seq.empty[Field])((a, b) => a ++ b.fields)
        val variables: Seq[LocalVariable] = in.foldLeft(Seq.empty[LocalVariable])((a, b) => a ++ b.variables)
        Some(IntermediateExpression(
          invokeStatic(method[VirtualValues, ListValue, Array[AnyValue]]("list"), arrayOf(in.map(_.ir): _*)),
          fields, variables, Set.empty))
      }

    case MapExpression(items) =>
      val compiled = (for {(k, v) <- items
                           c <- internalCompileExpression(v, currentContext)} yield k -> c).toMap
      if (compiled.size < items.size) None
      else {
        val tempVariable = namer.nextVariableName()
        val ops = Seq(
          declare[MapValueBuilder](tempVariable),
          assign(tempVariable, newInstance(constructor[MapValueBuilder, Int], constant(compiled.size)))
        ) ++ compiled.map {
          case (k, v) => invokeSideEffect(load(tempVariable),
                                          method[MapValueBuilder, AnyValue, String, AnyValue]("add"),
                                          constant(k.name), nullCheck(v)(v.ir))
        } :+ invoke(load(tempVariable), method[MapValueBuilder, MapValue]("build"))

        Some(IntermediateExpression(block(ops: _*), compiled.values.flatMap(_.fields).toSeq,
                                    compiled.values.flatMap(_.variables).toSeq, Set.empty))
      }

    case ListSlice(collection, None, None) => internalCompileExpression(collection, currentContext)

    case ListSlice(collection, Some(from), None) =>
      for {c <- internalCompileExpression(collection, currentContext)
           f <- internalCompileExpression(from, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, ListValue, AnyValue, AnyValue]("fromSlice"), c.ir, f.ir),
          c.fields ++ f.fields, c.variables ++ f.variables, c.nullCheck ++ f.nullCheck)

      }

    case ListSlice(collection, None, Some(to)) =>
      for {c <- internalCompileExpression(collection, currentContext)
           t <- internalCompileExpression(to, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, ListValue, AnyValue, AnyValue]("toSlice"), c.ir, t.ir),
          c.fields ++ t.fields, c.variables ++ t.variables, c.nullCheck ++ t.nullCheck)
      }

    case ListSlice(collection, Some(from), Some(to)) =>
      for {c <- internalCompileExpression(collection, currentContext)
           f <- internalCompileExpression(from, currentContext)
           t <- internalCompileExpression(to, currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, ListValue, AnyValue, AnyValue, AnyValue]("fullSlice"), c.ir, f.ir, t.ir),
          c.fields ++ f.fields ++ t.fields, c.variables ++ f.variables ++ t.variables,
          c.nullCheck ++ f.nullCheck ++ t.nullCheck)
      }

    case Variable(name) =>
      val loadVar = invokeStatic(
        method[CompiledHelpers, AnyValue, ExecutionContext, String]("loadVariable"),
        loadContext(currentContext), constant(name))

      Some(IntermediateExpression(loadVar, Seq.empty, Seq.empty, Set(equal(loadVar, noValue))))

    case SingleIterablePredicate(scope, collectionExpression) =>
      /*
        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.createClone();
        Iterator<AnyValue> listIterator = list.iterator();
        int matches = 0;
        boolean isNull = false;
        while( matches < 2 && listIterator.hasNext() )
        {
            AnyValue currentValue = listIterator.next();
            innerContext.set([name from scope], currentValue);
            Value isMatch = [result from inner expression using innerContext]
            if (isMatch == Values.TRUE)
            {
                matches++;
            }
            if (isMatch == Values.NO_VALUE)
            {
                isNull = true;
            }
        }
        return (matches < 2 && isNull) ? Values.NO_VALUE : Values.booleanValue(matches == 1);
       */
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           inner <- internalCompileExpression(scope.innerPredicate.get, Some(load(innerContext)))//Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val matches = namer.nextVariableName()
        val isNull = namer.nextVariableName()
        val isMatch = namer.nextVariableName()
        val ops = Seq(
          // ListValue list = [evaluate collection expression];
          // ExecutionContext innerContext = context.createClone();
          // int matches = 0;
          // boolean isNull = false;
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext), method[ExecutionContext, ExecutionContext]("createClone"))),
          declare[Int](matches),
          assign(matches, constant(0)),
          declare[Boolean](isNull),
          assign(isNull, constant(false)),
          // Iterator<AnyValue> listIterator = list.iterator();
          // while( matches < 2 && listIterator.hasNext())
          // {
          //    AnyValue currentValue = listIterator.next();
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          loop(and(lessThan(load(matches), constant(2)), invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext")))) {block(Seq(
            declare[AnyValue](currentValue),
            assign(currentValue, cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
            //innerContext.set([name from scope], currentValue);
            contextSet(scope.variable.name, load(innerContext), load(currentValue)),
            // Value isMatch = [result from inner expression using innerContext]
            // if (isMatch == Values.TRUE)
            // {
            //     matches = matches + 1;
            // }
            declare[Value](isMatch),
            assign(isMatch, nullCheck(inner)(inner.ir)),
            condition(equal(load(isMatch), truthValue))(
              assign(matches, add(load(matches), constant(1)))
            ),
            // if (isMatch == Values.NO_VALUE)
            // {
            //     isNull=true;
            // }
            condition(equal(load(isMatch), noValue))(
              assign(isNull, constant(true))
            )
          ):_*)
          },
          // }
          // return (matches < 2 && isNull) ? Values.NO_VALUE : Values.booleanValue(matches==1);
          ternary(and(lessThan(load(matches), constant(2)), load(isNull)),
            noValue,
            invokeStatic(method[Values, BooleanValue, Boolean]("booleanValue"), equal(load(matches), constant(1))))
        )
        IntermediateExpression(block(ops:_*), collection.fields ++ inner.fields,  collection.variables ++ inner.variables,
          collection.nullCheck)
      }

    case NoneIterablePredicate(scope, collectionExpression) =>
      /*
        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.createClone();
        Iterator<AnyValue> listIterator = list.iterator();
        Value isMatch = listIterator.hasNext() ? Values.NO_VALUE : Values.FALSE;
        boolean isNull = false;
        while( isMatch != Values.TRUE && listIterator.hasNext() )
        {
            AnyValue currentValue = listIterator.next();
            innerContext.set([name from scope], currentValue);
            isMatch = [result from inner expression using innerContext]
            if (isMatch == Values.NO_VALUE)
            {
                isNull = true;
            }
        }
        return (isNull && isMatch != Values.TRUE) ? Values.NO_VALUE : Values.booleanValue(isMatch == Values.FALSE);
       */
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           inner <- internalCompileExpression(scope.innerPredicate.get,
                                              Some(load(innerContext))) //Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val isMatch = namer.nextVariableName()
        val isNull = namer.nextVariableName()
        val ops = Seq(
          // ListValue list = [evaluate collection expression];
          // ExecutionContext innerContext = context.createClone();
          // Value isMatch = Values.NO_VALUE;
          // boolean isNull = false;
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext), method[ExecutionContext, ExecutionContext]("createClone"))),
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          declare[Value](isMatch),
          //assign(isMatch, noValue),
          assign(isMatch, ternary(invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext")), noValue, falseValue)),
          declare[Boolean](isNull),
          assign(isNull, constant(false)),
          // Iterator<AnyValue> listIterator = list.iterator();
          // while( isMatch != Values.TRUE, && listIterator.hasNext() )
          // {
          //    AnyValue currentValue = listIterator.next();
          loop(and(notEqual(load(isMatch), truthValue),
                   invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext")))) {
            block(Seq(
              declare[AnyValue](currentValue),
              assign(currentValue,
                     cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
              //innerContext.set([name from scope], currentValue);
              contextSet(scope.variable.name, load(innerContext), load(currentValue)),
              // isMatch = [result from inner expression using innerContext]
              assign(isMatch, nullCheck(inner)(inner.ir)),
              // if (isMatch == Values.NO_VALUE)
              // {
              //     isNull=true;
              // }
              condition(equal(load(isMatch), noValue))(
                assign(isNull, constant(true))
              )
            ): _*)
          },
          // }
          // return (isNull && isMatch != Values.TRUE) ? Values.NO_VALUE : Values.booleanValue(isMatch == Values.FALSE);
          ternary(and(load(isNull), notEqual(load(isMatch), truthValue)),
                  noValue,
                  invokeStatic(method[Values, BooleanValue, Boolean]("booleanValue"), equal(load(isMatch), falseValue)))
        )
        IntermediateExpression(block(ops: _*), collection.fields ++ inner.fields,
                               collection.variables ++ inner.variables,
                               collection.nullCheck)
      }

    case AnyIterablePredicate(scope, collectionExpression) =>
      /*
        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.createClone();
        Iterator<AnyValue> listIterator = list.iterator();
        Value isMatch = Values.FALSE;
        boolean isNull = false;
        while( isMatch != Values.TRUE && listIterator.hasNext() )
        {
            AnyValue currentValue = listIterator.next();
            innerContext.set([name from scope], currentValue);
            isMatch = [result from inner expression using innerContext]
            if (isMatch == Values.NO_VALUE)
            {
                isNull = true;
            }
        }
        return (isNull && isMatch != Values.TRUE) ? Values.NO_VALUE : isMatch;
       */
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           inner <- internalCompileExpression(scope.innerPredicate.get,
                                              Some(load(innerContext))) //Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val isMatch = namer.nextVariableName()
        val isNull = namer.nextVariableName()
        val ops = Seq(
          // ListValue list = [evaluate collection expression];
          // ExecutionContext innerContext = context.createClone();
          // Value isMatch = Values.FALSE;
          // boolean isNull = false;
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext), method[ExecutionContext, ExecutionContext]("createClone"))),
          declare[Value](isMatch),
          assign(isMatch, falseValue),
          declare[Boolean](isNull),
          assign(isNull, constant(false)),
          // Iterator<AnyValue> listIterator = list.iterator();
          // while( isMatch != Values.TRUE listIterator.hasNext())
          // {
          //    AnyValue currentValue = listIterator.next();
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          loop(and(notEqual(load(isMatch), truthValue),
                   invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext")))) {
            block(Seq(
              declare[AnyValue](currentValue),
              assign(currentValue,
                     cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
              //innerContext.set([name from scope], currentValue);
              contextSet(scope.variable.name, load(innerContext), load(currentValue)),
              // isMatch = [result from inner expression using innerContext]
              assign(isMatch, nullCheck(inner)(inner.ir)),
              // if (isMatch == Values.NO_VALUE)
              // {
              //     isNull=true;
              // }
              condition(equal(load(isMatch), noValue))(
                assign(isNull, constant(true))
              )
            ): _*)
          },
          // }
          // return (isNull && isMatch != Values.TRUE) ? Values.NO_VALUE : isMatch;
          ternary(and(load(isNull), notEqual(load(isMatch), truthValue)),
                  noValue,
                  load(isMatch))
        )
        IntermediateExpression(block(ops: _*), collection.fields ++ inner.fields,
                               collection.variables ++ inner.variables,
                               collection.nullCheck)
      }

    case AllIterablePredicate(scope, collectionExpression) =>
      /*
        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.createClone();
        Iterator<AnyValue> listIterator = list.iterator();
        Value isMatch = Values.TRUE;
        while( isMatch==Values.TRUE && listIterator.hasNext() )
        {
            AnyValue currentValue = listIterator.next();
            innerContext.set([name from scope], currentValue);
            isMatch = [result from inner expression using innerContext]
        }
        return isMatch;
       */
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           inner <- internalCompileExpression(scope.innerPredicate.get,
                                              Some(load(innerContext))) //Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val isMatch = namer.nextVariableName()
        val ops = Seq(
          // ListValue list = [evaluate collection expression];
          // ExecutionContext innerContext = context.createClone();
          // Value isMatch = Values.TRUE;
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext), method[ExecutionContext, ExecutionContext]("createClone"))),
          declare[Value](isMatch),
          assign(isMatch, truthValue),
          // Iterator<AnyValue> listIterator = list.iterator();
          // while( isMatch==Values.TRUE && listIterator.hasNext())
          // {
          //    AnyValue currentValue = listIterator.next();
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          loop(and(equal(load(isMatch), truthValue),
                   invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext")))) {
            block(Seq(
              declare[AnyValue](currentValue),
              assign(currentValue,
                     cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
              //innerContext.set([name from scope], currentValue);
              contextSet(scope.variable.name, load(innerContext), load(currentValue)),
              // isMatch = [result from inner expression using innerContext]
              assign(isMatch, nullCheck(inner)(inner.ir))
            ): _*)
          },
          // }
          // return isMatch;
          load(isMatch)
        )
        IntermediateExpression(block(ops: _*), collection.fields ++ inner.fields,
                               collection.variables ++ inner.variables,
                               collection.nullCheck)
      }

    case FilterExpression(scope, collectionExpression) =>
      /*
        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.createClone();
        ArrayList<AnyValue> filtered = new ArrayList<>();
        Iterator<AnyValue> listIterator = list.iterator();
        while( listIterator.hasNext() )
        {
            AnyValue currentValue = listIterator.next();
            innerContext.set([name from scope], currentValue);
            Value isFiltered = [result from inner expression using innerContext]
            if (isFiltered == Values.TRUE)
            {
                filtered.add(currentValue);
            }
        }
        return VirtualValues.fromList(filtered);
       */
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           inner <- internalCompileExpression(scope.innerPredicate.get,
                                              Some(load(innerContext))) //Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val filteredVars = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val isFiltered = namer.nextVariableName()
        val ops = Seq(
          // ListValue list = [evaluate collection expression];
          // ExecutionContext innerContext = context.createClone();
          // ArrayList<AnyValue> filtered = new ArrayList<>();
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext), method[ExecutionContext, ExecutionContext]("createClone"))),
          declare[java.util.ArrayList[AnyValue]](filteredVars),
          assign(filteredVars, newInstance(constructor[java.util.ArrayList[AnyValue]])),
          // Iterator<AnyValue> listIterator = list.iterator();
          // while( listIterator.hasNext() )
          // {
          //    AnyValue currentValue = listIterator.next();
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          loop(invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext"))) {
            block(Seq(
              declare[AnyValue](currentValue),
              assign(currentValue,
                     cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
              //innerContext.set([name from scope], currentValue);
              contextSet(scope.variable.name, load(innerContext), load(currentValue)),
              declare[Value](isFiltered),
              // Value isFiltered = [result from inner expression using innerContext]
              assign(isFiltered, nullCheck(inner)(inner.ir)),
              // if (isFiltered == Values.TRUE)
              // {
              //    filtered.add(currentValue);
              // }
              condition(equal(load(isFiltered), truthValue))(
                invokeSideEffect(load(filteredVars), method[java.util.ArrayList[_], Boolean, Object]("add"),
                                 load(currentValue))
              )): _*)
          },
          // }
          // return VirtualValues.fromList(extracted);
          invokeStatic(method[VirtualValues, ListValue, java.util.List[AnyValue]]("fromList"), load(filteredVars))
        )
        IntermediateExpression(block(ops: _*), collection.fields ++ inner.fields,
                               collection.variables ++ inner.variables,
                               collection.nullCheck)
      }

    case ExtractExpression(scope, collectionExpression) =>
      /*
        extract is tricky because it modifies the scope for future expressions. The generated code will be something
        along the line of:

        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.createClone();
        ArrayList<AnyValue> extracted = new ArrayList<>();
        for ( AnyValue currentValue : list ) {
            innerContext.set([name from scope], currentValue);
            extracted.add([result from inner expression using innerContext]);
        }
        return VirtualValues.fromList(extracted);
       */
      //this is the context inner expressions should see
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           inner <- internalCompileExpression(scope.extractExpression.get,
                                              Some(load(innerContext))) //Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val extractedVars = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val ops = Seq(
          //ListValue list = [evaluate collection expression];
          //ExecutionContext innerContext = context.createClone();
          //ArrayList<AnyValue> extracted = new ArrayList<>();
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext), method[ExecutionContext, ExecutionContext]("createClone"))),
          declare[java.util.ArrayList[AnyValue]](extractedVars),
          assign(extractedVars, newInstance(constructor[java.util.ArrayList[AnyValue]])),
          //Iterator<AnyValue> iter = list.iterator();
          //while (iter.hasNext) {
          //   AnyValue currentValue = iter.next();
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          loop(invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext"))) {
            block(Seq(
              declare[AnyValue](currentValue),
              assign(currentValue,
                     cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
              //innerContext.set([name from scope], currentValue);
              contextSet(scope.variable.name, load(innerContext), load(currentValue)),
              //extracted.add([result from inner expression using innerContext]);
              invokeSideEffect(load(extractedVars), method[java.util.ArrayList[_], Boolean, Object]("add"),
                               nullCheck(inner)(inner.ir))): _*)
          },
          // }
          // return VirtualValues.fromList(extracted);
          invokeStatic(method[VirtualValues, ListValue, java.util.List[AnyValue]]("fromList"), load(extractedVars))
        )
        IntermediateExpression(block(ops: _*), collection.fields ++ inner.fields,
                               collection.variables ++ inner.variables,
                               collection.nullCheck)
      }

    case ReduceExpression(scope, initExpression, collectionExpression) =>
      /*
        reduce is tricky because it modifies the scope for future expressions. The generated code will be something
        along the line of:

        ListValue list = [evaluate collection expression];
        ExecutionContext innerContext = context.copyWith(acc, init);
        for ( AnyValue currentValue : list ) {
            innerContext.set([name from scope], currentValue);
            innerContext.set(acc, [result from inner expression using innerContext[)
        }
        return innerContext.apply(acc)
       */
      //this is the context inner expressions should see
      val innerContext = namer.nextVariableName()
      val iterVariable = namer.nextVariableName()
      for {collection <- internalCompileExpression(collectionExpression, currentContext)
           init <- internalCompileExpression(initExpression, currentContext)
           inner <- internalCompileExpression(scope.expression,
                                              Some(load(innerContext))) //Note we update the context here
      } yield {
        val listVar = namer.nextVariableName()
        val currentValue = namer.nextVariableName()
        val ops = Seq(
          //ListValue list = [evaluate collection expression];
          //ExecutionContext innerContext = context.copyWith(acc, init);
          declare[ListValue](listVar),
          assign(listVar, invokeStatic(method[CypherFunctions, ListValue, AnyValue]("makeTraversable"), collection.ir)),
          declare[ExecutionContext](innerContext),
          assign(innerContext,
                 invoke(loadContext(currentContext),
                        method[ExecutionContext, ExecutionContext, String, AnyValue]("copyWith"),
                        constant(scope.accumulator.name), nullCheck(init)(init.ir))),
          //Iterator<AnyValue> iter = list.iterator();
          //while (iter.hasNext) {
          //   AnyValue currentValue = iter.next();
          declare[java.util.Iterator[AnyValue]](iterVariable),
          assign(iterVariable, invoke(load(listVar), method[ListValue, java.util.Iterator[AnyValue]]("iterator"))),
          loop(invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Boolean]("hasNext"))) {
            block(Seq(
              declare[AnyValue](currentValue),
              assign(currentValue,
                     cast[AnyValue](invoke(load(iterVariable), method[java.util.Iterator[AnyValue], Object]("next")))),
              // innerContext.set([name from scope], currentValue);
              contextSet(scope.variable.name, load(innerContext), load(currentValue)),
              //innerContext.set(acc, [inner expression using innerContext])
              contextSet(scope.accumulator.name, load(innerContext), nullCheck(inner)(inner.ir))
            ): _*)
          },
          //return innerContext(acc);
          cast[AnyValue](invoke(load(innerContext), method[ExecutionContext, Object, Object]("apply"),
                                constant(scope.accumulator.name)))
        )
        IntermediateExpression(block(ops: _*), collection.fields ++ inner.fields ++ init.fields, collection.variables ++
          inner.variables ++ init.variables, collection.nullCheck ++ init.nullCheck)
      }

    //boolean operators
    case Or(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        val left = if (isPredicate(lhs)) l else coerceToPredicate(l)
        val right = if (isPredicate(rhs)) r else coerceToPredicate(r)
        generateOrs(List(left, right))
      }

    case Ors(expressions) =>
      val compiled = expressions.foldLeft[Option[List[(IntermediateExpression, Boolean)]]](Some(List.empty))
        { (acc, current) =>
          for {l <- acc
               e <- internalCompileExpression(current, currentContext)} yield l :+ (e -> isPredicate(current))
        }

      for (e <- compiled) yield e match {
        case Nil => IntermediateExpression(truthValue, Seq.empty, Seq.empty,
                                           Set.empty) //this will not really happen because of rewriters etc
        case (a, isPredicate) :: Nil => if (isPredicate) a else coerceToPredicate(a)
        case list =>
          val coerced = list.map {
            case (p, true) => p
            case (p, false) => coerceToPredicate(p)
          }
          generateOrs(coerced)
      }

    case Xor(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        val left = if (isPredicate(lhs)) l else coerceToPredicate(l)
        val right = if (isPredicate(rhs)) r else coerceToPredicate(r)
        IntermediateExpression(
          invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("xor"), left.ir, right.ir),
          l.fields ++ r.fields, l.variables ++ r.variables, l.nullCheck ++ r.nullCheck)
      }

    case And(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        val left = if (isPredicate(lhs)) l else coerceToPredicate(l)
        val right = if (isPredicate(rhs)) r else coerceToPredicate(r)
        generateAnds(List(left, right))
      }

    case Ands(expressions) =>
      val compiled = expressions.foldLeft[Option[List[(IntermediateExpression, Boolean)]]](Some(List.empty))
        { (acc, current) =>
          for {l <- acc
               e <- internalCompileExpression(current, currentContext)} yield l :+ (e -> isPredicate(current))
        }

      for (e <- compiled) yield e match {
        case Nil => IntermediateExpression(truthValue, Seq.empty, Seq.empty,
                                           Set.empty) //this will not really happen because of rewriters etc
        case (a, isPredicate) :: Nil => if (isPredicate) a else coerceToPredicate(a)
        case list =>
          val coerced = list.map {
            case (p, true) => p
            case (p, false) => coerceToPredicate(p)
          }
          generateAnds(coerced)
      }

    case AndedPropertyInequalities(_, _, inequalities) =>
      val compiledInequalities = inequalities.toIndexedSeq.flatMap(i => internalCompileExpression(i, currentContext))
      if (compiledInequalities.size < inequalities.size) None
      else Some(generateAnds(compiledInequalities.toList))

    case expressions.Not(arg) =>
      internalCompileExpression(arg, currentContext).map(a => {
        val in = if (isPredicate(arg)) a else coerceToPredicate(a)
        IntermediateExpression(
          invokeStatic(method[CypherBoolean, Value, AnyValue]("not"), in.ir), in.fields, in.variables, in.nullCheck)
      })

    case Equals(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        val variableName = namer.nextVariableName()
        val local = variable[Value](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(l, r)(
          invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("equals"), l.ir, r.ir))))
        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, l.fields ++ r.fields, l.variables ++ r.variables :+ local, Set(nullChecks))
      }

    case NotEquals(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield {
        val variableName = namer.nextVariableName()
        val local = variable[Value](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(l, r)(invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("notEquals"), l.ir, r.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, l.fields ++ r.fields, l.variables ++ r.variables :+ local, Set(nullChecks))
      }

    case CoerceToPredicate(inner) => internalCompileExpression(inner, currentContext).map(coerceToPredicate)

    case RegexMatch(lhs, rhs) => rhs match {
      case expressions.StringLiteral(name) =>
        for (e <- internalCompileExpression(lhs, currentContext)) yield {
          val f = field[regex.Pattern](namer.nextVariableName())
          val ops = block(
            //if (f == null) { f = Pattern.compile(...) }
            condition(isNull(loadField(f)))(
              setField(f, invokeStatic(method[regex.Pattern, regex.Pattern, String]("compile"), constant(name)))),
            invokeStatic(method[CypherBoolean, BooleanValue, TextValue, regex.Pattern]("regex"), cast[TextValue](e.ir),
                         loadField(f)))

          IntermediateExpression(ops, e.fields :+ f, e.variables, Set(not(instanceOf[TextValue](e.ir))))
        }

      case _ =>
        for {l <- internalCompileExpression(lhs, currentContext)
             r <- internalCompileExpression(rhs, currentContext)
        } yield {
          IntermediateExpression(
            invokeStatic(method[CypherBoolean, BooleanValue, TextValue, TextValue]("regex"),
                         cast[TextValue](l.ir),
                         invokeStatic(method[CypherFunctions, TextValue, AnyValue]("asTextValue"), r.ir)),
            l.fields ++ r.fields, l.variables ++ r.variables, r.nullCheck + not(instanceOf[TextValue](l.ir)))
        }
    }

    case StartsWith(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {
        IntermediateExpression(
          invokeStatic(method[Values, BooleanValue, Boolean]("booleanValue"),
                       invoke(cast[TextValue](l.ir), method[TextValue, Boolean, TextValue]("startsWith"),
                              cast[TextValue](r.ir))),
          l.fields ++ r.fields, l.variables ++ r.variables,
          Set(not(instanceOf[TextValue](l.ir)), not(instanceOf[TextValue](r.ir))))
      }

    case EndsWith(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {
        IntermediateExpression(
          invokeStatic(method[Values, BooleanValue, Boolean]("booleanValue"),
                       invoke(cast[TextValue](l.ir), method[TextValue, Boolean, TextValue]("endsWith"),
                              cast[TextValue](r.ir))),
          l.fields ++ r.fields, l.variables ++ r.variables,
          Set(not(instanceOf[TextValue](l.ir)), not(instanceOf[TextValue](r.ir))))
      }

    case Contains(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {
        IntermediateExpression(
          invokeStatic(method[Values, BooleanValue, Boolean]("booleanValue"),
                       invoke(cast[TextValue](l.ir), method[TextValue, Boolean, TextValue]("contains"),
                              cast[TextValue](r.ir))),
          l.fields ++ r.fields, l.variables ++ r.variables,
          Set(not(instanceOf[TextValue](l.ir)), not(instanceOf[TextValue](r.ir))))
      }

    case expressions.IsNull(test) =>
      for (e <- internalCompileExpression(test, currentContext)) yield {
        IntermediateExpression(
          ternary(equal(e.ir, noValue), truthValue, falseValue), e.fields, e.variables, Set.empty)
      }

    case expressions.IsNotNull(test) =>
      for (e <- internalCompileExpression(test, currentContext)) yield {
        IntermediateExpression(
          ternary(notEqual(e.ir, noValue), truthValue, falseValue), e.fields, e.variables, Set.empty)
      }

    case LessThan(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {

        val variableName = namer.nextVariableName()
        val local = variable[Value](variableName, noValue)
        val lazySet = oneTime(assign(variableName,
                                     nullCheck(l, r)(invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("lessThan"), l.ir, r.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, l.fields ++ r.fields, l.variables ++ r.variables :+ local, Set(nullChecks))
      }

    case LessThanOrEqual(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {
        val variableName = namer.nextVariableName()
        val local = variable[Value](variableName, noValue)
        val lazySet = oneTime(assign(variableName,
                                     nullCheck(l, r)(invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("lessThanOrEqual"), l.ir, r.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, l.fields ++ r.fields, l.variables ++ r.variables :+ local, Set(nullChecks))
      }

    case GreaterThan(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {
        val variableName = namer.nextVariableName()
        val local = variable[Value](variableName, noValue)
        val lazySet = oneTime(assign(variableName,
                                     nullCheck(l, r)(invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("greaterThan"), l.ir, r.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, l.fields ++ r.fields, l.variables ++ r.variables :+ local, Set(nullChecks))
      }

    case GreaterThanOrEqual(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)} yield {
        val variableName = namer.nextVariableName()
        val local = variable[Value](variableName, noValue)
        val lazySet = oneTime(assign(variableName,
                                     nullCheck(l, r)(invokeStatic(method[CypherBoolean, Value, AnyValue, AnyValue]("greaterThanOrEqual"), l.ir, r.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, l.fields ++ r.fields, l.variables ++ r.variables :+ local, Set(nullChecks))
      }

    // misc
    case CoerceTo(expr, typ) =>
      for (e <- internalCompileExpression(expr, currentContext)) yield {
        typ match {
          case CTAny => e
          case CTString =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, TextValue, AnyValue]("asTextValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTNode =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, NodeValue, AnyValue]("asNodeValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTRelationship =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, RelationshipValue, AnyValue]("asRelationshipValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTPath =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, PathValue, AnyValue]("asPathValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTInteger =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, IntegralValue, AnyValue]("asIntegralValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTFloat =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, FloatingPointValue, AnyValue]("asFloatingPointValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTMap =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, MapValue, AnyValue, DbAccess]("asMapValue"), e.ir, DB_ACCESS),
              e.fields, e.variables, e.nullCheck)

          case l: ListType =>
            val typ = asNeoType(l.innerType)

            IntermediateExpression(
              invokeStatic(method[CypherCoercions, ListValue, AnyValue, AnyType, DbAccess]("asList"), e.ir, typ,
                           DB_ACCESS),
              e.fields, e.variables, e.nullCheck)

          case CTBoolean =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, BooleanValue, AnyValue]("asBooleanValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTNumber =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, NumberValue, AnyValue]("asNumberValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTPoint =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, PointValue, AnyValue]("asPointValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTGeometry =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, PointValue, AnyValue]("asPointValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTDate =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, DateValue, AnyValue]("asDateValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTLocalTime =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, LocalTimeValue, AnyValue]("asLocalTimeValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTTime =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, TimeValue, AnyValue]("asTimeValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTLocalDateTime =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, LocalDateTimeValue, AnyValue]("asLocalDateTimeValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTDateTime =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, DateTimeValue, AnyValue]("asDateTimeValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case CTDuration =>
            IntermediateExpression(
              invokeStatic(method[CypherCoercions, DurationValue, AnyValue]("asDurationValue"), e.ir),
              e.fields, e.variables, e.nullCheck)
          case _ => throw new CypherTypeException(s"Can't coerce to $typ")
        }
      }

    //data access
    case ContainerIndex(container, index) =>
      for {c <- internalCompileExpression(container, currentContext)
           idx <- internalCompileExpression(index, currentContext)
      } yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(c, idx)( nullCheck(c, idx)(invokeStatic(method[CypherFunctions, AnyValue, AnyValue, AnyValue, DbAccess]("containerIndex"),
                                                                                 c.ir, idx.ir, DB_ACCESS)))))
        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(
          ops, c.fields ++ idx.fields, c.variables ++ idx.variables :+ local, Set(nullChecks))
      }

    case Parameter(name, _) => //TODO parameters that are autogenerated from literals should have nullable = false
      //parameters are global in the sense that we only need one variable for the parameter
      val parameterVariable = namer.parameterName(name)

      val local = variable[AnyValue](parameterVariable,
                                     invoke(load("params"), method[MapValue, AnyValue, String]("get"),
                                            constant(name)))
      Some(IntermediateExpression(load(parameterVariable), Seq.empty, Seq(local),
                                  Set(equal(load(parameterVariable), noValue))))

    case Property(targetExpression, PropertyKeyName(key)) =>
      for (map <- internalCompileExpression(targetExpression, currentContext)) yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName, ternary(map.nullCheck.reduceLeft((acc,current) => or(acc, current)), noValue,
                                       invokeStatic(method[CypherFunctions, AnyValue, String, AnyValue, DbAccess]("propertyGet"), constant(key), map.ir, DB_ACCESS))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))
        IntermediateExpression(ops, Seq.empty, map.variables :+ local, Set(nullChecks))
      }

    case NodeProperty(offset, token, _) =>
      val variableName = namer.nextVariableName()
      val local = variable[Value](variableName, noValue)
      val lazySet = oneTime(assign(variableName, invoke(DB_ACCESS, method[DbAccess, Value, Long, Int]("nodeProperty"),
                                    getLongAt(offset, currentContext), constant(token))))

      val ops = block(lazySet, load(variableName))
      val nullChecks = block(lazySet, equal(load(variableName), noValue))
      Some(IntermediateExpression(ops, Seq.empty, Seq(local), Set(nullChecks)))

    case NodePropertyLate(offset, key, _) =>
      val f = field[Int](namer.nextVariableName(), constant(-1))
      val variableName = namer.nextVariableName()
      val local = variable[Value](variableName, noValue)
      val lazySet = oneTime(assign(variableName,  block(
          condition(equal(loadField(f), constant(-1)))(
            setField(f, invoke(DB_ACCESS, method[DbAccess, Int, String]("propertyKey"), constant(key)))),
          invoke(DB_ACCESS, method[DbAccess, Value, Long, Int]("nodeProperty"),
                 getLongAt(offset, currentContext), loadField(f)))))

      val ops = block(lazySet, load(variableName))
      val nullChecks = block(lazySet, equal(load(variableName), noValue))
      Some(IntermediateExpression(ops, Seq(f), Seq(local), Set(nullChecks)))

    case NodePropertyExists(offset, token, _) =>
      Some(
        IntermediateExpression(
          ternary(
          invoke(DB_ACCESS, method[DbAccess, Boolean, Long, Int]("nodeHasProperty"),
                 getLongAt(offset, currentContext), constant(token)), truthValue, falseValue), Seq.empty, Seq.empty, Set.empty))

    case NodePropertyExistsLate(offset, key, _) =>
      val f = field[Int](namer.nextVariableName(), constant(-1))
      Some(IntermediateExpression(
        block(
          condition(equal(loadField(f), constant(-1)))(
            setField(f, invoke(DB_ACCESS, method[DbAccess, Int, String]("propertyKey"), constant(key)))),
        ternary(
        invoke(DB_ACCESS, method[DbAccess, Boolean, Long, Int]("nodeHasProperty"),
               getLongAt(offset, currentContext), loadField(f)), truthValue, falseValue)), Seq(f), Seq.empty, Set.empty))

    case RelationshipProperty(offset, token, _) =>
      val variableName = namer.nextVariableName()
      val local = variable[Value](variableName, noValue)
      val lazySet = oneTime(assign(variableName, invoke(DB_ACCESS, method[DbAccess, Value, Long, Int]("relationshipProperty"),
                                    getLongAt(offset, currentContext), constant(token))))

      val ops = block(lazySet, load(variableName))
      val nullChecks = block(lazySet, equal(load(variableName), noValue))
      Some(IntermediateExpression(ops, Seq.empty, Seq(local), Set(nullChecks)))

    case RelationshipPropertyLate(offset, key, _) =>
      val f = field[Int](namer.nextVariableName(), constant(-1))
      val variableName = namer.nextVariableName()
      val local = variable[Value](variableName, noValue)
      val lazySet = oneTime(assign(variableName, block(
          condition(equal(loadField(f), constant(-1)))(
            setField(f, invoke(DB_ACCESS, method[DbAccess, Int, String]("propertyKey"), constant(key)))),
          invoke(DB_ACCESS, method[DbAccess, Value, Long, Int]("relationshipProperty"),
                 getLongAt(offset, currentContext), loadField(f)))))

      val ops = block(lazySet, load(variableName))
      val nullChecks = block(lazySet, equal(load(variableName), noValue))

      Some(IntermediateExpression(ops, Seq(f), Seq(local), Set(nullChecks)))

    case RelationshipPropertyExists(offset, token, _) =>
      Some(IntermediateExpression(
        ternary(
          invoke(DB_ACCESS, method[DbAccess, Boolean, Long, Int]("relationshipHasProperty"),
                 getLongAt(offset, currentContext), constant(token)),
          truthValue,
          falseValue), Seq.empty, Seq.empty, Set.empty)
      )

    case RelationshipPropertyExistsLate(offset, key, _) =>
      val f = field[Int](namer.nextVariableName(), constant(-1))
      Some(IntermediateExpression(
        block(
          condition(equal(loadField(f), constant(-1)))(
            setField(f, invoke(DB_ACCESS, method[DbAccess, Int, String]("propertyKey"), constant(key)))),
        ternary(
        invoke(DB_ACCESS, method[DbAccess, Boolean, Long, Int]("relationshipHasProperty"),
               getLongAt(offset, currentContext), loadField(f)),
        truthValue,
        falseValue)), Seq(f), Seq.empty, Set.empty))

    case HasLabels(nodeExpression, labels)  if labels.nonEmpty =>
      for (node <- internalCompileExpression(nodeExpression, currentContext)) yield {
        val tokensAndNames = labels.map(l => field[Int](s"label${l.name}", constant(-1)) -> l.name)

        val init = tokensAndNames.map {
          case (token, labelName) =>
            condition(equal(loadField(token), constant(-1)))(setField(
              token, invoke(DB_ACCESS, method[DbAccess, Int, String]("nodeLabel"), constant(labelName))))
        }

        val predicate: IntermediateRepresentation = ternary(tokensAndNames.map { token =>
          invokeStatic(method[CypherFunctions, Boolean, AnyValue, Int, DbAccess]("hasLabel"),
                       node.ir, loadField(token._1), DB_ACCESS)
        }.reduceLeft(and), truthValue, falseValue)

        IntermediateExpression(block(init :+ predicate:_*), node.fields ++ tokensAndNames.map(_._1), node.variables, node.nullCheck)
      }

    case NodeFromSlot(offset, name) =>
      Some(IntermediateExpression(
        invoke(DB_ACCESS, method[DbAccess, NodeValue, Long]("nodeById"), getLongAt(offset, currentContext)),
        Seq.empty, Seq.empty,
        slots.get(name).filter(_.nullable).map(slot => equal(getLongAt(slot.offset, currentContext), constant(-1L))).toSet))

    case RelationshipFromSlot(offset, name) =>
      Some(IntermediateExpression(
        invoke(DB_ACCESS, method[DbAccess, RelationshipValue, Long]("relationshipById"),
               getLongAt(offset, currentContext)), Seq.empty, Seq.empty,
        slots.get(name).filter(_.nullable).map(slot => equal(getLongAt(slot.offset, currentContext), constant(-1L))).toSet))

    case GetDegreePrimitive(offset, typ, dir) =>
      val methodName = dir match {
        case SemanticDirection.OUTGOING => "nodeGetOutgoingDegree"
        case SemanticDirection.INCOMING => "nodeGetIncomingDegree"
        case SemanticDirection.BOTH => "nodeGetTotalDegree"
      }
      typ match {
        case None =>
          Some(
            IntermediateExpression(
              invokeStatic(method[Values, IntValue, Int]("intValue"),
                           invoke(DB_ACCESS, method[DbAccess, Int, Long](methodName), getLongAt(offset, currentContext))),
              Seq.empty, Seq.empty, Set.empty))

        case Some(t) =>
          val f = field[Int](namer.nextVariableName(), constant(-1))
          Some(
            IntermediateExpression(
              block(
                condition(equal(loadField(f), constant(-1)))(
                  setField(f, invoke(DB_ACCESS, method[DbAccess, Int, String]("relationshipType"), constant(t)))),
                invokeStatic(method[Values, IntValue, Int]("intValue"),
                           invoke(DB_ACCESS, method[DbAccess, Int, Long, Int](methodName),
                                  getLongAt(offset, currentContext), loadField(f)))), Seq(f), Seq.empty, Set.empty))
      }

    //slotted operations
    case ReferenceFromSlot(offset, name) =>
      val nullCheck = slots.get(name).filter(_.nullable).map(_ => equal(getRefAt(offset, currentContext), noValue)).toSet
      val loadRef =  getRefAt(offset, currentContext)
      Some(IntermediateExpression(loadRef, Seq.empty, Seq.empty, nullCheck))

    case IdFromSlot(offset) =>
      val nameOfSlot = slots.nameOfLongSlot(offset)
      val nullCheck = nameOfSlot.filter(n => slots(n).nullable).map(_ => equal(getLongAt(offset, currentContext), constant(-1L))).toSet
      val value = invokeStatic(method[Values, LongValue, Long]("longValue"), getLongAt(offset, currentContext))

      Some(IntermediateExpression(value, Seq.empty, Seq.empty, nullCheck))

    case PrimitiveEquals(lhs, rhs) =>
      for {l <- internalCompileExpression(lhs, currentContext)
           r <- internalCompileExpression(rhs, currentContext)
      } yield
        IntermediateExpression(
          ternary(invoke(l.ir, method[AnyValue, Boolean, AnyRef]("equals"), r.ir), truthValue, falseValue),
          l.fields ++ r.fields, l.variables ++ r.variables, Set.empty)

    case NullCheck(offset, inner) =>
      internalCompileExpression(inner, currentContext).map(i =>
        IntermediateExpression(
          ternary(equal(getLongAt(offset, currentContext), constant(-1L)), noValue, i.ir),
          i.fields, i.variables, Set(equal(getLongAt(offset, currentContext), constant(-1L))))
      )

    case NullCheckVariable(offset, inner) =>
      internalCompileExpression(inner, currentContext).map(i => IntermediateExpression(ternary(equal(getLongAt(offset, currentContext), constant(-1L)), noValue, i.ir),
                                                                                       i.fields, i.variables, Set(equal(getLongAt(offset, currentContext), constant(-1L)))))

    case NullCheckProperty(offset, inner) =>
      internalCompileExpression(inner, currentContext).map(i =>
                           IntermediateExpression(ternary(equal(getLongAt(offset, currentContext), constant(-1L)), noValue, i.ir),
                                                  i.fields, i.variables, Set(equal(getLongAt(offset, currentContext), constant(-1L)))))

    case IsPrimitiveNull(offset) =>
      Some(IntermediateExpression(ternary(equal(getLongAt(offset, currentContext), constant(-1L)), truthValue, falseValue),
                                  Seq.empty, Seq.empty, Set.empty))

    case _ => None
  }

  def compileFunction(c: FunctionInvocation, currentContext: Option[IntermediateRepresentation]): Option[IntermediateExpression] = c.function match {
    case functions.Acos =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("acos"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Cos =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("cos"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Cot =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("cot"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Asin =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("asin"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Haversin =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("haversin"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Sin =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("sin"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Atan =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("atan"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Atan2 =>
      for {y <- internalCompileExpression(c.args(0), currentContext)
           x <- internalCompileExpression(c.args(1), currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, DoubleValue, AnyValue, AnyValue]("atan2"), y.ir, x.ir),
          y.fields ++ x.fields, y.variables ++ x.variables, y.nullCheck ++ x.nullCheck)
      }
    case functions.Tan =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("tan"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Round =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("round"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Rand =>
      Some(IntermediateExpression(invokeStatic(method[CypherFunctions, DoubleValue]("rand")),
                                  Seq.empty, Seq.empty, Set.empty))
    case functions.Abs =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, NumberValue, AnyValue]("abs"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Ceil =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("ceil"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Floor =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("floor"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Degrees =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("toDegrees"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Exp =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("exp"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Log =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("log"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Log10 =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("log10"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Radians =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("toRadians"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Sign =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, LongValue, AnyValue]("signum"), in.ir), in.fields, in.variables, in.nullCheck))
    case functions.Sqrt =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, DoubleValue, AnyValue]("sqrt"), in.ir), in.fields, in.variables, in.nullCheck))

    case functions.Range  if c.args.length == 2 =>
      for {start <- internalCompileExpression(c.args(0), currentContext)
           end <- internalCompileExpression(c.args(1), currentContext)
      } yield IntermediateExpression(invokeStatic(method[CypherFunctions, ListValue, AnyValue, AnyValue]("range"),
                                                  nullCheck(start)(start.ir), nullCheck(end)(end.ir)),
                                     start.fields ++ end.fields,
                                     start.variables ++ end.variables, Set.empty)

    case functions.Range  if c.args.length == 3 =>
      for {start <- internalCompileExpression(c.args(0), currentContext)
           end <- internalCompileExpression(c.args(1), currentContext)
           step <- internalCompileExpression(c.args(2), currentContext)
      } yield IntermediateExpression(invokeStatic(method[CypherFunctions, ListValue, AnyValue, AnyValue, AnyValue]("range"),
                                                  nullCheck(start)(start.ir), nullCheck(end)(end.ir), nullCheck(step)(step.ir)),
                                     start.fields ++ end.fields ++ step.fields,
                                     start.variables ++ end.variables ++ step.variables, Set.empty)

    case functions.Pi => Some(IntermediateExpression(getStatic[Values, DoubleValue]("PI"), Seq.empty, Seq.empty, Set.empty))
    case functions.E => Some(IntermediateExpression(getStatic[Values, DoubleValue]("E"), Seq.empty, Seq.empty, Set.empty))

    case functions.Coalesce =>
      val args = c.args.flatMap(internalCompileExpression(_, currentContext))
      if (args.size < c.args.size) None
      else {
        val tempVariable = namer.nextVariableName()
        val local = variable[AnyValue](tempVariable, noValue)
        // This loop will generate:
        // AnyValue tempVariable = arg0;
        //if (tempVariable == NO_VALUE) {
        //  tempVariable = arg1;
        //  if ( tempVariable == NO_VALUE) {
        //    tempVariable = arg2;
        //  ...
        //}
        def loop(expressions: List[IntermediateExpression]): IntermediateRepresentation = expressions match {
          case Nil => throw new InternalException("we should never exhaust this loop")
          case expression :: Nil => assign(tempVariable, nullCheck(expression)(expression.ir))
          case expression :: tail =>
            //tempVariable = hd; if (tempVariable == NO_VALUE){[continue with tail]}
            if (expression.nullCheck.nonEmpty) block(assign(tempVariable, nullCheck(expression)(expression.ir)),
                                                     condition(expression.nullCheck.reduceLeft((acc,current) => or(acc, current)))(loop(tail)))
            // WHOAH[Keanu Reeves voice] if not nullable we don't even need to generate code for the coming expressions,
            else assign(tempVariable, expression.ir)
        }
        val repr = block(loop(args.toList),
                          load(tempVariable))

        Some(IntermediateExpression(repr, args.foldLeft(Seq.empty[Field])((a,b) => a ++ b.fields),
                                    args.foldLeft(Seq.empty[LocalVariable])((a,b) => a ++ b.variables) :+ local,
                                    Set(equal(load(tempVariable), noValue))))
      }

    case functions.Distance =>
      for {p1 <- internalCompileExpression(c.args(0), currentContext)
           p2 <- internalCompileExpression(c.args(1), currentContext)
      } yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName,
                                       invokeStatic(method[CypherFunctions, Value, AnyValue, AnyValue]("distance"), p1.ir, p2.ir))
        IntermediateExpression(
          load(variableName),
          p1.fields ++ p2.fields,  p1.variables ++ p2.variables :+ local, Set(equal(load(variableName), noValue)))
      }

    case functions.StartNode =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, NodeValue, AnyValue, DbAccess]("startNode"), in.ir, DB_ACCESS), in.fields, in.variables, in.nullCheck))

    case functions.EndNode =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, NodeValue, AnyValue, DbAccess]("endNode"), in.ir,
                                      DB_ACCESS), in.fields, in.variables, in.nullCheck))

    case functions.Nodes =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, ListValue, AnyValue]("nodes"), in.ir), in.fields, in.variables, in.nullCheck))

    case functions.Relationships =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, ListValue, AnyValue]("relationships"), in.ir), in.fields, in.variables, in.nullCheck))

    case functions.Exists =>
      c.arguments.head match {
        case property: Property =>
          internalCompileExpression(property.map, currentContext).map(in => IntermediateExpression(
              invokeStatic(method[CypherFunctions, BooleanValue, String, AnyValue, DbAccess]("propertyExists"),
                           constant(property.propertyKey.name),
                           in.ir, DB_ACCESS ), in.fields, in.variables, in.nullCheck))
        case _: PatternExpression => None//TODO
        case _: NestedPipeExpression => None//TODO?
        case _: NestedPlanExpression => None//TODO
        case _ => None
      }

    case functions.Head =>
      internalCompileExpression(c.args.head, currentContext).map(in => {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName,  nullCheck(in)(invokeStatic(method[CypherFunctions, AnyValue, AnyValue]("head"), in.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))

        IntermediateExpression(ops, in.fields, in.variables :+ local, Set(nullChecks))
      })

    case functions.Id =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, LongValue, AnyValue]("id"), in.ir),
        in.fields, in.variables, in.nullCheck))

    case functions.Labels =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
       invokeStatic(method[CypherFunctions, ListValue, AnyValue, DbAccess]("labels"), in.ir, DB_ACCESS),
       in.fields, in.variables, in.nullCheck))

    case functions.Type =>
      internalCompileExpression(c.args.head, currentContext).map(in => IntermediateExpression(
        invokeStatic(method[CypherFunctions, TextValue, AnyValue]("type"), in.ir),
         in.fields, in.variables, in.nullCheck))

    case functions.Last =>
      internalCompileExpression(c.args.head, currentContext).map(in => {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(in)(invokeStatic(method[CypherFunctions, AnyValue, AnyValue]("last"), in.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))

        IntermediateExpression(ops, in.fields, in.variables :+ local, Set(nullChecks))
      })

    case functions.Left =>
      for {in <- internalCompileExpression(c.args(0), currentContext)
           endPos <- internalCompileExpression(c.args(1), currentContext)
      } yield {
        IntermediateExpression(
         invokeStatic(method[CypherFunctions, TextValue, AnyValue, AnyValue]("left"), in.ir, endPos.ir),
         in.fields ++ endPos.fields, in.variables ++ endPos.variables, in.nullCheck)
      }

    case functions.LTrim =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, TextValue, AnyValue]("ltrim"), in.ir),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.RTrim =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
         invokeStatic(method[CypherFunctions, TextValue, AnyValue]("rtrim"), in.ir),
         in.fields, in.variables, in.nullCheck)
      }

    case functions.Trim =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
         invokeStatic(method[CypherFunctions, TextValue, AnyValue]("trim"), in.ir),
         in.fields, in.variables, in.nullCheck)
      }

    case functions.Replace =>
      for {original <- internalCompileExpression(c.args(0), currentContext)
           search <- internalCompileExpression(c.args(1), currentContext)
           replaceWith <- internalCompileExpression(c.args(2), currentContext)
      } yield {
        IntermediateExpression(
            invokeStatic(method[CypherFunctions, TextValue, AnyValue, AnyValue, AnyValue]("replace"),
                         original.ir, search.ir, replaceWith.ir),
          original.fields ++ search.fields ++ replaceWith.fields, original.variables ++ search.variables ++ replaceWith.variables,
          original.nullCheck ++ search.nullCheck ++ replaceWith.nullCheck)
      }

    case functions.Reverse =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, AnyValue, AnyValue]("reverse"), in.ir), in.fields, in.variables, in.nullCheck)
      }

    case functions.Right =>
      for {in <- internalCompileExpression(c.args(0), currentContext)
           len <- internalCompileExpression(c.args(1), currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, TextValue, AnyValue, AnyValue]("right"), in.ir, len.ir),
          in.fields ++ len.fields, in.variables ++ len.variables, in.nullCheck)
      }

    case functions.Split =>
      for {original <- internalCompileExpression(c.args(0), currentContext)
           sep <- internalCompileExpression(c.args(1), currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, ListValue, AnyValue, AnyValue]("split"), original.ir, sep.ir),
          original.fields ++ sep.fields, original.variables ++ sep.variables,
        original.nullCheck ++ sep.nullCheck)
      }

    case functions.Substring if c.args.size == 2 =>
      for {original <- internalCompileExpression(c.args(0), currentContext)
           start <- internalCompileExpression(c.args(1), currentContext)
      } yield {
        IntermediateExpression(
         invokeStatic(method[CypherFunctions, TextValue, AnyValue, AnyValue]("substring"), original.ir, start.ir),
          original.fields ++ start.fields, original.variables ++ start.variables, original.nullCheck)
      }

    case functions.Substring  =>
      for {original <- internalCompileExpression(c.args(0), currentContext)
           start <- internalCompileExpression(c.args(1), currentContext)
           len <- internalCompileExpression(c.args(2), currentContext)
      } yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, TextValue, AnyValue, AnyValue, AnyValue]("substring"),
                                              original.ir, start.ir, len.ir),
          original.fields ++ start.fields ++ len.fields,
          original.variables ++ start.variables ++ len.variables, original.nullCheck)
      }

    case functions.ToLower =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, TextValue, AnyValue]("toLower"), in.ir),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.ToUpper =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, TextValue, AnyValue]("toUpper"), in.ir),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.Point =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, Value, AnyValue, DbAccess]("point"), in.ir, DB_ACCESS),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.Keys =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, ListValue, AnyValue, DbAccess]("keys"), in.ir, DB_ACCESS),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.Size =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, IntegralValue, AnyValue]("size"), in.ir),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.Length =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, IntegralValue, AnyValue]("length"), in.ir),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.Tail =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(
          invokeStatic(method[CypherFunctions, ListValue, AnyValue]("tail"), in.ir),
          in.fields, in.variables, in.nullCheck)
      }

    case functions.ToBoolean =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName,  nullCheck(in)(invokeStatic(method[CypherFunctions, Value, AnyValue]("toBoolean"), in.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))

        IntermediateExpression(ops, in.fields, in.variables :+ local, Set(nullChecks))
      }

    case functions.ToFloat =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(in)(invokeStatic(method[CypherFunctions, Value, AnyValue]("toFloat"), in.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))

        IntermediateExpression(ops, in.fields, in.variables :+ local, Set(nullChecks))
      }

    case functions.ToInteger =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(in)(invokeStatic(method[CypherFunctions, Value, AnyValue]("toInteger"), in.ir))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))

        IntermediateExpression(ops, in.fields, in.variables :+ local, Set(nullChecks))
      }

    case functions.ToString =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        IntermediateExpression(invokeStatic(method[CypherFunctions, TextValue, AnyValue]("toString"), in.ir), in.fields, in.variables, in.nullCheck)
      }

    case functions.Properties =>
      for (in <- internalCompileExpression(c.args.head, currentContext)) yield {
        val variableName = namer.nextVariableName()
        val local = variable[AnyValue](variableName, noValue)
        val lazySet = oneTime(assign(variableName, nullCheck(in)(invokeStatic(method[CypherFunctions, MapValue, AnyValue, DbAccess]("properties"), in.ir, DB_ACCESS))))

        val ops = block(lazySet, load(variableName))
        val nullChecks = block(lazySet, equal(load(variableName), noValue))

        IntermediateExpression(ops, in.fields, in.variables :+ local, Set(nullChecks))
      }

    case p =>
      None
  }

  private def contextSet(key: String, context: IntermediateRepresentation, value: IntermediateRepresentation): IntermediateRepresentation = {
    slots.get(key) match {
      case Some(LongSlot(offset, _, _)) =>
        setLongAt(offset, Some(context), value)
      case Some(RefSlot(offset, _, _)) =>
        setRefAt(offset, Some(context), value)
      case None =>
        invokeSideEffect(context, method[ExecutionContext, Unit, String, AnyValue]("set"), constant(key), value)
    }
  }

  private def getLongAt(offset: Int, currentContext: Option[IntermediateRepresentation]): IntermediateRepresentation =
    invoke(loadContext(currentContext), method[ExecutionContext, Long, Int]("getLongAt"),
           constant(offset))

  private def getRefAt(offset: Int, currentContext: Option[IntermediateRepresentation]): IntermediateRepresentation =
    invoke(loadContext(currentContext), method[ExecutionContext, AnyValue, Int]("getRefAt"),
           constant(offset))

  private def setRefAt(offset: Int, currentContext: Option[IntermediateRepresentation], value: IntermediateRepresentation): IntermediateRepresentation =
    invokeSideEffect(loadContext(currentContext), method[ExecutionContext, Unit, Int, AnyValue]("setRefAt"),
                     constant(offset), value)

  private def setLongAt(offset: Int, currentContext: Option[IntermediateRepresentation], value: IntermediateRepresentation): IntermediateRepresentation =
    invokeSideEffect(loadContext(currentContext), method[ExecutionContext, Unit, Int, AnyValue]("setLongAt"),
                     constant(offset), value)

  private def loadContext(currentContext: Option[IntermediateRepresentation]) = currentContext.getOrElse(load("context"))
  private def nullCheck(expressions: IntermediateExpression*)(onNotNull: IntermediateRepresentation): IntermediateRepresentation = {
    val checks = expressions.foldLeft(Set.empty[IntermediateRepresentation])((acc, current) => acc ++ current.nullCheck)
    if (checks.nonEmpty) ternary(checks.reduceLeft(or), noValue, onNotNull)
    else onNotNull
  }

  private def coerceToPredicate(e: IntermediateExpression) = IntermediateExpression(
    invokeStatic(method[CypherBoolean, Value, AnyValue]("coerceToBoolean"), e.ir), e.fields, e.variables, e.nullCheck)

  /**
    * Ok AND and ANDS are complicated.  At the core we try to find a single `FALSE` if we find one there is no need to look
    * at more predicates. If it doesn't find a `FALSE` it will either return `NULL` if any of the predicates has evaluated
    * to `NULL` or `TRUE` if all predicates evaluated to `TRUE`.
    *
    * For example:
    * - AND(FALSE, NULL) -> FALSE
    * - AND(NULL, FALSE) -> FALSE
    * - AND(TRUE, NULL) -> NULL
    * - AND(NULL, TRUE) -> NULL
    *
    * Errors are an extra complication here, errors are treated as `NULL` except that we will throw an error instead of
    * returning `NULL`, so for example:
    *
    * - AND(FALSE, 42) -> FALSE
    * - AND(42, FALSE) -> FALSE
    * - AND(TRUE, 42) -> throw type error
    * - AND(42, TRUE) -> throw type error
    *
    * The generated code below will look something like;
    *
    * RuntimeException error = null;
    * boolean seenNull = false;
    * Value returnValue = null;
    * try
    * {
    *   returnValue = [expressions.head];
    * }
    * catch( RuntimeException e)
    * {
    *   error = e;
    * }
    * seenNull = returnValue == NO_VALUE;
    * if ( returnValue != FALSE )
    * {
    *    try
    *    {
    *      returnValue = expressions.tail.head;
    *    }
    *    catch( RuntimeException e)
    *    {
    *      error = e;
    *    }
    *    seenValue = returnValue == FALSE ? false : (seenValue ? true : returnValue == NO_VALUE);
    *    if ( returnValue != FALSE )
    *    {
    *       try
    *       {
    *         returnValue = expressions.tail.tail.head;
    *       }
    *       catch( RuntimeException e)
    *       {
    *         error = e;
    *       }
    *       seenValue = returnValue == FALSE ? false : (seenValue ? true : returnValue == NO_VALUE);
    *       ...[continue unroll until we are at the end of expressions]
    *     }
    * }
    * if ( error != null && returnValue != FALSE )
    * {
    *   throw error;
    * }
    * return seenNull ? NO_VALUE : returnValue;
    */
  private def generateAnds(expressions: List[IntermediateExpression]) =
    generateCompositeBoolean(expressions, falseValue)

  /**
    * Ok OR and ORS are also complicated.  At the core we try to find a single `TRUE` if we find one there is no need to look
    * at more predicates. If it doesn't find a `TRUE` it will either return `NULL` if any of the predicates has evaluated
    * to `NULL` or `FALSE` if all predicates evaluated to `FALSE`.
    *
    * For example:
    * - OR(FALSE, NULL) -> NULL
    * - OR(NULL, FALSE) -> NULL
    * - OR(TRUE, NULL) -> TRUE
    * - OR(NULL, TRUE) -> TRUE
    *
    * Errors are an extra complication here, errors are treated as `NULL` except that we will throw an error instead of
    * returning `NULL`, so for example:
    *
    * - OR(TRUE, 42) -> TRUE
    * - OR(42, TRUE) -> TRUE
    * - OR(FALSE, 42) -> throw type error
    * - OR(42, FALSE) -> throw type error
    *
    * The generated code below will look something like;
    *
    * RuntimeException error = null;
    * boolean seenNull = false;
    * Value returnValue = null;
    * try
    * {
    *   returnValue = [expressions.head];
    * }
    * catch( RuntimeException e)
    * {
    *   error = e;
    * }
    * seenNull = returnValue == NO_VALUE;
    * if ( returnValue != TRUE )
    * {
    *    try
    *    {
    *      returnValue = expressions.tail.head;
    *    }
    *    catch( RuntimeException e)
    *    {
    *      error = e;
    *    }
    *    seenValue = returnValue == TRUE ? false : (seenValue ? true : returnValue == NO_VALUE);
    *    if ( returnValue != TRUE )
    *    {
    *       try
    *       {
    *         returnValue = expressions.tail.tail.head;
    *       }
    *       catch( RuntimeException e)
    *       {
    *         error = e;
    *       }
    *       seenValue = returnValue == TRUE ? false : (seenValue ? true : returnValue == NO_VALUE);
    *       ...[continue unroll until we are at the end of expressions]
    *     }
    * }
    * if ( error != null && returnValue != TRUE )
    * {
    *   throw error;
    * }
    * return seenNull ? NO_VALUE : returnValue;
    */
  private def generateOrs(expressions: List[IntermediateExpression]): IntermediateExpression =
    generateCompositeBoolean(expressions, truthValue)

  private def generateCompositeBoolean(expressions: List[IntermediateExpression], breakValue: IntermediateRepresentation): IntermediateExpression = {
    //do we need to do nullchecks
    val nullable = expressions.exists(_.nullCheck.nonEmpty)

    //these are the temp variables used
    val returnValue = namer.nextVariableName()
    val local = variable[AnyValue](returnValue, constant(null))
    val seenNull = namer.nextVariableName()
    val error = namer.nextVariableName()
    //this is setting up  a `if (returnValue != breakValue)`
    val ifNotBreakValue: IntermediateRepresentation => IntermediateRepresentation = condition(notEqual(load(returnValue), breakValue))
    //this is the inner block of the condition
    val inner = (e: IntermediateExpression) => {
      val exceptionName = namer.nextVariableName()
      val loadValue = tryCatch[RuntimeException](exceptionName)(assign(returnValue, nullCheck(e)(invokeStatic(ASSERT_PREDICATE, e.ir))))(
        assign(error, load(exceptionName)))

        if (nullable) {
          Seq(loadValue,
              assign(seenNull,
                     //returnValue == breakValue ? false :
                     ternary(equal(load(returnValue), breakValue), constant(false),
                             //seenNull ? true : (returnValue == NO_VALUE)
                             ternary(load(seenNull), constant(true), equal(load(returnValue), noValue)))))
        } else Seq(loadValue)
      }


    //this loop generates the nested expression:
    //if (returnValue != breakValue) {
    //  try {
    //    returnValue = ...;
    //  } catch ( RuntimeException e) { error = e}
    //  ...
    //  if (returnValue != breakValue ) {
    //    try {
    //        returnValue = ...;
    //    } catch ( RuntimeException e) { error = e}
    //    ...
    def loop(e: List[IntermediateExpression]): IntermediateRepresentation = e match {
      case Nil => throw new InternalException("we should never get here")
      case a :: Nil => ifNotBreakValue(block(inner(a):_*))
      case hd::tl => ifNotBreakValue(block(inner(hd) :+ loop(tl):_*))
    }

    val firstExpression = expressions.head
    val nullChecks = if (nullable) Seq(declare[Boolean](seenNull), assign(seenNull, constant(false))) else Seq.empty
    val nullCheckAssign = if (firstExpression.nullCheck.nonEmpty) Seq(assign(seenNull, equal(load(returnValue), noValue))) else Seq.empty
    val exceptionName = namer.nextVariableName()
    val ir =
      block(
        //set up all temp variables
        nullChecks ++ Seq(
          declare[RuntimeException](error),
          assign(error, constant(null)),
          //assign returnValue to head of expressions
          tryCatch[RuntimeException](exceptionName)(
            assign(returnValue, nullCheck(firstExpression)(invokeStatic(ASSERT_PREDICATE, firstExpression.ir))))(
            assign(error, load(exceptionName)))) ++ nullCheckAssign ++ Seq(
          //generated unrolls tail of expression
          loop(expressions.tail),
          //checks if there was an error and that we never evaluated to breakValue, if so throw
          condition(and(notEqual(load(error), constant(null)), notEqual(load(returnValue), breakValue)))(
            fail(load(error))),
          //otherwise check if we have seen a null which implicitly also mean we never seen a FALSE
          //if we seen a null we should return null otherwise we return whatever currently
          //stored in returnValue
          if (nullable) ternary(load(seenNull), noValue, load(returnValue)) else load(returnValue)): _*)
    IntermediateExpression(ir,
                           expressions.foldLeft(Seq.empty[Field])((a,b) => a ++ b.fields),
                           expressions.foldLeft(Seq.empty[LocalVariable])((a,b) => a ++ b.variables) :+ local,
                           Set(equal(load(returnValue), noValue)))
  }

  private def asNeoType(ct: CypherType): IntermediateRepresentation = ct match {
    case CTString => getStatic[Neo4jTypes, Neo4jTypes.TextType]("NTString")
    case CTInteger => getStatic[Neo4jTypes, Neo4jTypes.IntegerType]("NTInteger")
    case CTFloat => getStatic[Neo4jTypes, Neo4jTypes.FloatType]("NTFloat")
    case CTNumber =>  getStatic[Neo4jTypes, Neo4jTypes.NumberType]("NTNumber")
    case CTBoolean => getStatic[Neo4jTypes, Neo4jTypes.BooleanType]("NTBoolean")
    case l: ListType => invokeStatic(method[Neo4jTypes , Neo4jTypes.ListType, AnyType]("NTList"), asNeoType(l.innerType))
    case CTDateTime => getStatic[Neo4jTypes, Neo4jTypes.DateTimeType]("NTDateTime")
    case CTLocalDateTime => getStatic[Neo4jTypes, Neo4jTypes.LocalDateTimeType]("NTLocalDateTime")
    case CTDate => getStatic[Neo4jTypes, Neo4jTypes.DateType]("NTDate")
    case CTTime => getStatic[Neo4jTypes, Neo4jTypes.TimeType]("NTTime")
    case CTLocalTime => getStatic[Neo4jTypes, Neo4jTypes.LocalTimeType]("NTLocalTime")
    case CTDuration =>getStatic[Neo4jTypes, Neo4jTypes.DurationType]("NTDuration")
    case CTPoint => getStatic[Neo4jTypes, Neo4jTypes.PointType]("NTPoint")
    case CTNode =>getStatic[Neo4jTypes, Neo4jTypes.NodeType]("NTNode")
    case CTRelationship => getStatic[Neo4jTypes, Neo4jTypes.RelationshipType]("NTRelationship")
    case CTPath => getStatic[Neo4jTypes, Neo4jTypes.PathType]("NTPath")
    case CTGeometry => getStatic[Neo4jTypes, Neo4jTypes.GeometryType]("NTGeometry")
    case CTMap =>getStatic[Neo4jTypes, Neo4jTypes.MapType]("NTMap")
    case CTAny => getStatic[Neo4jTypes, Neo4jTypes.AnyType]("NTAny")
  }
}

object IntermediateCodeGeneration {
  private val ASSERT_PREDICATE = method[CompiledHelpers, Value, AnyValue]("assertBooleanOrNoValue")
  private val DB_ACCESS = load("dbAccess")
}
