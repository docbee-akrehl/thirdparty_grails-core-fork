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

import org.springframework.transaction.PlatformTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings
import org.grails.orm.hibernate.query.NotEqualsLegacy

/**
 * {@code neLegacy(property, value)} renders {@code x <> ?}, as Hibernate 5's {@code Restrictions.ne} did: by SQL's
 * three-valued logic it excludes the rows where the column is {@code NULL}. Plain {@code ne} keeps the Hibernate 7
 * semantics {@code (x <> ? or x is null)}.
 *
 * <pre>
 * I1 flag=true  -> owner O1
 * I2 flag=false -> owner O2
 * I3 flag=null  -> no owner
 * </pre>
 */
class NeLegacySpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore = new HibernateDatastore(
            DatastoreUtils.createPropertyResolver((Settings.SETTING_DB_CREATE): 'create-drop'),
            NeLegacyOwner, NeLegacyItem
    )
    @Shared PlatformTransactionManager transactionManager = hibernateDatastore.getTransactionManager()

    Map<String, Object> seed() {
        def o1 = new NeLegacyOwner(name: 'O1').save()
        def o2 = new NeLegacyOwner(name: 'O2').save()
        new NeLegacyItem(name: 'I1', flag: true, owner: o1).save()
        new NeLegacyItem(name: 'I2', flag: false, owner: o2).save()
        new NeLegacyItem(name: 'I3').save(flush: true)
        [o1: o1.id, o2: o2.id]
    }

    List<String> names(Map<String, Object> ids, Closure criteria) {
        Closure c = criteria.rehydrate(null, this, this)
        NeLegacyItem.createCriteria().list {
            c.delegate = delegate
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c.call(ids)
            order('name')
        }*.name
    }

    @Rollback
    @Unroll
    void "#description in a criteria query excludes the rows where the column is null"() {
        given:
        Map<String, Object> ids = seed()

        expect:
        names(ids, criteria) == expected

        where:
        description                              | expected     | criteria
        "neLegacy('flag', true)"                 | ['I2']       | { neLegacy('flag', true) }
        "neLegacy('name', 'I1')"                 | ['I2', 'I3'] | { neLegacy('name', 'I1') }
        "neLegacy('owner.id', o1)"               | ['I2']       | { m -> neLegacy('owner.id', m.o1) }
        "neLegacy('flag', null)"                 | ['I1', 'I2'] | { neLegacy('flag', null) }
        "or { neLegacy('flag', true); isNull }"  | ['I2', 'I3'] | { or { neLegacy('flag', true); isNull('flag') } }
        "not { neLegacy('flag', true) }"         | ['I1']       | { not { neLegacy('flag', true) } }
        "owner { neLegacy('name', 'O1') }"       | ['I2']       | { owner { neLegacy('name', 'O1') } }
        "add(new NotEqualsLegacy('flag', true))" | ['I2']       | { add(new NotEqualsLegacy('flag', true)) }
    }

    @Rollback
    void "plain ne keeps including the rows where the column is null"() {
        given:
        Map<String, Object> ids = seed()

        expect:
        names(ids) { ne('flag', true) } == ['I2', 'I3']
        names(ids) { m -> ne('owner.id', m.o1) } == ['I2', 'I3']
    }

    @Rollback
    void "neLegacy against an id subquery excludes the rows where the column is null"() {
        given:
        seed()
        DetachedCriteria<NeLegacyOwner> ownerO1 = new DetachedCriteria<>(NeLegacyOwner).build {
            eq('name', 'O1')
            projections {
                id()
            }
        }

        expect:
        names([:]) { neLegacy('owner', ownerO1) } == ['I2']
        names([:]) { ne('owner', ownerO1) } == ['I2', 'I3']
    }

    @Rollback
    void "neLegacy in a detached criteria excludes the rows where the column is null"() {
        given:
        Map<String, Object> ids = seed()

        expect:
        new DetachedCriteria<>(NeLegacyItem).build {
            neLegacy('owner.id', ids.o1)
        }.list(sort: 'name')*.name == ['I2']
        new DetachedCriteria<>(NeLegacyItem).build {
            or {
                neLegacy('flag', true)
                eq('name', 'I1')
            }
        }.list(sort: 'name')*.name == ['I1', 'I2']
    }
}

@Entity
class NeLegacyOwner {
    String name
}

@Entity
class NeLegacyItem {
    String name
    Boolean flag
    NeLegacyOwner owner

    static constraints = {
        flag nullable: true
        owner nullable: true
    }
}
