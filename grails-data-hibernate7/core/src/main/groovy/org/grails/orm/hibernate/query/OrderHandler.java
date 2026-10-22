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

import jakarta.persistence.criteria.Order;

import org.grails.datastore.mapping.query.Query;

/**
 * Translates a custom {@link Query.Order} subclass, added with {@code order(new MyOrder(...))}, to a JPA
 * {@link Order}, for example an order by a {@code coalesce} or {@code case} expression:
 *
 * <pre>
 * CriteriaHandlers.registerOrderHandler(CoalesceOrder) { CriteriaHandlerContext context, CoalesceOrder order -&gt;
 *     def cb = context.criteriaBuilder
 *     cb.asc(cb.coalesce(context.resolve(order.property), context.resolve(order.fallback)))
 * }
 * </pre>
 *
 * @param <O> the order type
 * @since 8.0
 * @see CriteriaHandlers#registerOrderHandler(Class, OrderHandler)
 */
@FunctionalInterface
public interface OrderHandler<O extends Query.Order> {

    /**
     * Translates the order.
     *
     * @param context the state of the query being translated
     * @param order the order
     * @return the JPA order, for example {@code criteriaBuilder.asc(expression, Nulls.LAST)}
     */
    Order handle(CriteriaHandlerContext context, O order);
}
