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
package io.prestosql.parquet.writer;

import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.schema.MessageType;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class MessageTypeConverterTest
{
    @Test
    public void testToParquetSchema()
    {
        // Setup
        final MessageType schema = new MessageType("name", Arrays.asList());
        final List<SchemaElement> expectedResult = Arrays.asList(new SchemaElement("name"));

        // Run the test
        final List<SchemaElement> result = MessageTypeConverter.toParquetSchema(schema);

        // Verify the results
        assertEquals(expectedResult, result);
    }
}
