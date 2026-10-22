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

import org.hibernate.resource.jdbc.spi.StatementInspector
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

/**
 * The identifier of a to-one association ({@code category.id}, also with a mapped identifier name such as
 * {@code owner.code}, also through an alias such as {@code c.mandant.id}) is its foreign key column, as in
 * Hibernate 5's criteria: it is compared without joining the associated table, so rows without the association
 * are not dropped.
 *
 * <pre>
 * I1 flag=false -> category A (mandant M1), owner O1
 * I2 flag=true  -> category B (no mandant), no owner
 * I3 flag=false -> no category, owner O2
 * I4 flag=true  -> no category, no owner
 * </pre>
 */
class AssociationIdForeignKeySpec extends Specification {

    @Shared SqlCapture sqlCapture = new SqlCapture()

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore = new HibernateDatastore(
            DatastoreUtils.createPropertyResolver(
                    (Settings.SETTING_DB_CREATE): 'create-drop',
                    'hibernate.session_factory.statement_inspector': sqlCapture
            ),
            AssocIdMandant, AssocIdCategory, AssocIdOwner, AssocIdItem
    )
    @Shared PlatformTransactionManager transactionManager = hibernateDatastore.getTransactionManager()

    Map<String, Object> seed() {
        def m1 = new AssocIdMandant(name: 'M1').save()
        def a = new AssocIdCategory(name: 'A', mandant: m1).save()
        def b = new AssocIdCategory(name: 'B').save()
        def o1 = new AssocIdOwner(code: 'O1', label: 'first').save()
        def o2 = new AssocIdOwner(code: 'O2', label: 'second').save()
        new AssocIdItem(name: 'I1', flag: false, category: a, owner: o1).save()
        new AssocIdItem(name: 'I2', flag: true, category: b).save()
        new AssocIdItem(name: 'I3', flag: false, owner: o2).save()
        new AssocIdItem(name: 'I4', flag: true).save(flush: true)
        sqlCapture.clear()
        [m1: m1.id, a: a.id, b: b.id]
    }

    List<String> names(Map<String, Object> ids, Closure criteria) {
        Closure c = criteria.rehydrate(null, this, this)
        AssocIdItem.createCriteria().list {
            c.delegate = delegate
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c.call(ids)
            order('name')
        }*.name
    }

    String itemQuerySql() {
        List<String> selects = sqlCapture.statements.findAll { it.toLowerCase().contains('from assoc_id_item') }
        assert selects.size() == 1
        selects[0].toLowerCase()
    }

    @Rollback
    @Unroll
    void "#description resolves the association identifier to the foreign key column"() {
        given:
        Map<String, Object> ids = seed()

        when:
        List<String> result = names(ids, criteria)

        then:
        result == expected

        and: 'the associated tables are not joined'
        !itemQuerySql().contains('assoc_id_category')
        !itemQuerySql().contains('assoc_id_owner')

        where:
        description                       | expected                 | criteria
        "isNull('category.id')"           | ['I3', 'I4']             | { isNull('category.id') }
        "isNotNull('category.id')"        | ['I1', 'I2']             | { isNotNull('category.id') }
        "eq('category.id')"               | ['I1']                   | { m -> eq('category.id', m.a) }
        "ne('category.id')"               | ['I2', 'I3', 'I4']       | { m -> ne('category.id', m.a) }
        "in('category.id')"               | ['I1', 'I2']             | { m -> 'in'('category.id', [m.a, m.b]) }
        "between('category.id')"          | ['I1', 'I2']             | { m -> between('category.id', m.a, m.b) }
        "ge('category.id')"               | ['I2']                   | { m -> ge('category.id', m.b) }
        "or { eq(flag); eq(category.id) }" | ['I1', 'I2', 'I4']      | { m -> or { eq('flag', true); eq('category.id', m.a) } }
        "or { isNull(category.id); eq }"  | ['I1', 'I3', 'I4']       | { m -> or { isNull('category.id'); eq('category.id', m.a) } }
        "not { isNotNull(category.id) }"  | ['I3', 'I4']             | { not { isNotNull('category.id') } }
        "and { isNull(category.id) }"     | ['I3']                   | { and { isNull('category.id'); eq('flag', false) } }
        "isNull('owner.code') (mapped id name)" | ['I2', 'I4']       | { isNull('owner.code') }
        "eq('owner.code') (mapped id name)" | ['I1']                 | { eq('owner.code', 'O1') }
        "ne('owner.code') (mapped id name)" | ['I2', 'I3', 'I4']     | { ne('owner.code', 'O1') }
        "isNull('owner.id') (id of a mapped id name)" | ['I2', 'I4'] | { isNull('owner.id') }
        "isNull on two associations"      | ['I4']                   | { isNull('category.id'); isNull('owner.code') }
    }

