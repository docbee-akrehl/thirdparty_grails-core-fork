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
package grails.gorm.tests.criteria

import jakarta.persistence.criteria.Expression

import org.grails.datastore.mapping.query.Query
import org.grails.orm.hibernate.query.CriteriaHandlerContext
import org.grails.orm.hibernate.query.CriteriaHandlerProvider
import org.grails.orm.hibernate.query.OrderHandler

/**
 * Contributes the order handler of {@link ServiceLoadedOrder} through {@code META-INF/services}: tickets without a
 * value first, then by the length of the value.
 */
class TestCriteriaHandlerProvider implements CriteriaHandlerProvider {

    @Override
    Map<Class<? extends Query.Order>, OrderHandler> orderHandlers() {
        [(ServiceLoadedOrder): { CriteriaHandlerContext context, Query.Order order ->
            def cb = context.criteriaBuilder
            cb.asc(cb.coalesce(cb.length((Expression<String>) context.resolve(order.property)), 0))
        } as OrderHandler]
    }
}
