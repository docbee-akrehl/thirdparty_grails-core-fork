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

import java.util.List;

import jakarta.persistence.criteria.JoinType;

import org.grails.datastore.gorm.query.criteria.AbstractDetachedCriteria;
import org.grails.datastore.mapping.query.api.Criteria;

/**
 * Groovy extension methods that add {@code sqlRestriction} and {@code createAlias} with a join type to detached
 * criteria, registered in
 * {@code META-INF/services/org.codehaus.groovy.runtime.ExtensionModule}. The {@code and}, {@code or} and {@code not}
 * blocks of a criteria query, and the association blocks inside them, resolve their calls against a detached
 * criteria, so a {@code sqlRestriction} in such a block restricts the criteria it is called in. The same methods
 * restrict a {@link grails.gorm.DetachedCriteria} of a Hibernate entity.
 *
 * @since 8.0.0
 */
public final class HibernateDetachedCriteriaExtension {

    private HibernateDetachedCriteriaExtension() {
    }

    /**
     * Restricts the results with a native SQL condition. {@code {alias}} in the SQL stands for the table alias of
     * the criteria's entity.
     *
     * @param self the detached criteria
     * @param sqlRestriction the SQL condition
     * @return the detached criteria
     */
    public static Criteria sqlRestriction(AbstractDetachedCriteria<?> self, String sqlRestriction) {
        return sqlRestriction(self, sqlRestriction, List.of());
    }

    /**
     * Restricts the results with a native SQL condition whose {@code ?} placeholders are bound to the given values.
     * {@code {alias}} in the SQL stands for the table alias of the criteria's entity.
     *
     * @param self the detached criteria
     * @param sqlRestriction the SQL condition
     * @param values the values of the {@code ?} placeholders, in order, none of them {@code null}
     * @return the detached criteria
     * @throws IllegalArgumentException if the number of {@code ?} placeholders differs from the number of values,
     *     or a value is {@code null}
     */
    public static Criteria sqlRestriction(AbstractDetachedCriteria<?> self, String sqlRestriction, List<?> values) {
        self.add(new SqlRestriction(sqlRestriction, values));
        return self;
    }

    /**
     * Joins an association with the given join type and makes it available under the given alias, as
     * {@code createAlias} of a criteria query does. The alias is joined even if nothing refers to it.
     *
     * @param self the detached criteria
     * @param associationPath the association to join; it may start with another alias, for example
     *     {@code c.mandant}, or be a full association path such as {@code customer.mandant}
     * @param alias the alias under which the joined association can be referenced
     * @param joinType the join type as a Hibernate 5 criteria constant: {@code 0} inner, {@code 1} left outer,
     *     {@code 2} right outer join
     * @return the detached criteria
     */
    public static Criteria createAlias(AbstractDetachedCriteria<?> self, String associationPath, String alias, int joinType) {
        return createAlias(self, associationPath, alias, HibernateAlias.toJoinType(joinType));
    }

    /**
     * Joins an association with the given join type and makes it available under the given alias, as
     * {@code createAlias} of a criteria query does. The alias is joined even if nothing refers to it.
     *
     * @param self the detached criteria
     * @param associationPath the association to join; it may start with another alias, for example
     *     {@code c.mandant}, or be a full association path such as {@code customer.mandant}
     * @param alias the alias under which the joined association can be referenced
     * @param joinType the join type
     * @return the detached criteria
     */
    public static Criteria createAlias(AbstractDetachedCriteria<?> self, String associationPath, String alias, JoinType joinType) {
        self.add(new HibernateAlias(associationPath, alias, joinType));
        return self;
    }
}
