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

/**
 * An entity with two or more unidirectional {@code hasMany} collections stores each of them in its own join table.
 * The key of such a collection must stay updatable: Hibernate writes no rows for a collection whose key is not
 * updatable, so the elements were silently lost as soon as the owner had a second unidirectional collection.
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
                UJTTag, UJTComponent, UJTOneCollection, UJTTwoCollections, UJTMappedJoinTables
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
    @PendingFeature(reason = 'open: the element column of the join table is nullable, so Hibernate 7 deletes the row ' +
            'of a removed element by the owner key alone, i.e. all rows of the owner (Hibernate 5 also matched the element)')
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

    private <T> T reload(Class<T> type, Serializable id) {
        hibernateDatastore.currentSession.flush()
        hibernateDatastore.currentSession.clear()
        type.get(id)
    }

    /** The join table Hibernate maps the collection to. */
    private String joinTable(Class<?> owner, String property) {
        hibernateDatastore.sessionFactory.mappingMetamodel
                .getCollectionDescriptor(owner.name + '.' + property).tableName
    }

    /** Counts the rows of a join table, whose name comes from this spec or its mapping, never from input. */
    private long count(String table) {
        (hibernateDatastore.sessionFactory.currentSession
                .createNativeQuery("select count(*) from ${table}".toString())
                .uniqueResult() as Number).longValue()
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
class UJTMappedJoinTables {
    String name

    static hasMany = [tags: UJTTag, components: UJTComponent]

    static mapping = {
        tags joinTable: [name: 'ujt_mapped_tags', key: 'mapped_id']
        components joinTable: [name: 'ujt_mapped_components', key: 'mapped_id']
    }
}
