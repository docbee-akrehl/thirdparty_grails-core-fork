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

import jakarta.persistence.criteria.JoinType

import grails.gorm.DetachedCriteria
import grails.gorm.PagedResultList
import grails.gorm.tests.HibernateGormDatastoreSpec
import grails.orm.HibernateCriteriaBuilder
import org.grails.orm.hibernate.query.HibernateOrder

/**
 * A query in the shape that applications ported from Hibernate 5 build from an alias map: nested LEFT aliases,
 * an or-filter over alias properties, a permission restriction on an alias, a collection alias, distinct ids
 * through a subquery, count / countDistinct, paging and an order with NULLS LAST.
 */
class AliasReferenceQuerySpec extends HibernateGormDatastoreSpec {

    static final Map<String, List> ALIASES = [
            customer   : ['c', JoinType.LEFT],
            'c.mandant': ['cm', JoinType.LEFT],
            links      : ['l', JoinType.LEFT],
    ].asImmutable()

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    static void createAliases(HibernateCriteriaBuilder builder, Collection<String> paths = ALIASES.keySet()) {
        paths.each { String path -> builder.createAlias(path, (String) ALIASES[path][0], (JoinType) ALIASES[path][1]) }
    }

    static void filter(Object criteria) {
        criteria.or {
            eq('c.name', 'C1')
            eq('cm.name', 'M2')
            eq('title', 'Z')
        }
        criteria.or {
            isNull('cm.id')
            eq('cm.active', true)
        }
    }

    void "a single paged query over nested LEFT aliases with an or-filter, a permission and NULLS LAST"() {
        when:
        List<List<String>> pages = [0, 2].collect { int offset ->
            HibernateCriteriaBuilder builder = AliasTicket.createCriteria()
            PagedResultList<AliasTicket> page = builder.list(max: 2, offset: offset) {
                createAliases(builder, ['customer', 'c.mandant'])
                filter(builder)
                order('c.name', 'asc', nulls: 'last')
                order('code')
            }
            assert page.totalCount == 3
            page*.code
        }

        then:
        pages == [['T1', 'T4'], ['T3']]
    }

    void "count and countDistinct with the collection alias"() {
        when:
        HibernateCriteriaBuilder distinctBuilder = AliasTicket.createCriteria()
        Long distinctCount = distinctBuilder.get {
            createAliases(distinctBuilder)
            filter(distinctBuilder)
            projections {
                countDistinct('id')
            }
        }
        HibernateCriteriaBuilder countBuilder = AliasTicket.createCriteria()
        Long count = countBuilder.get {
            createAliases(countBuilder)
            filter(countBuilder)
            projections {
                count('id')
            }
        }

        then: 'T1 has two links'
        distinctCount == 3L
        count == 4L
    }

    void "two-phase distinct: an id subquery with all aliases and an outer query ordered and paged"() {
        given: 'the ids subquery uses the same alias names as the outer query'
        DetachedCriteria<AliasTicket> ids = new DetachedCriteria<>(AliasTicket)
        ALIASES.each { String path, List alias -> ids.createAlias(path, (String) alias[0], (JoinType) alias[1]) }
        ids = ids.build { filter(delegate) }.distinct('id')

        when:
        List<List<String>> pages = [0, 2].collect { int offset ->
            HibernateCriteriaBuilder builder = AliasTicket.createCriteria()
            builder.list(max: 2, offset: offset) {
                createAlias('customer', 'c', JoinType.LEFT)
                'in'('id', ids)
                order(HibernateOrder.asc('c.name').nullsLast())
                order('code')
            }*.code
        }

        then:
        pages == [['T1', 'T4'], ['T3']]
    }

    void "an unreferenced INNER alias restricts the results"() {
        expect:
        AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
            order('code')
        }*.code == ['T1', 'T2', 'T4']
    }

    void "criteria applied through an explicit builder parameter inside the query closure"() {
        given:
        Closure additional = { HibernateCriteriaBuilder builder ->
            builder.'in'('c.name', ['C1', 'C3'])
            builder.not { eq('title', 'X') }
        }

        when:
        HibernateCriteriaBuilder builder = AliasTicket.createCriteria()
        List<String> codes = builder.list {
            createAliases(builder)
            additional.call(builder)
            projections {
                distinct('code')
            }
        }

        then:
        codes.sort() == ['T1', 'T4']
    }
}
