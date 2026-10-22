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
import grails.gorm.tests.HibernateGormDatastoreSpec
import org.grails.datastore.mapping.query.Restrictions

/**
 * {@code createAlias(path, alias, joinType, withClause)} restricts the join itself ({@code join ... on ...}), as
 * Hibernate 5's {@code createAlias} with a with-clause did, so a left join keeps the rows whose association does
 * not match the clause.
 */
class CreateAliasWithClauseSpec extends HibernateGormDatastoreSpec {

    static final int LEFT = 1

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    void "a with-clause closure restricts the joined collection elements and keeps the other rows"() {
        when:
        List rows = AliasTicket.createCriteria().list {
            createAlias('links', 'l', LEFT) {
                eq('l.name', 'L1')
            }
            projections {
                property('code')
                property('l.name')
            }
        }

        then: 'T1 is joined with L1 only, all other tickets are kept without a link'
        rows.collect { it as List }.sort { it[0] } == [['T1', 'L1'], ['T2', null], ['T3', null], ['T4', null], ['T5', null]]
    }

    void "a with-clause criterion applies to the join and not to the results"() {
        when:
        List<AliasTicket> withoutC1 = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT, Restrictions.eq('c.name', 'C1'))
            isNull('c.id')
        }

        then: 'only T1 has a customer joined, every other ticket has no joined customer'
        withoutC1*.code.sort() == ['T2', 'T3', 'T4', 'T5']
    }

    void "an INNER with-clause alias restricts the results to rows whose association matches the clause"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('links', 'l', JoinType.INNER) {
                or {
                    eq('l.name', 'L2')
                    eq('l.name', 'L3')
                }
            }
        }

        then:
        results*.code.sort() == ['T1', 'T3']
    }

    void "a with-clause can be combined with an alias path"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            createAlias('c.mandant', 'cm', LEFT) {
                eq('cm.active', true)
            }
            isNotNull('cm.id')
        }

        then:
        results*.code == ['T1']
    }

    void "a detached criteria accepts with-clause aliases"() {
        when:
        DetachedCriteria<AliasTicket> criteria = new DetachedCriteria<>(AliasTicket).build {
            createAlias('customer', 'c', LEFT, Restrictions.eq('c.name', 'C1'))
            isNull('c.id')
        }

        then:
        criteria.list()*.code.sort() == ['T2', 'T3', 'T4', 'T5']
    }
}
