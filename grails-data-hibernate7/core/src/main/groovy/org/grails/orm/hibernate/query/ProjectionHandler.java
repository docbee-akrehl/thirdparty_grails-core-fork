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

import jakarta.persistence.criteria.Expression;

import org.grails.datastore.mapping.query.Query;

/**
 * Translates a custom {@link Query.Projection} subclass, added with {@code projections { add(new MyProjection(...)) }},
 * to the expression it selects, for example a database function:
 *
 * <pre>
 * CriteriaHandlers.registerProjectionHandler(LowerProjection) { CriteriaHandlerContext context, LowerProjection projection -&gt;
 *     context.criteriaBuilder.lower(context.resolve(projection.property))
 * }
 * </pre>
 *
 * @param <P> the projection type
 * @since 8.0
 * @see CriteriaHandlers#registerProjectionHandler(Class, ProjectionHandler)
 */
@FunctionalInterface
public interface ProjectionHandler<P extends Query.Projection> {

    /**
     * Translates the projection.
     *
     * @param context the state of the query being translated
     * @param projection the projection
     * @return the selected expression
     */
    Expression<?> handle(CriteriaHandlerContext context, P projection);

    /**
     * Returns the type of the query result when the projection is the only projection of the query.
     *
     * @param projection the projection
     * @return the result type; {@code Object} unless overridden
     */
    default Class<?> resultType(P projection) {
        return Object.class;
    }
}
