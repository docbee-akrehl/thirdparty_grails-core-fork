/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.orm.hibernate.query;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;

import org.grails.datastore.mapping.query.Query;
import org.grails.datastore.mapping.query.api.QueryableCriteria;
import org.grails.orm.hibernate.cfg.domainbinding.hibernate.GrailsHibernatePersistentEntity;

/**
 * The state of the query being translated, handed to a {@link ContextualCriterionHandler}, an {@link OrderHandler}
 * or a {@link ProjectionHandler}, so a handler builds its Hibernate expression the way the built-in criteria do:
 * with the aliases created by {@code createAlias} (and their join types and with-clauses), the joins of the query,
 * its parameters and its subqueries.
 *
 * <p>A context is bound to the query or subquery being translated; inside a subquery {@link #resolve(String)}
 * resolves the subquery's own aliases first and falls back to the aliases of the enclosing query.
 *
 * @since 8.0
 * @see CriteriaHandlers
 */
public interface CriteriaHandlerContext {

    /**
     * @return the criteria builder of the query
     */
    HibernateCriteriaBuilder getCriteriaBuilder();

    /**
     * @return the query or subquery being built
     */
    AbstractQuery<?> getQuery();

    /**
     * @return the root of the query or subquery, or the join of the association whose criteria are translated
     */
    From<?, ?> getRoot();

    /**
     * @return the entity of {@link #getRoot()}, or {@code null} if it is not known
     */
    GrailsHibernatePersistentEntity getEntity();

    /**
     * Resolves a property the way the built-in criteria do: a property of the root ({@code name}, or a path such
     * as {@code customer.name}), an alias ({@code c}) or a property through an alias ({@code c.name},
     * {@code cm.parent.name}), or a projection alias.
     *
     * @param property the property, path or alias
     * @return the expression
     * @throws IllegalArgumentException if the property cannot be resolved
     */
    Expression<?> resolve(String property);

    /**
     * Returns the join of an alias created with {@code createAlias}, or of an association path joined by the query,
     * joining the alias if it is not joined yet.
     *
     * @param aliasOrPath the alias or association path
     * @return the join, or {@code null} if there is no such alias or join
     */
    From<?, ?> getJoin(String aliasOrPath);

    /**
     * Translates a criterion, built-in or handled by a registered handler, in this context.
     *
     * @param criterion the criterion, for example from {@link org.grails.datastore.mapping.query.Restrictions}
     * @return the predicate
     */
    Predicate toPredicate(Query.Criterion criterion);

    /**
     * Builds a subquery of this query from a detached criteria, with its projections and criteria. The subquery's
     * aliases are scoped to it; a property of the subquery that is not one of its own aliases can refer to an
     * alias of this query, which correlates the subquery.
     *
     * @param criteria the criteria of the subquery, typically with a single projection
     * @param resultType the type the subquery selects
     * @param <T> the type the subquery selects
     * @return the subquery
     */
    <T> Subquery<T> subquery(QueryableCriteria<?> criteria, Class<T> resultType);

    /**
     * Creates a parameter that is bound to the given value when the query is executed.
     *
     * @param type the type of the parameter
     * @param value the value
     * @param <T> the type of the parameter
     * @return the parameter expression
     */
    <T> ParameterExpression<T> parameter(Class<T> type, T value);
}
