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

import jakarta.persistence.criteria.From
import jakarta.persistence.criteria.JoinType

import org.springframework.transaction.PlatformTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.query.Query
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings
import org.grails.orm.hibernate.query.CriteriaHandlerContext
import org.grails.orm.hibernate.query.CriteriaHandlers

/**
 * A bare association path in a subquery ({@code customer { … }}, {@code eq('customer', x)},
 * {@code isNull('customer')}, {@code CriteriaHandlerContext.getJoin('customer')}) refers to the subquery's own root,
 * as in Hibernate 5's criteria, even when the enclosing query joins the same association path, through
 * {@code createAlias('customer', 'c')} or through the implicit alias of {@code customer.name}. Only an alias created
 * with {@code createAlias} in the enclosing query, referenced by its alias name, correlates the subquery with it.
 *
 * <pre>
 * T1 -> C1
 * T2 -> C2
 * T3 -> no customer
 * T4 -> C3
 * T5 -> no customer
 * </pre>
 */
abstract class SubqueryAssociationPathSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore
    @Shared PlatformTransactionManager transactionManager

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [(Settings.SETTING_DB_CREATE): 'create-drop'] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(
                DatastoreUtils.createPropertyResolver(settings),
                SubqueryPathCustomer, SubqueryPathTicket
        )
        transactionManager = hibernateDatastore.getTransactionManager()
    }

    void cleanup() {
        CriteriaHandlers.clear()
    }

    void seed() {
        def c1 = new SubqueryPathCustomer(name: 'C1').save()
        def c2 = new SubqueryPathCustomer(name: 'C2').save()
        def c3 = new SubqueryPathCustomer(name: 'C3').save()
        new SubqueryPathTicket(code: 'T1', customer: c1).save()
        new SubqueryPathTicket(code: 'T2', customer: c2).save()
        new SubqueryPathTicket(code: 'T3').save()
        new SubqueryPathTicket(code: 'T4', customer: c3).save()
        new SubqueryPathTicket(code: 'T5').save(flush: true)
        hibernateDatastore.sessionFactory.currentSession.clear()
    }

    private static DetachedCriteria<SubqueryPathTicket> ticketsOfC2ByAssociationBlock() {
        new DetachedCriteria<>(SubqueryPathTicket).build {
            customer {
                eq('name', 'C2')
            }
        }
    }

    private static DetachedCriteria<SubqueryPathTicket> ticketsOfCustomer(SubqueryPathCustomer customer) {
        new DetachedCriteria<>(SubqueryPathTicket).build {
            eq('customer', customer)
        }
    }

    private static DetachedCriteria<SubqueryPathTicket> ticketsWithoutCustomer() {
        new DetachedCriteria<>(SubqueryPathTicket).build {
            isNull('customer')
        }
    }

    private static List<String> codes(List<SubqueryPathTicket> tickets) {
        tickets*.code.sort()
    }

    @Rollback
    void "the subqueries match every row when the enclosing query has no alias"() {
        given:
        seed()
        def c2 = SubqueryPathCustomer.findByName('C2')

        expect:
        codes(SubqueryPathTicket.createCriteria().list { exists(ticketsOfC2ByAssociationBlock()) }) == ['T1', 'T2', 'T3', 'T4', 'T5']
        codes(SubqueryPathTicket.createCriteria().list { exists(ticketsOfCustomer(c2)) }) == ['T1', 'T2', 'T3', 'T4', 'T5']
        codes(SubqueryPathTicket.createCriteria().list { exists(ticketsWithoutCustomer()) }) == ['T1', 'T2', 'T3', 'T4', 'T5']
    }

    @Rollback
    void "an association block of a subquery is not correlated with an alias of the same path of the enclosing query"() {
        given:
        seed()

        expect: 'INNER: the subquery matches every row, so only the inner join of c restricts the results'
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(ticketsOfC2ByAssociationBlock())
        }) == ['T1', 'T2', 'T4']

        and: 'LEFT: every row'
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            exists(ticketsOfC2ByAssociationBlock())
        }) == ['T1', 'T2', 'T3', 'T4', 'T5']

        and: 'notExists: the subquery finds T2 for every row'
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            notExists(ticketsOfC2ByAssociationBlock())
        }) == []
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            notExists(ticketsOfC2ByAssociationBlock())
        }) == []
    }

    @Rollback
    void "eq on an association of a subquery is not correlated with an alias of the same path of the enclosing query"() {
        given:
        seed()
        def c2 = SubqueryPathCustomer.findByName('C2')

        expect:
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(ticketsOfCustomer(c2))
        }) == ['T1', 'T2', 'T4']
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            exists(ticketsOfCustomer(c2))
        }) == ['T1', 'T2', 'T3', 'T4', 'T5']
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            notExists(ticketsOfCustomer(c2))
        }) == []
    }

    @Rollback
    void "isNull on an association of a subquery is not correlated with an alias of the same path of the enclosing query"() {
        given:
        seed()

        expect:
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(ticketsWithoutCustomer())
        }) == ['T1', 'T2', 'T4']
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            exists(ticketsWithoutCustomer())
        }) == ['T1', 'T2', 'T3', 'T4', 'T5']
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            notExists(ticketsWithoutCustomer())
        }) == []
    }

    @Rollback
    void "an association block of a subquery is not correlated with the implicit alias of the enclosing query"() {
        given:
        seed()

        expect: 'customer.name joins the implicit alias customer in the enclosing query only'
        codes(SubqueryPathTicket.createCriteria().list {
            'in'('customer.name', ['C1', 'C2'])
            exists(ticketsOfC2ByAssociationBlock())
        }) == ['T1', 'T2']
        codes(SubqueryPathTicket.createCriteria().list {
            'in'('customer.name', ['C1', 'C2'])
            notExists(ticketsOfC2ByAssociationBlock())
        }) == []
    }

    @Rollback
    void "the join of an association path a handler looks up in a subquery is the subquery's own"() {
        given: 'a handler that reuses the join of customer, or joins it'
        CriteriaHandlers.registerCriterionHandler(CustomerNameCriterion) { CriteriaHandlerContext context, CustomerNameCriterion criterion ->
            From<?, ?> customer = context.getJoin('customer') ?: context.root.join('customer')
            context.criteriaBuilder.equal(customer.get('name'), criterion.name)
        }
        seed()
        DetachedCriteria<SubqueryPathTicket> ticketsOfC2 = new DetachedCriteria<>(SubqueryPathTicket).build {
            add(new CustomerNameCriterion('C2'))
        }

        expect:
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(ticketsOfC2)
        }) == ['T1', 'T2', 'T4']
    }

    @Rollback
    void "an alias of the enclosing query referenced by its name still correlates the subquery"() {
        given:
        seed()
        DetachedCriteria<SubqueryPathTicket> correlated = new DetachedCriteria<>(SubqueryPathTicket).build {
            eq('c.name', 'C2')
        }
        DetachedCriteria<SubqueryPathTicket> ownAlias = new DetachedCriteria<>(SubqueryPathTicket).build {
            createAlias('customer', 'sc')
            eq('sc.name', 'C2')
        }

        expect: 'c of the enclosing query restricts the subquery to its row'
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(correlated)
        }) == ['T2']

        and: 'an alias of the subquery itself is not correlated'
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c')
            exists(ownAlias)
        }) == ['T1', 'T2', 'T4']
    }

    @Rollback
    void "an association block of the enclosing query itself still reuses the join of its alias"() {
        given:
        seed()

        expect: 'the LEFT join of c is reused, so T3 and T5 are kept by the or'
        codes(SubqueryPathTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            or {
                isNull('c.id')
                customer {
                    eq('name', 'C2')
                }
            }
        }) == ['T2', 'T3', 'T5']
    }
}

class CustomerNameCriterion implements Query.Criterion {
    final String name

    CustomerNameCriterion(String name) {
        this.name = name
    }
}

@Entity
class SubqueryPathCustomer {
    String name

    static mapping = {
        // the default (native) generator is not initialized on PostgreSQL, like in the other PostgreSQL specs
        id generator: 'identity'
    }
}

@Entity
class SubqueryPathTicket {
    String code
    SubqueryPathCustomer customer

    static constraints = {
        customer nullable: true
    }

    static mapping = {
        id generator: 'identity'
    }
}
