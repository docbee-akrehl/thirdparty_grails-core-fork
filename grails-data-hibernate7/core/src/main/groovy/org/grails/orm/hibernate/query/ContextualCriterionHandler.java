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
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;

import org.grails.datastore.mapping.query.Query;

/**
 * A {@link PredicateGenerator.CriterionHandler} that translates a custom criterion with the full
 * {@link CriteriaHandlerContext}: aliases created with {@code createAlias}, nested criteria, parameters and
 * subqueries.
 *
 * <pre>
 * CriteriaHandlers.registerCriterionHandler(AnyLike) { CriteriaHandlerContext context, AnyLike criterion -&gt;
 *     def property = context.resolve(criterion.property)       // e.g. 'c.name' of createAlias('customer', 'c')
 *     context.criteriaBuilder.or(criterion.patterns.collect { context.criteriaBuilder.like(property, it) } as Predicate[])
 * }
 * </pre>
 *
 * @param <C> the criterion type
 * @since 8.0
 * @see CriteriaHandlers#registerCriterionHandler(Class, ContextualCriterionHandler)
 */
@FunctionalInterface
public interface ContextualCriterionHandler<C extends Query.Criterion> extends PredicateGenerator.CriterionHandler {

    /**
     * Translates the criterion to a predicate.
     *
     * @param context the state of the query being translated
     * @param criterion the criterion
     * @return the predicate
     */
    Predicate handleCriterion(CriteriaHandlerContext context, C criterion);

    @Override
    @SuppressWarnings("unchecked")
    default Predicate handle(CriteriaHandlerContext context, Query.Criterion criterion) {
        return handleCriterion(context, (C) criterion);
    }

    @Override
    default Predicate handle(AbstractQuery<?> criteriaQuery, From<?, ?> root, HibernateCriteriaBuilder criteriaBuilder,
            Query.Criterion criterion) {
        throw new UnsupportedOperationException(
                "A ContextualCriterionHandler is called with a CriteriaHandlerContext, see handle(CriteriaHandlerContext, Criterion)");
    }
}
