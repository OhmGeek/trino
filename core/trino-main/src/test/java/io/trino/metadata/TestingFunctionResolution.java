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
package io.trino.metadata;

import com.google.common.collect.ImmutableSet;
import io.trino.FeaturesConfig;
import io.trino.Session;
import io.trino.operator.aggregation.TestingAggregationFunction;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.function.InvocationConvention;
import io.trino.spi.function.OperatorType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeSignature;
import io.trino.sql.analyzer.TypeSignatureProvider;
import io.trino.sql.gen.ExpressionCompiler;
import io.trino.sql.gen.PageFunctionCompiler;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.QualifiedName;
import io.trino.testing.LocalQueryRunner;
import io.trino.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.metadata.FunctionExtractor.extractFunctions;
import static io.trino.metadata.MetadataManager.createTestMetadataManager;
import static io.trino.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static io.trino.transaction.TransactionBuilder.transaction;
import static java.util.Objects.requireNonNull;

public class TestingFunctionResolution
{
    private final TransactionManager transactionManager;
    private final Metadata metadata;

    public TestingFunctionResolution()
    {
        this(ImmutableSet.of());
    }

    public TestingFunctionResolution(Set<Class<?>> functions)
    {
        transactionManager = createTestTransactionManager();
        MetadataManager metadataManager = createTestMetadataManager(transactionManager, new FeaturesConfig());
        metadataManager.addFunctions(extractFunctions(functions));
        metadata = metadataManager;
    }

    public TestingFunctionResolution(LocalQueryRunner localQueryRunner)
    {
        this(localQueryRunner.getTransactionManager(), localQueryRunner.getMetadata());
    }

    public TestingFunctionResolution(TransactionManager transactionManager, Metadata metadata)
    {
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    public TestingFunctionResolution addFunctions(List<? extends SqlFunction> functions)
    {
        metadata.addFunctions(functions);
        return this;
    }

    public Metadata getMetadata()
    {
        return metadata;
    }

    public ExpressionCompiler getExpressionCompiler()
    {
        return new ExpressionCompiler(metadata, getPageFunctionCompiler());
    }

    public PageFunctionCompiler getPageFunctionCompiler()
    {
        return getPageFunctionCompiler(0);
    }

    public PageFunctionCompiler getPageFunctionCompiler(int expressionCacheSize)
    {
        return new PageFunctionCompiler(metadata, expressionCacheSize);
    }

    public ResolvedFunction resolveOperator(OperatorType operatorType, List<? extends Type> argumentTypes)
            throws OperatorNotFoundException
    {
        return inTransaction(session -> metadata.resolveOperator(session, operatorType, argumentTypes));
    }

    public ResolvedFunction getCoercion(Type fromType, Type toType)
    {
        return inTransaction(session -> metadata.getCoercion(session, fromType, toType));
    }

    public ResolvedFunction getCoercion(QualifiedName name, Type fromType, Type toType)
    {
        return inTransaction(session -> metadata.getCoercion(session, name, fromType, toType));
    }

    public TestingFunctionCallBuilder functionCallBuilder(QualifiedName name)
    {
        return new TestingFunctionCallBuilder(name);
    }

    //
    // Resolving or fetching a function in a transaction and then using that in another transaction, is not
    // legal, but works for tests
    //

    public ResolvedFunction resolveFunction(QualifiedName name, List<TypeSignatureProvider> parameterTypes)
    {
        return inTransaction(session -> metadata.resolveFunction(session, name, parameterTypes));
    }

    public FunctionInvoker getScalarFunctionInvoker(QualifiedName name, List<TypeSignatureProvider> parameterTypes, InvocationConvention invocationConvention)
    {
        return inTransaction(session -> metadata.getScalarFunctionInvoker(metadata.resolveFunction(session, name, parameterTypes), invocationConvention));
    }

    public TestingAggregationFunction getAggregateFunction(QualifiedName name, List<TypeSignatureProvider> parameterTypes)
    {
        return inTransaction(session -> {
            ResolvedFunction resolvedFunction = metadata.resolveFunction(session, name, parameterTypes);
            return new TestingAggregationFunction(
                    resolvedFunction.getSignature(),
                    resolvedFunction.getFunctionNullability(),
                    metadata.getAggregateFunctionImplementation(resolvedFunction));
        });
    }

    private <T> T inTransaction(Function<Session, T> transactionSessionConsumer)
    {
        return transaction(transactionManager, new AllowAllAccessControl())
                .singleStatement()
                .execute(TEST_SESSION, session -> {
                    // metadata.getCatalogHandle() registers the catalog for the transaction
                    session.getCatalog().ifPresent(catalog -> metadata.getCatalogHandle(session, catalog));
                    return transactionSessionConsumer.apply(session);
                });
    }

    public class TestingFunctionCallBuilder
    {
        private final QualifiedName name;
        private List<TypeSignature> argumentTypes = new ArrayList<>();
        private List<Expression> argumentValues = new ArrayList<>();

        public TestingFunctionCallBuilder(QualifiedName name)
        {
            this.name = name;
        }

        public TestingFunctionCallBuilder addArgument(Type type, Expression value)
        {
            requireNonNull(type, "type is null");
            return addArgument(type.getTypeSignature(), value);
        }

        public TestingFunctionCallBuilder addArgument(TypeSignature typeSignature, Expression value)
        {
            requireNonNull(typeSignature, "typeSignature is null");
            requireNonNull(value, "value is null");
            argumentTypes.add(typeSignature);
            argumentValues.add(value);
            return this;
        }

        public TestingFunctionCallBuilder setArguments(List<Type> types, List<Expression> values)
        {
            requireNonNull(types, "types is null");
            requireNonNull(values, "values is null");
            argumentTypes = types.stream()
                    .map(Type::getTypeSignature)
                    .collect(Collectors.toList());
            argumentValues = new ArrayList<>(values);
            return this;
        }

        public FunctionCall build()
        {
            return new FunctionCall(
                    Optional.empty(),
                    resolveFunction(name, TypeSignatureProvider.fromTypeSignatures(argumentTypes)).toQualifiedName(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    argumentValues);
        }
    }
}
