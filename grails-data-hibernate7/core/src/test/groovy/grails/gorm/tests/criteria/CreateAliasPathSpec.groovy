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
 * {@code createAlias} accepts an association path that starts with another alias ({@code c.mandant}), at any
 * depth, or the full association path ({@code customer.mandant}), as Hibernate 5's criteria did.
 */
class CreateAliasPathSpec extends HibernateGormDatastoreSpec {

    static final int LEFT = 1

    void setupSpec() {
        manager.registerDomainClasses(AliasCriteriaModel.DOMAIN_CLASSES as Class[])
    }

    void setup() {
        AliasCriteriaModel.seed()
    }

    void "an alias path rooted at another alias joins from that alias and keeps the LEFT join type"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            createAlias('c.mandant', 'cm', LEFT)
            or {
                eq('cm.name', 'M2')
                eq('title', 'Z')
            }
        }

        then: 'T3 has no customer and T4 a customer without mandant, both kept by the left joins'
        results*.code.sort() == ['T2', 'T3', 'T4']
    }

    void "an alias path can be declared before the alias it starts with"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('c.mandant', 'cm', LEFT)
            createAlias('customer', 'c', LEFT)
            or {
                isNull('cm.id')
                eq('cm.active', true)
            }
        }

        then:
        results*.code.sort() == ['T1', 'T3', 'T4', 'T5']
    }

    void "alias paths can be nested at any depth"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            createAlias('c.mandant', 'cm', LEFT)
            createAlias('cm.parent', 'cmp', LEFT)
            or {
                eq('cmp.name', 'M1')
                eq('title', 'X')
            }
        }

        then:
        results*.code.sort() == ['T2', 'T5']
    }

    void "the full association path reuses the join of the alias created for its first segment"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            createAlias('customer.mandant', 'cm', LEFT)
            or {
                eq('cm.name', 'M2')
                eq('c.name', 'C1')
                eq('title', 'X')
            }
        }

        then:
        results*.code.sort() == ['T1', 'T2', 'T5']
    }

    void "a full association path without an alias for its first segment joins every segment with the alias' join type"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer.mandant', 'cm', LEFT)
            or {
                eq('cm.name', 'M1')
                eq('title', 'Z')
            }
        }

        then:
        results*.code.sort() == ['T1', 'T3', 'T4']
    }

    void "an INNER alias path restricts the results to rows having the whole path"() {
        when:
        List<AliasTicket> results = AliasTicket.createCriteria().list {
            createAlias('customer', 'c', LEFT)
            createAlias('c.mandant', 'cm')
        }

        then:
        results*.code.sort() == ['T1', 'T2']
    }

    void "a detached criteria accepts alias paths"() {
        when:
        DetachedCriteria<AliasTicket> criteria = new DetachedCriteria<>(AliasTicket).build {
            createAlias('customer', 'c', LEFT)
            createAlias('c.mandant', 'cm', LEFT)
            or {
                eq('cm.name', 'M2')
                eq('title', 'Z')
            }
        }

        then:
        criteria.list()*.code.sort() == ['T2', 'T3', 'T4']
    }
}
