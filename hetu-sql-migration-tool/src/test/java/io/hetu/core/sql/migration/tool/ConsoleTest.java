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
package io.hetu.core.sql.migration.tool;

import io.hetu.core.sql.migration.SqlSyntaxType;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertTrue;

public class ConsoleTest
{
    private Console consoleUnderTest;

    @BeforeMethod
    public void setUp()
    {
        consoleUnderTest = new Console();
        consoleUnderTest.cliOptions = mock(CliOptions.class);
    }

    @Test
    public void testExecuteCommand() throws Exception
    {
        // Setup
        final SessionProperties session = new SessionProperties();
        session.setSourceType(SqlSyntaxType.HIVE);
        session.setParsingOptions(false);
        session.setConsolePrintEnable(false);
        session.setMigrationConfig(new MigrationConfig(consoleUnderTest.cliOptions.configFile));
        session.setDebugEnable(false);

        // Run the test
        final boolean result = consoleUnderTest.executeCommand("query", "outputFile", session);

        // Verify the results
        assertTrue(result);
    }
}
