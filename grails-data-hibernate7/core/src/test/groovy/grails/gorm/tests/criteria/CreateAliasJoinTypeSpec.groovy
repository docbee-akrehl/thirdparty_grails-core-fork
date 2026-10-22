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

/**
 * {@code createAlias(path, alias, joinType)} keeps the join type of the alias, also when properties are
 * referenced through the alias, and an explicit alias is joined even if nothing references it.
 */
class CreateAliasJoinTypeSpec extends HibernateGormDatastoreSpec {

    static final int LEFT = 1

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    void "a LEFT alias keeps rows without the association when one of its properties is referenced"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            or {
                eq('c.name', 'C1')
                eq('title', 'Z')
            }
        }

        then:
        results*.code.sort() == ['T1', 'T3', 'T4']
    }

    void "a LEFT alias given as a JPA join type keeps rows without the association"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', JoinType.LEFT)
            or {
                isNull('c.id')
                eq('c.name', 'C2')
            }
        }

        then:
        results*.code.sort() == ['T2', 'T3', 'T5']
    }

    void "the association path of an alias reuses the alias' join and its join type"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            or {
                eq('customer.name', 'C1')
                eq('title', 'Z')
            }
        }

        then:
        results*.code.sort() == ['T1', 'T3', 'T4']
    }

    void "an unreferenced INNER alias still restricts the results to rows having the association"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c')
        }

        then:
        results*.code.sort() == ['T1', 'T2', 'T4']
    }

    void "an unreferenced LEFT alias on a collection joins it and repeats the parent per element"() {
        when:
        List<String> codes = AliasTicket.createCriteria().list {
            createAlias('links', 'l', LEFT)
            projections {
                property('code')
            }
        }

        then:
        codes.sort() == ['T1', 'T1', 'T2', 'T3', 'T4', 'T5']
    }

    void "count and countDistinct honour the join type of the aliases"() {
        expect:
        AliasTicket.createCriteria().get {
            createAlias('customer', 'c', LEFT)
            createAlias('links', 'l', LEFT)
            or {
                eq('c.name', 'C1')
                eq('title', 'Z')
            }
            projections {
                countDistinct('id')
            }
        } == 3L

        and:
        AliasTicket.createCriteria().get {
            createAlias('customer', 'c', LEFT)
            createAlias('links', 'l', LEFT)
            or {
                eq('c.name', 'C1')
                eq('title', 'Z')
            }
            projections {
                count('id')
            }
        } == 4L
    }

    void "the total count of a paged list joins the aliases with their join type"() {
        when:
        PagedResultList<AliasTicket> page = AliasTicket.createCriteria().list(max: 1, offset: 0) {
            createAlias('customer', 'c', LEFT)
            or {
                eq('c.name', 'C1')
                eq('title', 'Z')
            }
            order('code')
        }

        then:
        page*.code == ['T1']
        page.totalCount == 3
    }

    void "a detached criteria alias keeps its join type"() {
        when:
        DetachedCriteria<AliasTicket> criteria = new DetachedCriteria<>(AliasTicket).build {
            createAlias('customer', 'c', LEFT)
            or {
                eq('c.name', 'C1')
                eq('title', 'Z')
            }
        }

        then:
        criteria.list()*.code.sort() == ['T1', 'T3', 'T4']
    }
}
