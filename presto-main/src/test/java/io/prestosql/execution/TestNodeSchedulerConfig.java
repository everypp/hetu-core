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
package io.prestosql.execution;

import com.google.common.collect.ImmutableMap;
import io.airlift.configuration.testing.ConfigAssertions;
import io.airlift.units.Duration;
import io.prestosql.execution.scheduler.NodeSchedulerConfig;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.prestosql.execution.scheduler.NodeSchedulerConfig.NetworkTopologyType.LEGACY;

public class TestNodeSchedulerConfig
{
    @Test
    public void testDefaults()
    {
        ConfigAssertions.assertRecordedDefaults(ConfigAssertions.recordDefaults(NodeSchedulerConfig.class)
                .setNetworkTopology(LEGACY)
                .setMinCandidates(10)
                .setMaxSplitsPerNode(100)
                .setMaxPendingSplitsPerTask(10)
                .setIncludeCoordinator(true)
                .setOptimizedLocalScheduling(true)
                .setAllowedNoMatchingNodePeriod(new Duration(2, TimeUnit.MINUTES))
                .setNodeAllocatorType("bin_packing"));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("node-scheduler.network-topology", "flat")
                .put("node-scheduler.min-candidates", "11")
                .put("node-scheduler.include-coordinator", "false")
                .put("node-scheduler.max-pending-splits-per-task", "11")
                .put("node-scheduler.max-splits-per-node", "101")
                .put("node-scheduler.optimized-local-scheduling", "false")
                .put("node-scheduler.allowed-no-matching-node-period", "3m")
                .put("node-scheduler.allocator-type", "fixed_count")
                .build();

        NodeSchedulerConfig expected = new NodeSchedulerConfig()
                .setNetworkTopology("flat")
                .setIncludeCoordinator(false)
                .setMaxSplitsPerNode(101)
                .setMaxPendingSplitsPerTask(11)
                .setMinCandidates(11)
                .setOptimizedLocalScheduling(false)
                .setAllowedNoMatchingNodePeriod(new Duration(3, TimeUnit.MINUTES))
                .setNodeAllocatorType("fixed_count");

        ConfigAssertions.assertFullMapping(properties, expected);
    }
}
