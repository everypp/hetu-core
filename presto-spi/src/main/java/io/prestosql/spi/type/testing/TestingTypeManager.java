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
package io.prestosql.spi.type.testing;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.type.ParametricType;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeId;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.spi.type.TypeNotFoundException;
import io.prestosql.spi.type.TypeOperators;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.TypeSignatureParameter;

import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static io.prestosql.spi.block.MethodHandleUtil.nativeValueGetter;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.HyperLogLogType.HYPER_LOG_LOG;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.VARCHAR;

public class TestingTypeManager
        implements TypeManager
{
    @Override
    public Type getType(TypeSignature signature)
    {
        for (Type type : getTypes()) {
            if (signature.getBase().equals(type.getTypeSignature().getBase())) {
                return type;
            }
        }
        throw new TypeNotFoundException(signature);
    }

    @Override
    public Type getParameterizedType(String baseTypeName, List<TypeSignatureParameter> typeParameters)
    {
        return null;
    }

    @Override
    public List<Type> getTypes()
    {
        return ImmutableList.of(BOOLEAN, BIGINT, INTEGER, DOUBLE, VARCHAR, VARBINARY, TIMESTAMP, DATE, HYPER_LOG_LOG);
    }

    @Override
    public Collection<ParametricType> getParametricTypes()
    {
        return null;
    }

    @Override
    public Optional<Type> getCommonSuperType(Type firstType, Type secondType)
    {
        return Optional.empty();
    }

    @Override
    public boolean canCoerce(Type actualType, Type expectedType)
    {
        return false;
    }

    @Override
    public boolean isTypeOnlyCoercion(Type actualType, Type expectedType)
    {
        return false;
    }

    @Override
    public Optional<Type> coerceTypeBase(Type sourceType, String resultTypeBase)
    {
        return Optional.empty();
    }

    @Override
    public MethodHandle resolveOperator(OperatorType operatorType, List<? extends Type> argumentTypes)
    {
        return nativeValueGetter(argumentTypes.get(0));
    }

    @Override
    public Type getType(TypeId id)
    {
        for (Type type : getTypes()) {
            if (type.getTypeId().equals(id)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Type not found: " + id);
    }

    @Override
    public TypeOperators getTypeOperators()
    {
        return new TypeOperators();
    }
}
