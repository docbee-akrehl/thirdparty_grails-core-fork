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

import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * {@code listDistinct} returns each root entity once, as Hibernate 5's {@code DISTINCT_ROOT_ENTITY} did: the SQL
 * query is not {@code DISTINCT}, so it may be ordered by a column of a joined alias, and the duplicates a join of
 * a collection produces are removed from the results, keeping the order of the first occurrence of each entity.
 * {@code maxResults} / {@code firstResult} apply to the SQL rows, before the duplicates are removed, as in
 * Hibernate 5. Distinct projections still render {@code DISTINCT}.
 *
 * <pre>
 * A -> workers b, y
 * B -> workers z
 * C -> no workers
 * D -> workers a
 * </pre>
 *
 * Ordered by the worker name descending (nulls last) the SQL rows are B(z), A(y), A(b), D(a), C(null).
 */
abstract class ListDistinctRootEntitySpec extends Specification {

    @Shared SqlCapture sqlCapture = new SqlCapture()
    @Shared @AutoCleanup HibernateDatastore hibernateDatastore
    @Shared PlatformTransactionManager transactionManager

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [
                (Settings.SETTING_DB_CREATE)                    : 'create-drop',
                'hibernate.session_factory.statement_inspector': sqlCapture
        ] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(
                DatastoreUtils.createPropertyResolver(settings),
                ListDistinctItem, ListDistinctWorker
        )
        transactionManager = hibernateDatastore.getTransactionManager()
    }

    void seed() {
        new ListDistinctItem(name: 'A')
                .addToWorkers(name: 'b')
                .addToWorkers(name: 'y')
                .save()
        new ListDistinctItem(name: 'B').addToWorkers(name: 'z').save()
        new ListDistinctItem(name: 'C').save()
        new ListDistinctItem(name: 'D').addToWorkers(name: 'a').save(flush: true)
        hibernateDatastore.sessionFactory.currentSession.clear()
        sqlCapture.clear()
    }

    String itemQuerySql() {
        List<String> selects = sqlCapture.statements.findAll { it.toLowerCase().contains('from list_distinct_item') }
        assert selects.size() == 1
        selects[0].toLowerCase()
    }

    @Rollback
    void "listDistinct ordered by a column of a LEFT alias over a collection returns each root entity once in that order"() {
        given:
        seed()

        when:
        List<ListDistinctItem> results = ListDistinctItem.createCriteria().listDistinct {
            createAlias('workers', 'w', JoinType.LEFT)
            order('w.name', 'desc', nulls: 'last')
        }

        then: 'the first occurrence of each item decides its position, the item without workers is kept'
        results*.name == ['B', 'A', 'D', 'C']

        and: 'every result is a distinct instance'
        results.unique(false) { System.identityHashCode(it) }.size() == 4

        and: 'the SQL query is not DISTINCT'
        itemQuerySql().contains('left join list_distinct_worker')
        !itemQuerySql().contains('select distinct')
    }

    @Rollback
    void "listDistinct ordered by a column of a LEFT alias with the database's default null precedence"() {
        given:
        seed()

        when: 'the query of the Hibernate 5 code that SELECT DISTINCT broke'
        List<ListDistinctItem> results = ListDistinctItem.createCriteria().listDistinct {
            createAlias('workers', 'w', JoinType.LEFT)
            order('w.name', 'desc')
        }

        then: 'each item once; where the item without workers sorts depends on the database'
        results.size() == 4
        results*.name.findAll { it != 'C' } == ['B', 'A', 'D']
        results*.name.contains('C')
    }

    @Rollback
    void "listDistinct with a restriction on an alias over a collection returns each matching root entity once"() {
        given:
        seed()

        when:
        List<ListDistinctItem> results = ListDistinctItem.createCriteria().listDistinct {
            createAlias('workers', 'w', JoinType.LEFT)
            or {
                isNull('w.id')
                gt('w.name', 'a')
            }
            order('name')
        }

        then:
        results*.name == ['A', 'B', 'C']
    }

    @Rollback
    void "maxResults and firstResult of listDistinct apply to the SQL rows before the duplicates are removed"() {
        given:
        seed()

        when: 'the first three rows are B(z), A(y), A(b)'
        List<ListDistinctItem> firstRows = ListDistinctItem.createCriteria().listDistinct {
            createAlias('workers', 'w', JoinType.LEFT)
            order('w.name', 'desc', nulls: 'last')
            maxResults(3)
        }

        then: 'two items, as in Hibernate 5'
        firstRows*.name == ['B', 'A']

        when: 'rows two to four are A(y), A(b), D(a)'
        List<ListDistinctItem> laterRows = ListDistinctItem.createCriteria().listDistinct {
            createAlias('workers', 'w', JoinType.LEFT)
            order('w.name', 'desc', nulls: 'last')
            firstResult(1)
            maxResults(3)
        }

        then:
        laterRows*.name == ['A', 'D']
    }

    @Rollback
    void "a distinct root entity projection without a property behaves like listDistinct"() {
        given:
        seed()

        when: 'the criteria builder'
        List<ListDistinctItem> built = ListDistinctItem.createCriteria().list {
            createAlias('workers', 'w', JoinType.LEFT)
            projections {
                distinct()
            }
            order('w.name', 'desc', nulls: 'last')
        }

        then:
        built*.name == ['B', 'A', 'D', 'C']
        !itemQuerySql().contains('select distinct')

        when: 'a detached criteria'
        sqlCapture.clear()
        List<ListDistinctItem> detached = new DetachedCriteria<>(ListDistinctItem).build {
            createAlias('workers', 'w', JoinType.LEFT)
            projections {
                distinct()
            }
            order('w.name', 'desc', nulls: 'last')
        }.list()

        then:
        detached*.name == ['B', 'A', 'D', 'C']
        !itemQuerySql().contains('select distinct')
    }

    @Rollback
    void "distinct property projections and countDistinct still render DISTINCT"() {
        given:
        seed()

        when:
        List<String> names = ListDistinctItem.createCriteria().list {
            createAlias('workers', 'w', JoinType.LEFT)
            projections {
                distinct('name')
            }
            order('name')
        }

        then:
        names == ['A', 'B', 'C', 'D']
        itemQuerySql().contains('select distinct')

        when:
        sqlCapture.clear()
        Long count = ListDistinctItem.createCriteria().get {
            createAlias('workers', 'w', JoinType.LEFT)
            projections {
                countDistinct('id')
            }
        } as Long

        then:
        count == 4L
        itemQuerySql().contains('count(distinct')

        when: 'listDistinct with a property projection selects the distinct values'
        sqlCapture.clear()
        List<String> projected = ListDistinctItem.createCriteria().listDistinct {
            createAlias('workers', 'w', JoinType.LEFT)
            projections {
                property('name')
            }
            order('name')
        }

        then:
        projected == ['A', 'B', 'C', 'D']
        itemQuerySql().contains('select distinct')
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
class ListDistinctItem {
    String name

    static hasMany = [workers: ListDistinctWorker]

    static mapping = {
        // the default (native) generator is not initialized on PostgreSQL, like in the other PostgreSQL specs
        id generator: 'identity'
    }
}

@Entity
class ListDistinctWorker {
    String name

    static belongsTo = [item: ListDistinctItem]

    static mapping = {
        id generator: 'identity'
    }
}
