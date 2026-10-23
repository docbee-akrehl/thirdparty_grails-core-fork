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
package grails.gorm.tests.insert

import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

import org.springframework.dao.DataIntegrityViolationException

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.annotation.Entity
import grails.validation.ValidationException
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * {@code save(insert: true)} and {@code insert()} force an SQL INSERT, as documented for {@code save} and as GORM for
 * Hibernate 5 (Grails 6) did ({@code performInsert}: {@code session.save(target)}).
 *
 * <p>A new instance with an assigned id whose row already exists is rejected by the database with a
 * {@link DataIntegrityViolationException} (the unique violation of the primary key) and the row stays as it was; it
 * is not merged into that row. Applications detect a duplicate this way, e.g. a token table whose primary key marks a
 * request as processed. {@code save()} without {@code insert} keeps the Hibernate 7 upsert (merge for a known id).
 */
abstract class SaveInsertForcesInsertSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [(Settings.SETTING_DB_CREATE): 'create-drop'] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(settings),
                InsertToken, InsertVersioned, InsertGenerated, InsertUser, InsertRole, InsertUserRole)
    }

    void setup() {
        InsertToken.VALIDATIONS.set(0)
    }

    void cleanup() {
        InsertToken.withNewTransaction {
            InsertUserRole.executeUpdate('delete from InsertUserRole')
            InsertUser.executeUpdate('delete from InsertUser')
            InsertRole.executeUpdate('delete from InsertRole')
            InsertToken.executeUpdate('delete from InsertToken')
            InsertVersioned.executeUpdate('delete from InsertVersioned')
            InsertGenerated.executeUpdate('delete from InsertGenerated')
        }
    }

    void "save(flush: true, insert: true) of a new instance with the id of an existing row is rejected and leaves the row unchanged"() {
        given:
        existingToken('t1', 'original')

        when:
        InsertToken.withNewTransaction {
            new InsertToken(token: 't1', name: 'duplicate').save(flush: true, insert: true)
        }

        then:
        DataIntegrityViolationException e = thrown()
        isUniqueViolation(e)
        tokenRows() == [['t1', 'original']]
    }

    void "insert(flush: true) of a new instance with the id of an existing row is rejected and leaves the row unchanged"() {
        given:
        existingToken('t1', 'original')

        when:
        InsertToken.withNewTransaction {
            new InsertToken(token: 't1', name: 'duplicate').insert(flush: true)
        }

        then:
        DataIntegrityViolationException e = thrown()
        isUniqueViolation(e)
        tokenRows() == [['t1', 'original']]
    }

    void "a duplicate caught inside the transaction is reported and the transaction still ends normally"() {
        given: 'the pattern that detects an already processed token by the primary key violation'
        existingToken('t1', 'original')

        expect:
        alreadyProcessed('t1')
        !alreadyProcessed('t2')
        alreadyProcessed('t2')
        tokenRows() == [['t1', 'original'], ['t2', 'name']]
    }

    void "save(insert: true) without flush is rejected when the transaction commits"() {
        given:
        existingToken('t1', 'original')

        when:
        InsertToken.withNewTransaction {
            new InsertToken(token: 't1', name: 'duplicate').save(insert: true)
        }

        then:
        DataIntegrityViolationException e = thrown()
        isUniqueViolation(e)
        tokenRows() == [['t1', 'original']]
    }

    void "a new instance with the id of an instance already in the session is rejected"() {
        given:
        existingToken('t1', 'original')

        when:
        InsertToken.withNewTransaction {
            InsertToken.get('t1')
            new InsertToken(token: 't1', name: 'duplicate').save(flush: true, insert: true)
        }

        then: 'Hibernate refuses a second instance for the same id (as Hibernate 5 did)'
        thrown(DataIntegrityViolationException)
        tokenRows() == [['t1', 'original']]
    }

    void "a detached instance is inserted, not merged into its row"() {
        given:
        InsertVersioned.withNewTransaction { new InsertVersioned(id: 'v1', name: 'original').save(flush: true, failOnError: true) }
        InsertVersioned detached = InsertVersioned.withNewTransaction { InsertVersioned.get('v1') }
        detached.name = 'changed'

        when:
        InsertVersioned.withNewTransaction { detached.insert(flush: true) }

        then:
        DataIntegrityViolationException e = thrown()
        isUniqueViolation(e)
        versionedRows() == [['v1', 'original', 0L]]
    }

    void "a new id is inserted by save(insert: true) with and without flush and by insert()"() {
        when:
        InsertToken.withNewTransaction { new InsertToken(token: 'n1', name: 'a').save(flush: true, insert: true) }
        InsertToken.withNewTransaction { new InsertToken(token: 'n2', name: 'b').insert(flush: true) }
        InsertToken.withNewTransaction { new InsertToken(token: 'n3', name: 'c').save(insert: true) }
        InsertVersioned.withNewTransaction { new InsertVersioned(id: 'v1', name: 'd').insert(flush: true) }
        Long generatedId = InsertGenerated.withNewTransaction { new InsertGenerated(name: 'e').insert(flush: true).id }

        then:
        tokenRows() == [['n1', 'a'], ['n2', 'b'], ['n3', 'c']]
        versionedRows() == [['v1', 'd', 0L]]
        generatedId != null
        InsertGenerated.withNewTransaction { InsertGenerated.get(generatedId)?.name } == 'e'
    }

    void "insert of the instance the session already manages writes its changes"() {
        given:
        existingToken('t1', 'original')

        when:
        InsertToken.withNewTransaction {
            InsertToken token = InsertToken.get('t1')
            token.name = 'changed'
            token.insert(flush: true)
        }

        then:
        tokenRows() == [['t1', 'changed']]
    }

    void "save() without insert still merges a new instance into the existing row of its id"() {
        given:
        existingToken('t1', 'original')

        when:
        InsertToken.withNewTransaction { new InsertToken(token: 't1', name: 'flushed').save(flush: true) }

        then:
        tokenRows() == [['t1', 'flushed']]

        when:
        InsertToken.withNewTransaction { new InsertToken(token: 't1', name: 'committed').save() }

        then:
        tokenRows() == [['t1', 'committed']]
    }

    void "save(insert: true) validates once and rejects an invalid instance"() {
        when: 'a valid instance saved without flush'
        InsertToken.withNewTransaction { new InsertToken(token: 'n1', name: 'valid').save(insert: true) }

        then: 'its validators ran once, the commit did not validate it again'
        InsertToken.VALIDATIONS.get() == 1
        tokenRows() == [['n1', 'valid']]

        when:
        InsertToken invalid = new InsertToken(token: 'i1', name: null)
        InsertToken result = InsertToken.withNewTransaction { invalid.save(flush: true, insert: true) }

        then:
        result == null
        invalid.hasErrors()
        tokenRows() == [['n1', 'valid']]

        when:
        InsertToken.withNewTransaction { new InsertToken(token: 'i2', name: null).insert(flush: true, failOnError: true) }

        then:
        thrown(ValidationException)
        tokenRows() == [['n1', 'valid']]
    }

    void "a composite id instance (UserRole-like) is inserted, and rejected when its row exists"() {
        given:
        List<Long> ids = InsertUser.withNewTransaction {
            InsertUser user = new InsertUser(name: 'u').save(flush: true, failOnError: true)
            InsertRole r1 = new InsertRole(name: 'r1').save(flush: true, failOnError: true)
            InsertRole r2 = new InsertRole(name: 'r2').save(flush: true, failOnError: true)
            InsertRole r3 = new InsertRole(name: 'r3').save(flush: true, failOnError: true)
            new InsertUserRole(user: user, role: r1, note: 'original').save(flush: true, failOnError: true)
            [user.id, r1.id, r2.id, r3.id]
        }

        when: 'the row of the key exists'
        InsertUserRole.withNewTransaction {
            new InsertUserRole(user: InsertUser.load(ids[0]), role: InsertRole.load(ids[1]), note: 'duplicate').save(flush: true, insert: true)
        }

        then:
        DataIntegrityViolationException e = thrown()
        isUniqueViolation(e)
        userRoleRows() == [['u', 'r1', 'original']]

        when: 'new keys, with and without flush'
        InsertUserRole.withNewTransaction {
            new InsertUserRole(user: InsertUser.load(ids[0]), role: InsertRole.load(ids[2]), note: 'flushed').save(flush: true, insert: true)
        }
        InsertUserRole.withNewTransaction {
            new InsertUserRole(user: InsertUser.load(ids[0]), role: InsertRole.load(ids[3]), note: 'committed').save(flush: false, insert: true)
        }

        then:
        userRoleRows() == [['u', 'r1', 'original'], ['u', 'r2', 'flushed'], ['u', 'r3', 'committed']]

        when: 'an existing key without flush'
        InsertUserRole.withNewTransaction {
            new InsertUserRole(user: InsertUser.load(ids[0]), role: InsertRole.load(ids[2]), note: 'duplicate').insert()
        }

        then:
        thrown(DataIntegrityViolationException)
        userRoleRows() == [['u', 'r1', 'original'], ['u', 'r2', 'flushed'], ['u', 'r3', 'committed']]
    }

    private static void existingToken(String token, String name) {
        InsertToken.withNewTransaction { new InsertToken(token: token, name: name).save(flush: true, failOnError: true) }
        InsertToken.VALIDATIONS.set(0)
    }

    private static boolean alreadyProcessed(String token) {
        InsertToken.withNewTransaction {
            try {
                new InsertToken(token: token, name: 'name').save(flush: true, insert: true)
            } catch (DataIntegrityViolationException ignored) {
                return true
            }
            return false
        }
    }

    private static boolean isUniqueViolation(Throwable e) {
        Throwable cause = e
        while (cause != null) {
            if (cause instanceof SQLException && ((SQLException) cause).SQLState == '23505') {
                return true
            }
            cause = cause.cause == cause ? null : cause.cause
        }
        return false
    }

    private static List<List<Object>> tokenRows() {
        InsertToken.withNewTransaction {
            InsertToken.executeQuery('select t.token, t.name from InsertToken t order by t.token').collect { ((Object[]) it).toList() }
        } as List<List<Object>>
    }

    private static List<List<Object>> versionedRows() {
        InsertVersioned.withNewTransaction {
            InsertVersioned.executeQuery('select v.id, v.name, v.version from InsertVersioned v order by v.id').collect { ((Object[]) it).toList() }
        } as List<List<Object>>
    }

    private static List<List<Object>> userRoleRows() {
        InsertUserRole.withNewTransaction {
            InsertUserRole.executeQuery('select ur.user.name, ur.role.name, ur.note from InsertUserRole ur order by ur.role.name')
                    .collect { ((Object[]) it).toList() }
        } as List<List<Object>>
    }
}

/**
 * An assigned, named id without version, like a token table.
 */
@Entity
class InsertToken {

    static final AtomicInteger VALIDATIONS = new AtomicInteger()

    String token
    String name

    static constraints = {
        name nullable: false, validator: { String val ->
            InsertToken.VALIDATIONS.incrementAndGet()
            true
        }
    }

    static mapping = {
        version false
        id generator: 'assigned', name: 'token'
    }
}

@Entity
class InsertVersioned {
    String id
    String name

    static mapping = {
        id generator: 'assigned'
    }
}

@Entity
class InsertGenerated {
    String name
}

@Entity
class InsertUser {
    String name
}

@Entity
class InsertRole {
    String name
}

@Entity
class InsertUserRole implements Serializable {

    InsertUser user
    InsertRole role
    String note

    static mapping = {
        id composite: ['user', 'role']
        version false
    }

    @Override
    boolean equals(Object other) {
        other instanceof InsertUserRole && ((InsertUserRole) other).user?.id == user?.id && ((InsertUserRole) other).role?.id == role?.id
    }

    @Override
    int hashCode() {
        Objects.hash(user?.id, role?.id)
    }
}
