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

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Requires
import spock.lang.Shared

import grails.gorm.tests.HibernateGormDatastoreSpec
import grails.gorm.transactions.Rollback

/**
 * {@link UnidirectionalJoinTableCollectionsSpec} on PostgreSQL.
 */
@Testcontainers
@Requires({ HibernateGormDatastoreSpec.isDockerAvailable() })
class UnidirectionalJoinTableCollectionsPostgresSpec extends UnidirectionalJoinTableCollectionsSpec {

    @Shared PostgreSQLContainer postgres = new PostgreSQLContainer('postgres:16')

    @Override
    Map<String, Object> databaseSettings() {
        [
                'dataSource.url'            : postgres.jdbcUrl,
                'dataSource.driverClassName': postgres.driverClassName,
                'dataSource.username'       : postgres.username,
                'dataSource.password'       : postgres.password,
                'hibernate.dialect'         : 'org.hibernate.dialect.PostgreSQLDialect'
        ] as Map<String, Object>
    }

    @Rollback
    void "an element removed from a collection whose join table has the Hibernate 5 shape deletes only its join row"() {
        given: 'the join table as GORM for Hibernate 5 created it: element column nullable, no primary or unique key'
        String table = joinTable(UJTOneCollection, 'tags')
        List<String> constraints = hibernateDatastore.sessionFactory.currentSession.createNativeQuery(
                "select constraint_name from information_schema.table_constraints where table_name = :table " +
                        "and constraint_type in ('PRIMARY KEY', 'UNIQUE')", String)
                .setParameter('table', table).list()
        constraints.each { String name ->
            execute("alter table ${table} drop constraint ${name}")
        }
        elementColumns(UJTOneCollection, 'tags').each { String column ->
            execute("alter table ${table} alter column ${column} drop not null")
        }

        and:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def owner = new UJTOneCollection(name: 'one').addToTags(first).addToTags(second)
                .save(flush: true, failOnError: true)

        when:
        def loaded = reload(UJTOneCollection, owner.id)
        loaded.removeFromTags(loaded.tags.find { it.name == 'a' })
        loaded.save(flush: true, failOnError: true)

        then: 'the database rolls the schema change back with the test transaction'
        !constraints.empty
        count(table) == 1
        reload(UJTOneCollection, owner.id).tags*.name == ['b']
    }

    @Rollback
    void "the generated join table shared by two sets has a unique key per collection and no primary key"() {
        when:
        String table = joinTable(UJTSharedJoinTable, 'tags')
        String element = elementColumns(UJTSharedJoinTable, 'tags').first()
        List<Object[]> rows = hibernateDatastore.sessionFactory.currentSession.createNativeQuery(
                "select tc.constraint_name, tc.constraint_type, kcu.column_name " +
                        "from information_schema.table_constraints tc " +
                        "join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name " +
                        "and kcu.table_name = tc.table_name " +
                        "where tc.table_name = :table and tc.constraint_type in ('PRIMARY KEY', 'UNIQUE')",
                Object[]).setParameter('table', table).list()
        Map<String, List<Object[]>> byConstraint = rows.groupBy { Object[] row -> row[0] as String }
        List<Map<String, Object>> columns = hibernateDatastore.sessionFactory.currentSession.createNativeQuery(
                "select column_name, is_nullable from information_schema.columns where table_name = :table",
                Object[]).setParameter('table', table).list()
                .collect { Object[] row -> [name: row[0], nullable: row[1]] as Map<String, Object> }

        then:
        rows.every { Object[] row -> row[1] == 'UNIQUE' }
        byConstraint.values().collect { List<Object[]> key -> key.collect { it[2] as String } as Set } as Set == [
                [keyColumn(UJTSharedJoinTable, 'tags'), element] as Set,
                [keyColumn(UJTSharedJoinTable, 'otherTags'), element] as Set
        ] as Set
        columns.findAll { it.nullable == 'NO' }*.name == [element]
    }

    @Rollback
    void "an element removed from a collection in a shared join table with the Hibernate 5 shape deletes only its join row"() {
        given: 'the shared join table as GORM for Hibernate 5 created it: three nullable columns, no primary or unique key'
        String table = joinTable(UJTSharedJoinTable, 'tags')
        List<String> constraints = hibernateDatastore.sessionFactory.currentSession.createNativeQuery(
                "select constraint_name from information_schema.table_constraints where table_name = :table " +
                        "and constraint_type in ('PRIMARY KEY', 'UNIQUE')", String)
                .setParameter('table', table).list()
        constraints.each { String name ->
            execute("alter table ${table} drop constraint ${name}")
        }
        List<String> columns = hibernateDatastore.sessionFactory.currentSession.createNativeQuery(
                "select column_name from information_schema.columns where table_name = :table", String)
                .setParameter('table', table).list()
        columns.each { String column ->
            execute("alter table ${table} alter column ${column} drop not null")
        }

        and:
        def first = new UJTTag(name: 'a').save(flush: true, failOnError: true)
        def second = new UJTTag(name: 'b').save(flush: true, failOnError: true)
        def owner = new UJTSharedJoinTable(name: 'shared').addToTags(first).addToTags(second).addToOtherTags(first)
                .save(flush: true, failOnError: true)

        when:
        def loaded = reload(UJTSharedJoinTable, owner.id)
        loaded.removeFromTags(loaded.tags.find { it.name == 'a' })
        loaded.save(flush: true, failOnError: true)
        loaded = reload(UJTSharedJoinTable, owner.id)

        then: 'the database rolls the schema change back with the test transaction'
        constraints.size() == 2
        columns.size() == 3
        count(table) == 2
        loaded.tags*.name == ['b']
        loaded.otherTags*.name == ['a']
    }

    /** Runs a DDL statement built from the mapping and the database catalog, never from input. */
    private void execute(GString sql) {
        hibernateDatastore.sessionFactory.currentSession.createNativeMutationQuery(sql.toString()).executeUpdate()
    }
}