    @Rollback
    void "a DetachedCriteria and a DetachedCriteria subquery resolve the association identifier to the foreign key column"() {
        given:
        Map<String, Object> ids = seed()

        when: 'the detached criteria is listed itself'
        List<String> listed = new DetachedCriteria<>(AssocIdItem).build {
            or {
                isNull('category.id')
                eq('category.id', ids.a)
            }
        }.list(sort: 'name')*.name

        then:
        listed == ['I1', 'I3', 'I4']
        !itemQuerySql().contains('assoc_id_category')

        when: 'it is used as an id subquery'
        sqlCapture.clear()
        DetachedCriteria<AssocIdItem> withoutCategory = new DetachedCriteria<>(AssocIdItem).build {
            isNull('category.id')
        }.id()
        List<String> outer = names(ids) { 'in'('id', withoutCategory) }

        then:
        outer == ['I3', 'I4']
        !itemQuerySql().contains('assoc_id_category')

        when: 'the outer query names the same association: the subquery still refers to its own root'
        sqlCapture.clear()
        List<String> combined = names(ids) { m ->
            or {
                eq('category.id', m.a)
                'in'('id', withoutCategory)
            }
        }

        then:
        combined == ['I1', 'I3', 'I4']
        !itemQuerySql().contains('assoc_id_category')
    }

    @Rollback
    void "the association identifier through an alias is the foreign key column of the alias' table"() {
        given:
        Map<String, Object> ids = seed()

        when:
        List<String> withoutMandant = names(ids) {
            createAlias('category', 'c', JoinType.LEFT)
            isNull('c.mandant.id')
        }

        then: 'items without a category have no mandant either (LEFT alias)'
        withoutMandant == ['I2', 'I3', 'I4']
        itemQuerySql().contains('left join assoc_id_category')
        !itemQuerySql().contains('assoc_id_mandant')

        when:
        sqlCapture.clear()
        List<String> withMandant = names(ids) { m ->
            createAlias('category', 'c', JoinType.LEFT)
            or {
                eq('c.mandant.id', m.m1)
                eq('flag', true)
            }
        }

        then:
        withMandant == ['I1', 'I2', 'I4']
        !itemQuerySql().contains('assoc_id_mandant')

        when: 'the identifier of the alias itself'
        sqlCapture.clear()
        List<String> aliasId = names(ids) {
            createAlias('category', 'c', JoinType.LEFT)
            isNull('c.id')
        }

        then:
        aliasId == ['I3', 'I4']
    }

    @Rollback
    void "projections and orders on the association identifier keep rows without the association"() {
        given:
        Map<String, Object> ids = seed()

        when:
        List<Object> projected = AssocIdItem.createCriteria().list {
            projections {
                property('category.id')
            }
            order('name')
        }

        then:
        projected == [ids.a, ids.b, null, null]
        !itemQuerySql().contains('assoc_id_category')

        when:
        sqlCapture.clear()
        List<String> ordered = AssocIdItem.createCriteria().list {
            order('category.id', 'desc')
            order('name')
        }*.name

        then:
        ordered.size() == 4
        ordered.findAll { it in ['I1', 'I2'] } == ['I2', 'I1']
        !itemQuerySql().contains('assoc_id_category')
    }

    @Rollback
    void "a property of the association other than its identifier still joins the association"() {
        given:
        Map<String, Object> ids = seed()

        when:
        List<String> result = names(ids) { eq('category.name', 'A') }

        then:
        result == ['I1']
        itemQuerySql().contains('join assoc_id_category')

        when:
        sqlCapture.clear()
        List<String> labelled = names(ids) { isNotNull('owner.label') }

        then:
        labelled == ['I1', 'I3']
        itemQuerySql().contains('join assoc_id_owner')

        when: 'the identifier next to another property of the same association'
        sqlCapture.clear()
        List<String> both = names(ids) { m ->
            eq('category.id', m.a)
            isNotNull('category.name')
        }

        then: 'the association is joined for the other property, the identifier is the foreign key column'
        both == ['I1']
        itemQuerySql().contains('join assoc_id_category')
        itemQuerySql().contains('.category_id=?')
    }

    /**
     * Captures SQL statements executed by Hibernate for inspection in tests.
     */
    static class SqlCapture implements StatementInspector {
        final List<String> statements = Collections.synchronizedList(new ArrayList<String>())

        @Override
        String inspect(String sql) {
            statements.add(sql)
            return sql
        }

        void clear() {
            statements.clear()
        }
    }
}

@Entity
class AssocIdMandant {
    String name
}

@Entity
class AssocIdCategory {
    String name
    AssocIdMandant mandant

    static constraints = {
        mandant nullable: true
    }
}

@Entity
class AssocIdOwner {
    String code
    String label

    static mapping = {
        id name: 'code', generator: 'assigned'
    }
}

@Entity
class AssocIdItem {
    String name
    Boolean flag
    AssocIdCategory category
    AssocIdOwner owner

    static constraints = {
        category nullable: true
        owner nullable: true
    }
}
