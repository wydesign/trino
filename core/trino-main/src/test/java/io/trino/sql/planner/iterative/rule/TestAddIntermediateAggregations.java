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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.ExchangeNode;
import org.junit.jupiter.api.Test;

import static io.trino.SystemSessionProperties.ENABLE_INTERMEDIATE_AGGREGATIONS;
import static io.trino.SystemSessionProperties.TASK_CONCURRENCY;
import static io.trino.spi.type.BigintType.BIGINT;

public class TestAddIntermediateAggregations
        extends BaseRuleTest
{
    @Test
    public void testSessionDisable()
    {
        tester().assertThat(new AddIntermediateAggregations())
                .setSystemProperty(ENABLE_INTERMEDIATE_AGGREGATIONS, "false")
                .setSystemProperty(TASK_CONCURRENCY, "4")
                .on(p -> p.aggregation(af -> {
                    af.globalGrouping()
                            .step(AggregationNode.Step.FINAL)
                            .addAggregation(p.symbol("c"), PlanBuilder.aggregation("count", ImmutableList.of(new SymbolReference("b"))), ImmutableList.of(BIGINT))
                            .source(
                                    p.gatheringExchange(
                                            ExchangeNode.Scope.REMOTE,
                                            p.aggregation(ap -> ap.globalGrouping()
                                                    .step(AggregationNode.Step.PARTIAL)
                                                    .addAggregation(p.symbol("b"), PlanBuilder.aggregation("count", ImmutableList.of(new SymbolReference("a"))), ImmutableList.of(BIGINT))
                                                    .source(
                                                            p.values(p.symbol("a"))))));
                }))
                .doesNotFire();
    }

    @Test
    public void testWithGroups()
    {
        tester().assertThat(new AddIntermediateAggregations())
                .setSystemProperty(ENABLE_INTERMEDIATE_AGGREGATIONS, "true")
                .setSystemProperty(TASK_CONCURRENCY, "4")
                .on(p -> p.aggregation(af -> {
                    af.singleGroupingSet(p.symbol("c"))
                            .step(AggregationNode.Step.FINAL)
                            .addAggregation(p.symbol("c"), PlanBuilder.aggregation("count", ImmutableList.of(new SymbolReference("b"))), ImmutableList.of(BIGINT))
                            .source(
                                    p.gatheringExchange(
                                            ExchangeNode.Scope.REMOTE,
                                            p.aggregation(ap -> ap.singleGroupingSet(p.symbol("b"))
                                                    .step(AggregationNode.Step.PARTIAL)
                                                    .addAggregation(p.symbol("b"), PlanBuilder.aggregation("count", ImmutableList.of(new SymbolReference("a"))), ImmutableList.of(BIGINT))
                                                    .source(
                                                            p.values(p.symbol("a"))))));
                }))
                .doesNotFire();
    }
}
