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

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.grails.datastore.mapping.query.Query;

/**
 * Registry of the handlers that translate custom criterion, order and projection types of GORM criteria queries
 * to Hibernate 7 expressions. A handler is looked up by the exact class of the criterion, order or projection and
 * receives a {@link CriteriaHandlerContext} with the aliases, joins, parameters and subqueries of the query being
 * translated.
 *
 * <pre>
 * CriteriaHandlers.registerCriterionHandler(AnyLike) { CriteriaHandlerContext context, AnyLike criterion -&gt; ... }
 * CriteriaHandlers.registerOrderHandler(CoalesceOrder) { CriteriaHandlerContext context, CoalesceOrder order -&gt; ... }
 * CriteriaHandlers.registerProjectionHandler(LowerProjection) { CriteriaHandlerContext context, LowerProjection projection -&gt; ... }
 *
 * Ticket.createCriteria().list {
 *     createAlias('customer', 'c', JoinType.LEFT)
 *     add(new AnyLike('c.name', ['A%', 'B%']))
 *     order(new CoalesceOrder('c.name', 'title'))
 *     projections { add(new LowerProjection('c.name')) }
 * }
 * </pre>
 *
 * <p>Handlers can also be contributed through the {@link CriteriaHandlerProvider} {@link ServiceLoader} SPI.
 * Criterion handlers share the registry of {@link PredicateGenerator#registerCriterionHandler}, so a handler
 * registered there without context keeps working.
 *
 * @since 8.0
 */
public final class CriteriaHandlers {

    private static final Map<Class<? extends Query.Order>, OrderHandler<?>> ORDER_HANDLERS = new ConcurrentHashMap<>();
    private static final Map<Class<? extends Query.Projection>, ProjectionHandler<?>> PROJECTION_HANDLERS =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean SERVICE_LOADERS_INITIALIZED = new AtomicBoolean(false);

    private CriteriaHandlers() {
    }

    /**
     * Registers the handler of a custom criterion type. It replaces a handler registered before for the type and
     * takes precedence over the built-in handling of the type.
     *
     * @param type the exact criterion class
     * @param handler the handler
     * @param <C> the criterion type
     */
    public static <C extends Query.Criterion> void registerCriterionHandler(Class<C> type, ContextualCriterionHandler<C> handler) {
        PredicateGenerator.registerCriterionHandler(type, handler);
    }

    /**
     * Registers the handler of a custom order type, added to a query with {@code order(Query.Order)}.
     *
     * @param type the exact order class
     * @param handler the handler
     * @param <O> the order type
     */
    public static <O extends Query.Order> void registerOrderHandler(Class<O> type, OrderHandler<O> handler) {
        ORDER_HANDLERS.put(type, handler);
    }

    /**
     * Registers the handler of a custom projection type, added to a query with {@code projections { add(...) }}.
     *
     * @param type the exact projection class
     * @param handler the handler
     * @param <P> the projection type
     */
    public static <P extends Query.Projection> void registerProjectionHandler(Class<P> type, ProjectionHandler<P> handler) {
        PROJECTION_HANDLERS.put(type, handler);
    }

    /**
     * Returns the handler of the exact class of an order.
     *
     * @param order the order
     * @return the handler, or {@code null} if the order is not handled by a registered handler
     */
    @SuppressWarnings("unchecked")
    public static OrderHandler<Query.Order> findOrderHandler(Query.Order order) {
        loadServiceProviders();
        return (OrderHandler<Query.Order>) ORDER_HANDLERS.get(order.getClass());
    }

    /**
     * Returns the handler of the exact class of a projection.
     *
     * @param projection the projection
     * @return the handler, or {@code null} if the projection is not handled by a registered handler
     */
    @SuppressWarnings("unchecked")
    public static ProjectionHandler<Query.Projection> findProjectionHandler(Query.Projection projection) {
        loadServiceProviders();
        return (ProjectionHandler<Query.Projection>) PROJECTION_HANDLERS.get(projection.getClass());
    }

    /**
     * Removes all registered criterion, order and projection handlers, and makes the {@link ServiceLoader}
     * providers load again on the next query. Useful for test cleanup.
     */
    public static void clear() {
        ORDER_HANDLERS.clear();
        PROJECTION_HANDLERS.clear();
        PredicateGenerator.clearCustomCriterionHandlers();
    }

    static void resetServiceProviders() {
        SERVICE_LOADERS_INITIALIZED.set(false);
    }

    static void loadServiceProviders() {
        if (SERVICE_LOADERS_INITIALIZED.compareAndSet(false, true)) {
            ServiceLoader.load(CriteriaHandlerProvider.class, Thread.currentThread().getContextClassLoader())
                    .forEach(provider -> {
                        provider.criterionHandlers().forEach(PredicateGenerator.CUSTOM_HANDLERS::putIfAbsent);
                        provider.orderHandlers().forEach(ORDER_HANDLERS::putIfAbsent);
                        provider.projectionHandlers().forEach(PROJECTION_HANDLERS::putIfAbsent);
                    });
        }
    }
}
