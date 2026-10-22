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
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate

import grails.gorm.DetachedCriteria
import grails.gorm.tests.HibernateGormDatastoreSpec
import org.grails.datastore.mapping.query.Query
import org.grails.datastore.mapping.query.Restrictions
import org.grails.orm.hibernate.query.CriteriaHandlerContext
import org.grails.orm.hibernate.query.CriteriaHandlers

/**
 * Custom criterion, order and projection types are translated by handlers that build a Hibernate 7 expression from
 * the current query context, including the aliases created with {@code createAlias}.
 */
class CriteriaHandlerSpec extends HibernateGormDatastoreSpec {

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    void cleanup() {
        CriteriaHandlers.clear()
    }

    void "a criterion handler resolves alias properties and keeps the alias' join type"() {
        given: 'a handler for a criterion matching any of several patterns'
        CriteriaHandlers.registerCriterionHandler(AnyLikeCriterion) { CriteriaHandlerContext context, AnyLikeCriterion criterion ->
            Expression<String> property = (Expression<String>) context.resolve(criterion.property)
            context.criteriaBuilder.or(criterion.patterns.collect { context.criteriaBuilder.like(property, it) } as Predicate[])
        }

        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            or {
                add(new AnyLikeCriterion('c.name', ['C1', 'C3%']))
                eq('title', 'X')
            }
        }

        then: 'T5 has no customer and is kept by the left join'
        results*.code.sort() == ['T1', 'T4', 'T5']
    }

    void "a criterion handler translates nested criteria, binds parameters and builds subqueries"() {
        given:
        CriteriaHandlers.registerCriterionHandler(NotCriterion) { CriteriaHandlerContext context, NotCriterion criterion ->
            context.criteriaBuilder.not(context.toPredicate(criterion.criterion))
        }
        CriteriaHandlers.registerCriterionHandler(IdInCriterion) { CriteriaHandlerContext context, IdInCriterion criterion ->
            context.resolve('id').in(context.subquery(criterion.ids, Long))
        }
        CriteriaHandlers.registerCriterionHandler(ParameterEqualsCriterion) { CriteriaHandlerContext context, ParameterEqualsCriterion criterion ->
            context.criteriaBuilder.equal(context.resolve(criterion.property), context.parameter(String, criterion.value))
        }
        DetachedCriteria<AliasTicket> titledZ = new DetachedCriteria<>(AliasTicket).build {
            createAlias('customer', 'c', JoinType.LEFT)
            eq('title', 'Z')
        }.id()

        expect:
        AliasTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            add(new NotCriterion(Restrictions.eq('c.name', 'C1')))
        }*.code.sort() == ['T2', 'T4']

        and:
        AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
            add(new IdInCriterion(titledZ))
        }*.code == ['T4']

        and:
        AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
            add(new ParameterEqualsCriterion('c.name', 'C2'))
        }*.code == ['T2']
    }

    void "an order handler orders by a coalesce of alias properties"() {
        given:
        CriteriaHandlers.registerOrderHandler(CoalesceOrder) { CriteriaHandlerContext context, CoalesceOrder order ->
            def cb = context.criteriaBuilder
            Expression<String> coalesce = cb.coalesce((Expression<String>) context.resolve(order.property), (Expression<String>) context.resolve(order.fallback))
            order.direction == Query.Order.Direction.ASC ? cb.asc(coalesce) : cb.desc(coalesce)
        }

        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            order(new CoalesceOrder('c.name', 'title', Query.Order.Direction.ASC))
            order('code')
        }

        then: 'C1, C2, C3, then the titles X and Z of the tickets without customer'
        results*.code == ['T1', 'T2', 'T4', 'T5', 'T3']
    }

    void "an order handler orders by a case expression"() {
        given: 'tickets without priority first'
        CriteriaHandlers.registerOrderHandler(MissingFirstOrder) { CriteriaHandlerContext context, MissingFirstOrder order ->
            def cb = context.criteriaBuilder
            cb.asc(cb.selectCase().when(cb.isNull(context.resolve(order.property)), 0).otherwise(1))
        }

        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            order(new MissingFirstOrder('prio'))
            order('code')
        }

        then:
        results*.code == ['T3', 'T5', 'T1', 'T2', 'T4']
    }

    void "a projection handler selects a function of an alias property"() {
        given:
        CriteriaHandlers.registerProjectionHandler(FunctionProjection, { CriteriaHandlerContext context, FunctionProjection projection ->
            context.criteriaBuilder.function(projection.function, projection.type, context.resolve(projection.property))
        } as org.grails.orm.hibernate.query.ProjectionHandler)

        when: 'the only projection'
        List<String> names = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            projections {
                add(new FunctionProjection('lower', 'c.name', String))
            }
            order('code')
        }

        then:
        names == ['c1', 'c2', null, 'c3', null]

        when: 'combined with other projections'
        List rows = AliasTicket.createCriteria().list {
            projections {
                property('code')
                add(new FunctionProjection('length', 'title', Integer))
            }
            eq('code', 'T1')
        }

        then:
        rows.collect { it as List } == [['T1', 1]]
    }

    void "the result type of a single custom projection comes from its handler"() {
        given:
        CriteriaHandlers.registerProjectionHandler(FunctionProjection, new TypedFunctionProjectionHandler())

        expect:
        AliasTicket.createCriteria().list {
            projections {
                add(new FunctionProjection('upper', 'title', String))
            }
            eq('code', 'T5')
        } == ['X']
    }

    void "handlers are discovered through the CriteriaHandlerProvider service"() {
        expect: 'the order handler of TestCriteriaHandlerProvider (META-INF/services) orders by length, then code'
        AliasTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            order(new ServiceLoadedOrder('c.name'))
            order('code')
        }*.code == ['T3', 'T5', 'T1', 'T2', 'T4']
    }
}

