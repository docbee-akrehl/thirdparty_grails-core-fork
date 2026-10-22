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

import org.grails.datastore.mapping.query.Query;

/**
 * Criterion matching a property that is not equal to a value with Hibernate 5's semantics: it renders
 * {@code x <> ?}, so by SQL's three-valued logic the rows where the column is {@code NULL} do not match. The
 * {@link Query.NotEquals} criterion of {@code ne} renders {@code (x <> ? or x is null)} and includes them.
 * A {@code null} value renders {@code x is not null}. The value may be a detached criteria, which becomes a subquery.
 *
 * <p>Created by {@code neLegacy(property, value)} in a criteria query and a detached criteria, or with
 * {@link #neLegacy(String, Object)}.
 *
 * <p>docbee fork: a migration helper for code written against Hibernate 5. To be removed once its callers are
 * migrated to {@code ne} (with an explicit {@code isNull} where the null rows are wanted, or none where not).
 *
 * @since 8.0.0
 */
public class NotEqualsLegacy extends Query.PropertyCriterion {

    public NotEqualsLegacy(String name, Object value) {
        super(name, value);
    }

    /**
     * Creates the Hibernate 5 not-equal criterion, which excludes the rows where the property is {@code null}.
     *
     * @param propertyName the property
     * @param value the value, a detached criteria for a subquery, or {@code null} for {@code is not null}
     * @return the criterion
     */
    public static NotEqualsLegacy neLegacy(String propertyName, Object value) {
        return new NotEqualsLegacy(propertyName, value);
    }
}
