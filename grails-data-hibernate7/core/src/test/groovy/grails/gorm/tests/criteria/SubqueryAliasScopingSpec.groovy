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

import grails.gorm.DetachedCriteria
import grails.gorm.tests.HibernateGormDatastoreSpec

/**
 * The aliases of a subquery are scoped to the subquery: an alias or association path of the subquery that has
 * the same name as one of the enclosing query is joined in the subquery instead of being correlated with the
 * enclosing query's join.
 */
class SubqueryAliasScopingSpec extends HibernateGormDatastoreSpec {

    static final int LEFT = 1

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    void "a subquery alias named like an alias of the enclosing query joins its own association"() {
        given: 'the customers whose mandant is M1, with the mandant aliased as c'
        DetachedCriteria<AliasCustomer> customersOfM1 = new DetachedCriteria<>(AliasCustomer).build {
            createAlias('mandant', 'c', LEFT)
            eq('c.name', 'M1')
            projections {
                property('id')
            }
        }

        when: 'the enclosing query aliases its customer as c as well'
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
            'in'('c.id', customersOfM1)
        }

        then:
        results*.code == ['T1']
    }

    void "a same-entity id subquery with its own aliases is not correlated with the enclosing aliases"() {
        given: 'the tickets visible for an active or missing mandant, as an id subquery with its own aliases'
        DetachedCriteria<AliasTicket> visibleIds = new DetachedCriteria<>(AliasTicket).build {
            createAlias('customer', 'c', LEFT)
            createAlias('c.mandant', 'cm', LEFT)
            or {
                isNull('cm.id')
                eq('cm.active', true)
            }
            projections {
                distinct('id')
            }
        }

        when: 'the enclosing query uses the same alias names'
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            'in'('id', visibleIds)
        }

        then:
        results*.code.sort() == ['T1', 'T3', 'T4', 'T5']
    }

    void "an association path of a subquery is not correlated with the enclosing alias of the same path"() {
        given: 'an uncorrelated subquery that finds T2'
        DetachedCriteria<AliasTicket> ticketsOfC2 = new DetachedCriteria<>(AliasTicket).build {
            eq('customer.name', 'C2')
        }

        when: 'the enclosing query joins the same association path under an alias'
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(ticketsOfC2)
        }

        then: 'the subquery matches for every row, so only the inner join of c restricts the results'
        results*.code.sort() == ['T1', 'T2', 'T4']
    }

    void "a subquery can still refer to an alias of the enclosing query that it does not define itself"() {
        given:
        DetachedCriteria<AliasCustomer> sameNamedCustomer = new DetachedCriteria<>(AliasCustomer).build {
            eqProperty('name', 'c.name')
            projections {
                property('id')
            }
        }

        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
            'in'('c.id', sameNamedCustomer)
        }

        then:
        results*.code.sort() == ['T1', 'T2', 'T4']
    }
}
