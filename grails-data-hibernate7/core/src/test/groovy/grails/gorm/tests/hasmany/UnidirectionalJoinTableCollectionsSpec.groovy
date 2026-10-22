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
package grails.gorm.tests.hasmany

import org.springframework.transaction.PlatformTransactionManager
import spock.lang.AutoCleanup
import spock.lang.PendingFeature
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings
import org.hibernate.metamodel.mapping.SelectableMapping
import org.hibernate.persister.collection.AbstractCollectionPersister

/**
 * An entity with two or more unidirectional {@code hasMany} collections stores each of them in its own join table.
 * The key of such a collection must stay updatable: Hibernate writes no rows for a collection whose key is not
 * updatable, so the elements were silently lost as soon as the owner had a second unidirectional collection.
 * <p>
 * The element column of the join table of a set is not null: Hibernate deletes the row of a removed element by its
 * non-nullable element columns only, so with a nullable element column {@code removeFrom} deleted every row of the
 * owner. The same holds for collections of basic values ({@code hasMany = [ids: Long]}).
 */
abstract class UnidirectionalJoinTableCollectionsSpec extends Specification {

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
                UJTTag, UJTComponent, UJTOneCollection, UJTTwoCollections, UJTMappedJoinTables, UJTValues,
                UJTSharedJoinTable
        )
        transactionManager = hibernateDatastore.getTransactionManager()
    }

    @Rollback
    void "the join rows of the only unidirectional collection are stored"() {
        given:
        def tag = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def owner = new UJTOneCollection(name: 'one').addToTags(tag).save(flush: true, failOnError: true)

        expect:
        count(joinTable(UJTOneCollection, 'tags')) == 1
        reload(UJTOneCollection, owner.id).tags*.name == ['a']
    }

    @Rollback
    void "the join rows of each of two unidirectional collections are stored"() {
        given:
        def tag = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def component = new UJTComponent(name: 'c').save(flush: true, failOnError: true)
        def owner = new UJTTwoCollections(name: 'two').addToTags(tag).addToComponents(component)
                .save(flush: true, failOnError: true)

        expect:
        count(joinTable(UJTTwoCollections, 'tags')) == 1
        count(joinTable(UJTTwoCollections, 'components')) == 1

        when:
        def loaded = reload(UJTTwoCollections, owner.id)

        then:
        loaded.tags*.name == ['a']
        loaded.components*.name == ['c']
    }

    @Rollback
    void "the join rows of mapped join tables are stored, also when only one collection has elements"() {
        given:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def owner = new UJTMappedJoinTables(name: 'mapped').addToTags(first).addToTags(second)
                .save(flush: true, failOnError: true)

        expect:
        count('ujt_mapped_tags') == 2
        count('ujt_mapped_components') == 0
        reload(UJTMappedJoinTables, owner.id).tags*.name.sort() == ['a', 'b']
    }

    @Rollback
    void "elements added to a stored owner are stored"() {
        given:
        def owner = new UJTTwoCollections(name: 'two').save(flush: true, failOnError: true)
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def component = new UJTComponent(name: 'c').save(flush: true, failOnError: true)

        when:
        owner.addToTags(first).addToTags(second).addToComponents(component).save(flush: true, failOnError: true)

        then:
        count(joinTable(UJTTwoCollections, 'tags')) == 2
        count(joinTable(UJTTwoCollections, 'components')) == 1

        when:
        def loaded = reload(UJTTwoCollections, owner.id)
        loaded.components.clear()
        loaded.save(flush: true, failOnError: true)

        then: 'a cleared collection deletes its join rows'
        count(joinTable(UJTTwoCollections, 'tags')) == 2
        count(joinTable(UJTTwoCollections, 'components')) == 0
    }

    @Rollback
    void "an element removed from a collection deletes only its join row"() {
        given:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def owner = new UJTOneCollection(name: 'one').addToTags(first).addToTags(second)
                .save(flush: true, failOnError: true)

        when:
        def loaded = reload(UJTOneCollection, owner.id)
        loaded.removeFromTags(loaded.tags.find { it.name == 'a' })
        loaded.save(flush: true, failOnError: true)

        then:
        count(joinTable(UJTOneCollection, 'tags')) == 1
        reload(UJTOneCollection, owner.id).tags*.name == ['b']
    }

    @Rollback
    void "an element removed from one of two mapped collections deletes only its join row"() {
        given:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def component = new UJTComponent(name: 'c').save(flush: true, failOnError: true)
        def owner = new UJTMappedJoinTables(name: 'mapped').addToTags(first).addToTags(second)
                .addToComponents(component).save(flush: true, failOnError: true)

        when:
        def loaded = reload(UJTMappedJoinTables, owner.id)
        loaded.tags.remove(loaded.tags.find { it.name == 'b' })
        loaded.save(flush: true, failOnError: true)

        then:
        count('ujt_mapped_tags') == 1
        count('ujt_mapped_components') == 1

        when:
        loaded = reload(UJTMappedJoinTables, owner.id)

        then:
        loaded.tags*.name == ['a']
        loaded.components*.name == ['c']
    }

    @Rollback
    void "two collections of the same class in one shared join table keep their rows apart"() {
        given:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def owner = new UJTSharedJoinTable(name: 'shared').addToTags(first).addToTags(second).addToOtherTags(first)
                .save(flush: true, failOnError: true)
        String table = joinTable(UJTSharedJoinTable, 'tags')

        expect: 'issue 10811: each collection writes its own nullable key column next to the shared element column'
        joinTable(UJTSharedJoinTable, 'otherTags') == table
        count(table) == 3
        elementColumnsNullable(UJTSharedJoinTable, 'tags') == [true]

        when:
        def loaded = reload(UJTSharedJoinTable, owner.id)
        loaded.otherTags.clear()
        loaded.save(flush: true, failOnError: true)
        loaded = reload(UJTSharedJoinTable, owner.id)

        then:
        loaded.tags*.name.sort() == ['a', 'b']
        loaded.otherTags.empty
    }

    @Rollback
    @PendingFeature(reason = 'open: in a join table shared by two collections (issue 10811) the element column stays ' +
            'nullable - a not-null element column would make Hibernate create a primary key over the key column of ' +
            'one collection and the element column, so the rows of the other collection could not be stored')
    void "an element removed from a collection in a shared join table deletes only its join row"() {
        given:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def owner = new UJTSharedJoinTable(name: 'shared').addToTags(first).addToTags(second)
                .save(flush: true, failOnError: true)

        when:
        def loaded = reload(UJTSharedJoinTable, owner.id)
        loaded.removeFromTags(loaded.tags.find { it.name == 'a' })
        loaded.save(flush: true, failOnError: true)

        then:
        reload(UJTSharedJoinTable, owner.id).tags*.name == ['b']
    }

    @Rollback
    void "the element column of the join table of a set is not null"() {
        expect:
        elementColumnsNullable(UJTOneCollection, 'tags') == [false]
        elementColumnsNullable(UJTMappedJoinTables, 'components') == [false]
        elementColumnsNullable(UJTValues, 'numbers') == [false]
        elementColumnsNullable(UJTValues, 'labels') == [false]
    }

    @Rollback
    void "the rows of each of several collections of basic values are stored"() {
        given:
        def owner = new UJTValues(name: 'v').addToNumbers(1L).addToNumbers(2L).addToCodes(7L).addToLabels('x')
                .save(flush: true, failOnError: true)

        expect:
        count('ujt_values_numbers') == 2
        count('ujt_values_codes') == 1
        count(joinTable(UJTValues, 'labels')) == 1

        when:
        def loaded = reload(UJTValues, owner.id)

        then:
        loaded.numbers.sort() == [1L, 2L]
        loaded.codes as List == [7L]
        loaded.labels as List == ['x']
    }

    @Rollback
    void "a value removed from a collection of basic values deletes only its row"() {
        given:
        def owner = new UJTValues(name: 'v').addToNumbers(1L).addToNumbers(2L).addToCodes(7L).addToCodes(8L)
                .addToLabels('x').addToLabels('y').save(flush: true, failOnError: true)

        when:
        def loaded = reload(UJTValues, owner.id)
        loaded.removeFromNumbers(1L)
        loaded.labels.remove('y')
        loaded.save(flush: true, failOnError: true)

        then:
        count('ujt_values_numbers') == 1
        count('ujt_values_codes') == 2
        count(joinTable(UJTValues, 'labels')) == 1

        when:
        loaded = reload(UJTValues, owner.id)

        then:
        loaded.numbers as List == [2L]
        loaded.codes.sort() == [7L, 8L]
        loaded.labels as List == ['x']
    }

    protected <T> T reload(Class<T> type, Serializable id) {
        hibernateDatastore.currentSession.flush()
        hibernateDatastore.currentSession.clear()
        type.get(id)
    }

    /** The join table Hibernate maps the collection to. */
    protected String joinTable(Class<?> owner, String property) {
        hibernateDatastore.sessionFactory.mappingMetamodel
                .getCollectionDescriptor(owner.name + '.' + property).tableName
    }

    /** Counts the rows of a join table, whose name comes from this spec or its mapping, never from input. */
    protected long count(String table) {
        (hibernateDatastore.sessionFactory.currentSession
                .createNativeQuery("select count(*) from ${table}".toString())
                .uniqueResult() as Number).longValue()
    }

    /** The element columns of the join table Hibernate maps the collection to. */
    protected List<String> elementColumns(Class<?> owner, String property) {
        (hibernateDatastore.sessionFactory.mappingMetamodel
                .getCollectionDescriptor(owner.name + '.' + property) as AbstractCollectionPersister)
                .elementColumnNames as List<String>
    }

    /** Whether each element column of the collection is nullable in the Hibernate mapping. */
    private List<Boolean> elementColumnsNullable(Class<?> owner, String property) {
        List<Boolean> nullable = []
        hibernateDatastore.sessionFactory.mappingMetamodel.getCollectionDescriptor(owner.name + '.' + property)
                .attributeMapping.elementDescriptor.forEachSelectable { int index, SelectableMapping selectable ->
                    nullable << selectable.nullable
                }
        nullable
    }
}

@Entity
class UJTTag {
    String name
}

@Entity
class UJTComponent {
    String name
}

@Entity
class UJTOneCollection {
    String name

    static hasMany = [tags: UJTTag]
}

@Entity
class UJTTwoCollections {
    String name

    static hasMany = [tags: UJTTag, components: UJTComponent]
}

@Entity
class UJTValues {
    String name

    static hasMany = [numbers: Long, codes: Long, labels: String]

    static mapping = {
        numbers joinTable: [name: 'ujt_values_numbers', key: 'values_id', column: 'number_value']
        codes joinTable: [name: 'ujt_values_codes', key: 'values_id', column: 'code_value']
    }
}

@Entity
class UJTSharedJoinTable {
    String name

    static hasMany = [tags: UJTTag, otherTags: UJTTag]
}

@Entity
class UJTMappedJoinTables {
    String name

    static hasMany = [tags: UJTTag, components: UJTComponent]

    static mapping = {
        tags joinTable: [name: 'ujt_mapped_tags', key: 'mapped_id']
        components joinTable: [name: 'ujt_mapped_components', key: 'mapped_id']
    }
}
