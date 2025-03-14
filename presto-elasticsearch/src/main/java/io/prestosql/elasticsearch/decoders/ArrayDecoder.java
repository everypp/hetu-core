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
package io.prestosql.elasticsearch.decoders;

import io.prestosql.spi.block.BlockBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.Aggregations;

import java.util.List;
import java.util.function.Supplier;

public class ArrayDecoder
        implements Decoder
{
    private final Decoder elementDecoder;

    public ArrayDecoder(Decoder elementDecoder)
    {
        this.elementDecoder = elementDecoder;
    }

    @Override
    public void decode(SearchHit hit, Supplier<Object> getter, BlockBuilder output)
    {
        Object data = getter.get();

        if (data == null) {
            output.appendNull();
        }
        else if (data instanceof List) {
            BlockBuilder array = output.beginBlockEntry();
            ((List<?>) data).forEach(element -> elementDecoder.decode(hit, () -> element, array));
            output.closeEntry();
        }
        else {
            BlockBuilder array = output.beginBlockEntry();
            elementDecoder.decode(hit, () -> data, array);
            output.closeEntry();
        }
    }

    @Override
    public void decode(Aggregations aggregations, Supplier<Object> getter, BlockBuilder output)
    {
        Object data = getter.get();

        if (data == null) {
            output.appendNull();
        }
        else if (data instanceof List) {
            BlockBuilder array = output.beginBlockEntry();
            ((List<?>) data).forEach(element -> elementDecoder.decode(aggregations, () -> element, array));
            output.closeEntry();
        }
        else {
            BlockBuilder array = output.beginBlockEntry();
            elementDecoder.decode(aggregations, () -> data, array);
            output.closeEntry();
        }
    }
}
