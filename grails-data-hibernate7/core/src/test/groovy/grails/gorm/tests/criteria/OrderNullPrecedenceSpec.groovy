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

import jakarta.persistence.criteria.Nulls

import grails.gorm.DetachedCriteria
import grails.gorm.tests.HibernateGormDatastoreSpec
import org.grails.orm.hibernate.query.HibernateOrder

/**
 * Orders can place {@code null} values first or last ({@code NULLS FIRST} / {@code NULLS LAST}), through the
 * criteria DSL ({@code order('c.name', 'asc', nulls: 'last')}) or a {@link HibernateOrder}.
 */
class OrderNullPrecedenceSpec extends HibernateGormDatastoreSpec {

    static final int LEFT = 1

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    void "nulls last on an ascending order of an alias property"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            order('c.name', 'asc', nulls: 'last')
            order('code')
        }

        then:
        results*.code == ['T1', 'T2', 'T4', 'T3', 'T5']
    }

    void "nulls first on a descending order"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            order('prio', 'desc', nulls: 'first')
            order('code')
        }

        then:
        results*.code == ['T3', 'T5', 'T4', 'T2', 'T1']
    }

    void "nulls last on a descending order"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            order('prio', 'desc', nulls: 'last')
            order('code')
        }

        then:
        results*.code == ['T4', 'T2', 'T1', 'T3', 'T5']
    }

    void "the direction defaults to ascending"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            order('prio', nulls: Nulls.LAST)
            order('code')
        }

        then:
        results*.code == ['T1', 'T2', 'T4', 'T3', 'T5']
    }

    void "the null precedence combines with ignoreCase"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            order('c.name', 'desc', nulls: 'last', ignoreCase: true)
            order('code')
        }

        then:
        results*.code == ['T4', 'T2', 'T1', 'T3', 'T5']
    }

    void "a HibernateOrder places nulls first on an alias property"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            order(HibernateOrder.asc('c.name').nullsFirst())
            order('code')
        }

        then:
        results*.code == ['T3', 'T5', 'T1', 'T2', 'T4']
    }

    void "a detached criteria orders with a null precedence"() {
        when:
        DetachedCriteria<AliasTicket> criteria = new DetachedCriteria<>(AliasTicket).build {
            createAlias('customer', 'c', LEFT)
            order('c.name', 'asc', nulls: 'last')
            order(HibernateOrder.asc('code'))
        }

        then:
        criteria.list()*.code == ['T1', 'T2', 'T4', 'T3', 'T5']
    }

    void "an unknown null precedence is rejected"() {
        when:
        AliasTicket.createCriteria().list {
            order('prio', 'asc', nulls: 'middle')
        }

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('middle')
    }
}
