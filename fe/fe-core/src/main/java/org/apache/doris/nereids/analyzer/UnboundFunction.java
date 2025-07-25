// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.analyzer;

import org.apache.doris.common.Pair;
import org.apache.doris.nereids.exceptions.UnboundException;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.functions.Function;
import org.apache.doris.nereids.trees.expressions.functions.PropagateNullable;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitor;
import org.apache.doris.nereids.util.Utils;

import com.google.common.base.Joiner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Expression for unbound function.
 */
public class UnboundFunction extends Function implements Unbound, PropagateNullable {
    private final String dbName;
    private final boolean isDistinct;
    private final boolean isSkew;
    // for create view stmt, the start and end position of the function string in original sql
    private final Optional<Pair<Integer, Integer>> indexInSqlString;
    // the start and end position of the function string in original sql
    private final Optional<FunctionIndexInSql> functionIndexInSql;

    /**
     * FunctionIndexInSql
     */
    public static class FunctionIndexInSql {
        public final int functionNameBegin;
        public final int functionNameEnd;
        public final int functionExpressionEnd;

        public FunctionIndexInSql(int nameBegin, int nameEnd, int expressionEnd) {
            this.functionNameBegin = nameBegin;
            this.functionNameEnd = nameEnd;
            this.functionExpressionEnd = expressionEnd;
        }

        public FunctionIndexInSql indexInQueryPart(int offset) {
            return new FunctionIndexInSql(functionNameBegin - offset, functionNameEnd - offset,
                    functionExpressionEnd - offset);
        }
    }

    public UnboundFunction(String name, List<Expression> arguments) {
        this(null, name, false, arguments, false, Optional.empty(), Optional.empty());
    }

    public UnboundFunction(String dbName, String name, List<Expression> arguments) {
        this(dbName, name, false, arguments, false, Optional.empty(), Optional.empty());
    }

    public UnboundFunction(String name, boolean isDistinct, List<Expression> arguments) {
        this(null, name, isDistinct, arguments, false, Optional.empty(), Optional.empty());
    }

    public UnboundFunction(String dbName, String name, boolean isDistinct, List<Expression> arguments, boolean isSkew) {
        this(dbName, name, isDistinct, arguments, isSkew, Optional.empty(), Optional.empty());
    }

    /**UnboundFunction*/
    public UnboundFunction(String dbName, String name, boolean isDistinct,
            List<Expression> arguments, boolean isSkew, Optional<FunctionIndexInSql> functionIndexInSql,
            Optional<Pair<Integer, Integer>> indexInSqlString) {
        super(name, arguments);
        this.dbName = dbName;
        this.isDistinct = isDistinct;
        this.functionIndexInSql = functionIndexInSql;
        this.indexInSqlString = indexInSqlString;
        this.isSkew = isSkew;
    }

    @Override
    public String getExpressionName() {
        if (!this.exprName.isPresent()) {
            this.exprName = Optional.of(Utils.normalizeName(getName(), DEFAULT_EXPRESSION_NAME));
        }
        return this.exprName.get();
    }

    public String getDbName() {
        return dbName;
    }

    public boolean isDistinct() {
        return isDistinct;
    }

    public boolean isSkew() {
        return isSkew;
    }

    public List<Expression> getArguments() {
        return children();
    }

    @Override
    public String computeToSql() throws UnboundException {
        String params = children.stream()
                .map(Expression::toSql)
                .collect(Collectors.joining(", "));
        return getName() + "(" + (isDistinct ? "distinct " : "") + params + ")";
    }

    @Override
    public String toString() {
        String params = Joiner.on(", ").join(children);
        return "'" + getName() + "(" + (isDistinct ? "distinct " : "") + params + ")";
    }

    @Override
    public <R, C> R accept(ExpressionVisitor<R, C> visitor, C context) {
        return visitor.visitUnboundFunction(this, context);
    }

    @Override
    public UnboundFunction withChildren(List<Expression> children) {
        return new UnboundFunction(dbName, getName(), isDistinct, children, isSkew, functionIndexInSql,
                indexInSqlString);
    }

    public Optional<FunctionIndexInSql> getFunctionIndexInSql() {
        return functionIndexInSql;
    }

    public UnboundFunction withIndexInSqlString(Optional<FunctionIndexInSql> functionIndexInSql) {
        return new UnboundFunction(dbName, getName(), isDistinct, children, isSkew, functionIndexInSql,
                indexInSqlString);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        UnboundFunction that = (UnboundFunction) o;
        return isDistinct == that.isDistinct && getName().equals(that.getName());
    }

    @Override
    public int computeHashCode() {
        return Objects.hash(getName(), isDistinct);
    }

    public UnboundFunction withIndexInSql(Pair<Integer, Integer> index) {
        return new UnboundFunction(dbName, getName(), isDistinct, children, isSkew, functionIndexInSql,
                Optional.ofNullable(index));
    }

    public Optional<Pair<Integer, Integer>> getIndexInSqlString() {
        return indexInSqlString;
    }
}
