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
package grails.gorm.tests.junction

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * An empty disjunction ({@code or {}} without a criterion) restricts nothing, as with GORM for Hibernate 5 (Grails 6),
 * whose Hibernate {@code Junction} rendered a junction without conditions as {@code 1=1}. JPA defines an empty
 * disjunction as false, so it would select no row; a conditionally filled {@code or} block would then silently empty
 * the whole result.
 *
 * <p>A negation is the negated disjunction of its criteria, so {@code not { or {} }} and an empty {@code not {}} select
 * no row (Hibernate 5: {@code not (1=1)}). An empty conjunction stays true and a disjunction with criteria is unchanged.
 * Rows: a (category x, amount 1), b (category y, amount 2), c (no category, amount 3).
 */
abstract class EmptyDisjunctionSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [(Settings.SETTING_DB_CREATE): 'create-drop'] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(settings), JunctionItem)
        JunctionItem.withNewTransaction {
            new JunctionItem(name: 'a', category: 'x', amount: 1).save(flush: true, failOnError: true)
            new JunctionItem(name: 'b', category: 'y', amount: 2).save(flush: true, failOnError: true)
            new JunctionItem(name: 'c', category: null, amount: 3).save(flush: true, failOnError: true)
        }
    }

    void "an empty or block in a criteria query restricts nothing"() {
        expect:
        names { JunctionItem.createCriteria().list { or {} } } == ['a', 'b', 'c']
        JunctionItem.withNewTransaction { JunctionItem.createCriteria().count { or {} } } == 3
    }

    void "an empty or block next to other criteria leaves only the other criteria in effect"() {
        given: 'an or block filled from an empty list'
        List<String> categories = []

        expect:
        names { JunctionItem.createCriteria().list { or {}; eq('category', 'x') } } == ['a']
        names {
            JunctionItem.createCriteria().list {
                or { categories.each { eq('category', it) } }
                eq('category', 'x')
            }
        } == ['a']
        names { JunctionItem.createCriteria().list { or {}; isNull('category') } } == ['c']
        names { JunctionItem.createCriteria().list { and { or {} }; gt('amount', 1) } } == ['b', 'c']
    }

    void "an empty or block inside another or block makes that block true"() {
        expect:
        names { JunctionItem.createCriteria().list { or { eq('category', 'x'); or {} } } } == ['a', 'b', 'c']
    }

    void "a negated empty or block selects no row"() {
        expect:
        names { JunctionItem.createCriteria().list { not { or {} } } } == []
        names { JunctionItem.createCriteria().list { not { eq('category', 'x'); or {} } } } == []
        names { JunctionItem.createCriteria().list { not { not { or {} } } } } == ['a', 'b', 'c']
        names { new DetachedCriteria(JunctionItem).build { not { or {} } }.list() } == []
    }

    void "an empty not block selects no row"() {
        expect: 'Hibernate 5 rendered not (1=1); its criteria builder rejected an empty not block instead'
        names { new DetachedCriteria(JunctionItem).build { not {} }.list() } == []
        names { JunctionItem.createCriteria().list { not {} } } == []
    }

    void "an empty and block stays true and its negation false"() {
        expect:
        names { JunctionItem.createCriteria().list { and {} } } == ['a', 'b', 'c']
        names { JunctionItem.createCriteria().list { or { and {} } } } == ['a', 'b', 'c']
        names { JunctionItem.createCriteria().list { not { and {} } } } == []
        names { new DetachedCriteria(JunctionItem).build { and {} }.list() } == ['a', 'b', 'c']
    }

    void "an or block with criteria is unchanged"() {
        expect:
        names { JunctionItem.createCriteria().list { or { eq('category', 'x'); eq('category', 'y') } } } == ['a', 'b']
        names { JunctionItem.createCriteria().list { or { eq('category', 'x') } } } == ['a']
        names { JunctionItem.createCriteria().list { not { or { eq('category', 'x'); eq('category', 'y') } } } } == []
        names { JunctionItem.createCriteria().list { not { eq('category', 'x') } } } == ['b']
        names { new DetachedCriteria(JunctionItem).build { or { eq('category', 'x'); eq('category', 'y') } }.list() } == ['a', 'b']
    }

    void "an empty or block in a detached criteria or where query restricts nothing"() {
        expect:
        names { new DetachedCriteria(JunctionItem).build { or {} }.list() } == ['a', 'b', 'c']
        names { new DetachedCriteria(JunctionItem).build { or {}; eq('category', 'x') }.list() } == ['a']
        JunctionItem.withNewTransaction { new DetachedCriteria(JunctionItem).build { or {} }.count() } == 3
        names { JunctionItem.where { amount > 1 }.build { or {} }.list() } == ['b', 'c']
        names { JunctionItem.where { amount > 1 }.build { not { or {} } }.list() } == []
    }

    void "an empty or block in a subquery restricts nothing in the subquery"() {
        expect:
        names {
            new DetachedCriteria(JunctionItem).build {
                inList('id', new DetachedCriteria(JunctionItem).build { or {}; eq('category', 'y'); projections { property('id') } })
            }.list()
        } == ['b']
        names {
            new DetachedCriteria(JunctionItem).build {
                inList('id', new DetachedCriteria(JunctionItem).build { or {}; projections { property('id') } })
            }.list()
        } == ['a', 'b', 'c']
        names {
            new DetachedCriteria(JunctionItem).build {
                notIn('id', new DetachedCriteria(JunctionItem).build { or {}; projections { property('id') } })
            }.list()
        } == []
        names {
            new DetachedCriteria(JunctionItem).build {
                exists(new DetachedCriteria(JunctionItem).build { or {}; projections { property('id') } })
            }.list()
        } == ['a', 'b', 'c']
        names {
            JunctionItem.createCriteria().list {
                'in'('id', new DetachedCriteria(JunctionItem).build { or {}; eq('category', 'y'); projections { property('id') } })
            }
        } == ['b']
    }

    private static List<String> names(Closure<Collection<JunctionItem>> query) {
        JunctionItem.withNewTransaction {
            query().collect { it.name }.sort()
        }
    }
}

@Entity
class JunctionItem {
    String name
    String category
    Integer amount

    static constraints = {
        category nullable: true
    }
}
