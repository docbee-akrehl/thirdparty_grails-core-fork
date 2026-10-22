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

import org.grails.datastore.mapping.query.Query;

/**
 * SPI that contributes criterion, order and projection handlers through {@link java.util.ServiceLoader}. List the
 * implementation in {@code META-INF/services/org.grails.orm.hibernate.query.CriteriaHandlerProvider}; its handlers
 * are registered on first query execution, so no registration in {@code BootStrap} is needed. Handlers registered
 * programmatically with {@link CriteriaHandlers} take precedence.
 *
 * <pre>
 * class MyCriteriaHandlers implements CriteriaHandlerProvider {
 *     Map&lt;Class&lt;? extends Query.Order&gt;, OrderHandler&lt;?&gt;&gt; orderHandlers() {
 *         [(CoalesceOrder): new CoalesceOrderHandler()]
 *     }
 * }
 * </pre>
 *
 * @since 8.0
 */
public interface CriteriaHandlerProvider {

    /**
     * @return the criterion handlers by the exact criterion class they handle
     */
    default Map<Class<? extends Query.Criterion>, ContextualCriterionHandler<?>> criterionHandlers() {
        return Map.of();
    }

    /**
     * @return the order handlers by the exact order class they handle
     */
    default Map<Class<? extends Query.Order>, OrderHandler<?>> orderHandlers() {
        return Map.of();
    }

    /**
     * @return the projection handlers by the exact projection class they handle
     */
    default Map<Class<? extends Query.Projection>, ProjectionHandler<?>> projectionHandlers() {
        return Map.of();
    }
}
