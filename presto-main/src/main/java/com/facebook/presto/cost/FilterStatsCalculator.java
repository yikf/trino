/*
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
package com.facebook.presto.cost;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.LiteralInterpreter;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.ComparisonExpressionType;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Literal;
import com.facebook.presto.sql.tree.SymbolReference;

import java.util.Map;

import static com.facebook.presto.cost.ComparisonStatsCalculator.comparisonSymbolToLiteralStats;
import static com.facebook.presto.cost.ComparisonStatsCalculator.comparisonSymbolToSymbolStats;
import static com.facebook.presto.cost.StatsUtil.toStatsRepresentation;
import static java.lang.Double.NaN;
import static java.util.Objects.requireNonNull;

public class FilterStatsCalculator
{
    private static final double UNKNOWN_FILTER_COEFFICIENT = 0.9;

    private final Metadata metadata;

    public FilterStatsCalculator(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    public PlanNodeStatsEstimate filterStats(
            PlanNodeStatsEstimate statsEstimate,
            Expression predicate,
            Session session,
            Map<Symbol, Type> types)
    {
        return new FilterExpressionStatsCalculatingVisitor(statsEstimate, session, types).process(predicate);
    }

    public static PlanNodeStatsEstimate filterStatsForUnknownExpression(PlanNodeStatsEstimate inputStatistics)
    {
        return inputStatistics.mapOutputRowCount(rowCount -> rowCount * UNKNOWN_FILTER_COEFFICIENT);
    }

    private class FilterExpressionStatsCalculatingVisitor
            extends AstVisitor<PlanNodeStatsEstimate, Void>
    {
        private final PlanNodeStatsEstimate input;
        private final Session session;
        private final Map<Symbol, Type> types;

        FilterExpressionStatsCalculatingVisitor(PlanNodeStatsEstimate input, Session session, Map<Symbol, Type> types)
        {
            this.input = input;
            this.session = session;
            this.types = types;
        }

        @Override
        protected PlanNodeStatsEstimate visitExpression(Expression node, Void context)
        {
            return filterForUnknownExpression();
        }

        private PlanNodeStatsEstimate filterForUnknownExpression()
        {
            return filterStatsForUnknownExpression(input);
        }

        @Override
        protected PlanNodeStatsEstimate visitComparisonExpression(ComparisonExpression node, Void context)
        {
            // TODO: verify we eliminate Literal-Literal earlier or support them here

            ComparisonExpressionType type = node.getType();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            if (!(left instanceof SymbolReference) && right instanceof SymbolReference) {
                // normalize so that symbol is on the left
                return process(new ComparisonExpression(type.flip(), right, left));
            }

            if (left instanceof SymbolReference && right instanceof Literal) {
                Symbol symbol = Symbol.from(left);
                double literal = doubleValueFromLiteral(types.get(symbol), (Literal) right);
                return comparisonSymbolToLiteralStats(input, symbol, literal, type);
            }

            if (right instanceof SymbolReference) {
                // left is SymbolReference too
                return comparisonSymbolToSymbolStats(input, Symbol.from(left), Symbol.from(right), type);
            }

            return filterStatsForUnknownExpression(input);
        }

        private double doubleValueFromLiteral(Type type, Literal literal)
        {
            Object literalValue = LiteralInterpreter.evaluate(metadata, session.toConnectorSession(), literal);
            return toStatsRepresentation(metadata, session, type, literalValue).orElse(NaN);
        }
    }
}
