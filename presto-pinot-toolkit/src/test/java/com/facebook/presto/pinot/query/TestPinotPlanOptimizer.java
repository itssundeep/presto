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
package com.facebook.presto.pinot.query;

import com.facebook.presto.Session;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.cost.StatsProvider;
import com.facebook.presto.expressions.LogicalRowExpressions;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.pinot.PinotConfig;
import com.facebook.presto.pinot.PinotPlanOptimizer;
import com.facebook.presto.pinot.PinotTableHandle;
import com.facebook.presto.pinot.TestPinotQueryBase;
import com.facebook.presto.pinot.TestPinotSplitManager;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.PlanVariableAllocator;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.assertions.ExpectedValueProvider;
import com.facebook.presto.sql.planner.assertions.MatchResult;
import com.facebook.presto.sql.planner.assertions.Matcher;
import com.facebook.presto.sql.planner.assertions.PlanAssert;
import com.facebook.presto.sql.planner.assertions.PlanMatchPattern;
import com.facebook.presto.sql.planner.assertions.SymbolAliases;
import com.facebook.presto.sql.planner.iterative.rule.test.PlanBuilder;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.facebook.presto.sql.relational.RowExpressionDeterminismEvaluator;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.aggregation;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.node;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

public class TestPinotPlanOptimizer
        extends TestPinotQueryBase
{
    private static final SessionHolder defaultSessionHolder = new SessionHolder(false);
    private final LogicalRowExpressions logicalRowExpressions = new LogicalRowExpressions(
            new RowExpressionDeterminismEvaluator(functionMetadataManager),
            new FunctionResolution(functionMetadataManager),
            functionMetadataManager);
    private final PinotTableHandle pinotTable = TestPinotSplitManager.hybridTable;

    private static void assertPlanMatch(PlanNode actual, PlanMatchPattern expected, TypeProvider typeProvider)
    {
        PlanAssert.assertPlan(
                defaultSessionHolder.getSession(),
                metadata,
                (node, sourceStats, lookup, session, types) -> PlanNodeStatsEstimate.unknown(),
                new Plan(actual, typeProvider, StatsAndCosts.empty()),
                expected);
    }

    private static final class PinotTableScanMatcher
            implements Matcher
    {
        private final ConnectorId connectorId;
        private final String tableName;
        private final Optional<String> pqlRegex;
        private final Optional<Boolean> scanParallelismExpected;
        private final String[] columns;

        static PlanMatchPattern match(
                String connectorName,
                String tableName,
                Optional<String> pqlRegex,
                Optional<Boolean> scanParallelismExpected,
                String... columnNames)
        {
            return node(TableScanNode.class)
                    .with(new PinotTableScanMatcher(
                            new ConnectorId(connectorName),
                            tableName,
                            pqlRegex, scanParallelismExpected, columnNames));
        }

        static PlanMatchPattern match(
                PinotTableHandle tableHandle,
                Optional<String> pqlRegex,
                Optional<Boolean> scanParallelismExpected,
                List<VariableReferenceExpression> variables)
        {
            return match(tableHandle.getConnectorId(),
                    tableHandle.getTableName(),
                    pqlRegex,
                    scanParallelismExpected,
                    variables.stream().map(VariableReferenceExpression::getName).toArray(String[]::new));
        }

        private PinotTableScanMatcher(
                ConnectorId connectorId,
                String tableName,
                Optional<String> pqlRegex,
                Optional<Boolean> scanParallelismExpected,
                String... columns)
        {
            this.connectorId = connectorId;
            this.pqlRegex = pqlRegex;
            this.scanParallelismExpected = scanParallelismExpected;
            this.columns = columns;
            this.tableName = tableName;
        }

        @Override
        public boolean shapeMatches(PlanNode node)
        {
            return node instanceof TableScanNode;
        }

        private static boolean checkPqlMatches(Optional<String> regex, Optional<String> pql)
        {
            if (!pql.isPresent() && !regex.isPresent()) {
                return true;
            }
            if (pql.isPresent() && regex.isPresent()) {
                String toMatch = pql.get();
                Pattern compiled = Pattern.compile(regex.get(), Pattern.CASE_INSENSITIVE);
                return compiled.matcher(toMatch).matches();
            }
            return false;
        }

        @Override
        public MatchResult detailMatches(
                PlanNode node,
                StatsProvider stats,
                Session session,
                Metadata metadata,
                SymbolAliases symbolAliases)
        {
            checkState(shapeMatches(node), "Plan testing framework error: shapeMatches returned false in detailMatches in %s", this.getClass().getName());

            TableScanNode tableScanNode = (TableScanNode) node;
            if (connectorId.equals(tableScanNode.getTable().getConnectorId())) {
                PinotTableHandle pinotTableHandle = (PinotTableHandle) tableScanNode.getTable().getConnectorHandle();
                if (pinotTableHandle.getTableName().equals(tableName)) {
                    Optional<String> pql = pinotTableHandle.getPql().map(PinotQueryGenerator.GeneratedPql::getPql);
                    if (checkPqlMatches(pqlRegex, pql)) {
                        return MatchResult.match(SymbolAliases.builder().putAll(Arrays.stream(columns).collect(toMap(identity(), SymbolReference::new))).build());
                    }
                }
            }
            return MatchResult.NO_MATCH;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("connectorId", connectorId)
                    .add("tableName", tableName)
                    .add("pqlRegex", pqlRegex)
                    .add("scanParallelismExpected", scanParallelismExpected)
                    .add("columns", columns)
                    .toString();
        }
    }

    @Test
    public void testLimitPushdownWithStarSelection()
    {
        PlanBuilder pb = createPlanBuilder(defaultSessionHolder);
        PlanNode originalPlan = limit(pb, 50L, tableScan(pb, pinotTable, regionId, city, fare, secondsSinceEpoch));
        PlanNode optimized = getOptimizedPlan(pb, originalPlan);
        assertPlanMatch(optimized, PinotTableScanMatcher.match(pinotTable, Optional.of("SELECT regionId, city, fare, secondsSinceEpoch FROM hybrid LIMIT 50"), Optional.of(false), originalPlan.getOutputVariables()), typeProvider);
    }

    @Test
    public void testPartialPredicatePushdown()
    {
        PlanBuilder pb = createPlanBuilder(defaultSessionHolder);
        TableScanNode tableScanNode = tableScan(pb, pinotTable, regionId, city, fare, secondsSinceEpoch);
        FilterNode filter = filter(pb, tableScanNode, getRowExpression("lower(substr(city, 0, 3)) = 'del' AND fare > 100", defaultSessionHolder));
        PlanNode originalPlan = limit(pb, 50L, filter);
        PlanNode optimized = getOptimizedPlan(pb, originalPlan);
        PlanMatchPattern tableScanMatcher = PinotTableScanMatcher.match(pinotTable, Optional.of("SELECT regionId, city, fare, secondsSinceEpoch FROM hybrid__TABLE_NAME_SUFFIX_TEMPLATE__ WHERE \\(fare > 100\\).*"), Optional.of(true), filter.getOutputVariables());
        assertPlanMatch(optimized, PlanMatchPattern.limit(50L, PlanMatchPattern.filter("lower(substr(city, 0, 3)) = 'del'", tableScanMatcher)), typeProvider);
    }

    @Test
    public void testUnsupportedPredicatePushdown()
    {
        Map<String, ExpectedValueProvider<FunctionCall>> aggregationsSecond = ImmutableMap.of(
                "count", PlanMatchPattern.functionCall("count", false, ImmutableList.of()));

        PlanBuilder planBuilder = createPlanBuilder(defaultSessionHolder);
        PlanNode limit = limit(planBuilder, 50L, tableScan(planBuilder, pinotTable, regionId, city, fare, secondsSinceEpoch));
        PlanNode originalPlan = planBuilder.aggregation(builder -> builder.source(limit).globalGrouping().addAggregation(new VariableReferenceExpression("count", BIGINT), getRowExpression("count(*)", defaultSessionHolder)));

        PlanNode optimized = getOptimizedPlan(planBuilder, originalPlan);

        PlanMatchPattern tableScanMatcher = PinotTableScanMatcher.match(pinotTable, Optional.of("SELECT regionId, city, fare, secondsSinceEpoch FROM hybrid LIMIT 50"), Optional.of(false), originalPlan.getOutputVariables());
        assertPlanMatch(optimized, aggregation(aggregationsSecond, tableScanMatcher), typeProvider);
    }

    private PlanNode getOptimizedPlan(PlanBuilder planBuilder, PlanNode originalPlan)
    {
        PinotConfig pinotConfig = new PinotConfig();
        PinotQueryGenerator pinotQueryGenerator = new PinotQueryGenerator(pinotConfig, typeManager, functionMetadataManager, standardFunctionResolution);
        PinotPlanOptimizer optimizer = new PinotPlanOptimizer(pinotQueryGenerator, typeManager, functionMetadataManager, logicalRowExpressions, standardFunctionResolution);
        return optimizer.optimize(originalPlan, defaultSessionHolder.getConnectorSession(), new PlanVariableAllocator(), planBuilder.getIdAllocator());
    }
}