class AnyLikeCriterion implements Query.Criterion {
    final String property
    final List<String> patterns

    AnyLikeCriterion(String property, List<String> patterns) {
        this.property = property
        this.patterns = patterns
    }
}

class NotCriterion implements Query.Criterion {
    final Query.Criterion criterion

    NotCriterion(Query.Criterion criterion) {
        this.criterion = criterion
    }
}

class IdInCriterion implements Query.Criterion {
    final DetachedCriteria<?> ids

    IdInCriterion(DetachedCriteria<?> ids) {
        this.ids = ids
    }
}

class ParameterEqualsCriterion implements Query.Criterion {
    final String property
    final String value

    ParameterEqualsCriterion(String property, String value) {
        this.property = property
        this.value = value
    }
}

class CoalesceOrder extends Query.Order {
    final String fallback

    CoalesceOrder(String property, String fallback, Query.Order.Direction direction) {
        super(property, direction)
        this.fallback = fallback
    }
}

class MissingFirstOrder extends Query.Order {
    MissingFirstOrder(String property) {
        super(property)
    }
}

class ServiceLoadedOrder extends Query.Order {
    ServiceLoadedOrder(String property) {
        super(property)
    }
}

class FunctionProjection extends Query.Projection {
    final String function
    final String property
    final Class<?> type

    FunctionProjection(String function, String property, Class<?> type) {
        this.function = function
        this.property = property
        this.type = type
    }
}

class TypedFunctionProjectionHandler implements org.grails.orm.hibernate.query.ProjectionHandler {
    @Override
    Expression<?> handle(CriteriaHandlerContext context, Query.Projection projection) {
        FunctionProjection function = (FunctionProjection) projection
        context.criteriaBuilder.function(function.function, function.type, context.resolve(function.property))
    }

    @Override
    Class<?> resultType(Query.Projection projection) {
        ((FunctionProjection) projection).type
    }
}
