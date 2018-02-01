/*
 * Copyright (c) 2018 "Neo4j, Inc." [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.gremlin.translation.walker

import org.apache.tinkerpop.gremlin.process.traversal.Order
import org.neo4j.cypher.internal.frontend.v3_2.ast._
import org.opencypher.gremlin.translation.TranslationBuilder
import org.opencypher.gremlin.translation.walker.NodeUtils.expressionValue

/**
  * AST walker that handles translation
  * of the `WITH` clause nodes in the Cypher AST.
  */
object WithWalker {

  def walkClause[T, P](context: StatementContext[T, P], g: TranslationBuilder[T, P], node: With) {
    new WithWalker(context, g).walkClause(node)
  }
}

private class WithWalker[T, P](context: StatementContext[T, P], g: TranslationBuilder[T, P]) {

  def walkClause(node: With) {
    val With(_, ReturnItems(_, items), orderByOption, _, _, _) = node
    for (item <- items) {
      val AliasedReturnItem(expression, Variable(alias)) = item
      expression match {
        case Property(Variable(varName), PropertyKeyName(keyName)) =>
          g.select(varName).values(keyName).as(alias)
        case Variable(varName) =>
          if (varName != alias) {
            context.matchedOrCreatedNodes.add(alias)
            g.select(varName).as(alias)
          }
        case Parameter(name, _) =>
          g.constant(context.extractedParameters(name)).as(alias)
        case expression: Expression =>
          WhereWalker.walk(context, g, expression)
      }
    }

    if (orderByOption.isDefined) {
      sort(node)
    }
  }

  private def sort(node: Clause) {
    val With(_, ReturnItems(_, items), Some(OrderBy(sortItems)), skip, limit, _) = node
    val aliases = items.map(_.asInstanceOf[AliasedReturnItem]).map(_.name)
    g.select(aliases: _*).order()
    for (sortItem <- sortItems) {
      val order = sortItem match {
        case _: AscSortItem =>
          Order.incr
        case _: DescSortItem =>
          Order.decr
      }
      sortItem.expression match {
        case Variable(varName) =>
          g.by(g.start().select(varName), order)
        case _ =>
          context.unsupported("sort expression", sortItem.expression)
      }
    }
    if (skip.isDefined || limit.isDefined) {
      range(skip, limit)
    }
  }

  private def range(skip: Option[Skip], limit: Option[Limit]) {
    val extract: (ASTSlicingPhrase => Long) = ast =>
      expressionValue(ast.expression, context).asInstanceOf[Number].longValue()
    val low = skip.map(extract).getOrElse(0L)
    val high = low + limit.map(extract).getOrElse(-1L)
    g.range(low, high)
  }
}