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
package grails.gorm.tests.validation

import java.util.concurrent.atomic.AtomicInteger

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.annotation.Entity
import grails.validation.ValidationException
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * A validated {@code save()} that does not flush validates the instance exactly once, as GORM for Hibernate 5
 * (Grails 6) did: the flush that later writes the deferred insert or update does not validate it again.
 *
 * <p>Validators may depend on that. A validator that only applies to an existing row ({@code if (obj.id && !val)})
 * passes when {@code save()} validates a new instance, but fails at the flush, because {@code save()} has assigned
 * the id from the sequence by then. Without {@code failOnError} a failing flush-time validation vetoes the insert
 * silently, so the instance would be lost without any error.
 *
 * <p>{@code save(flush: true)} validates as before (once, during the save).
 */
abstract class SaveWithoutFlushValidatesOnceSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore

    /**
     * @return the settings of the database the specification runs on, in addition to the common ones
     */
    abstract Map<String, Object> databaseSettings()

    void setupSpec() {
        Map<String, Object> settings = [(Settings.SETTING_DB_CREATE): 'create-drop'] as Map<String, Object>
        settings.putAll(databaseSettings())
        hibernateDatastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(settings), ValidateOnceExample)
    }

    void setup() {
        ValidateOnceExample.VALIDATIONS.set(0)
    }

    void cleanup() {
        ValidateOnceExample.withNewTransaction {
            ValidateOnceExample.executeUpdate('delete from ValidateOnceExample')
        }
    }

    void "a new instance saved without flush is stored although a validator rejects it once it has an id"() {
        when: 'save() validates the new instance (no id yet) and the transaction commits'
        Long id = ValidateOnceExample.withNewTransaction {
            new ValidateOnceExample(name: 'new').save().id
        }

        then: 'the flush on commit does not validate it again, so the insert is not vetoed'
        id != null
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.count() } == 1
    }

    void "a new instance saved without flush is stored by an explicit flush in the same transaction"() {
        when:
        ValidateOnceExample example = ValidateOnceExample.withNewTransaction {
            ValidateOnceExample saved = new ValidateOnceExample(name: 'new').save()
            ValidateOnceExample.withSession { session -> session.flush() }
            saved
        }

        then:
        !example.hasErrors()
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.findByName('new') } != null
    }

    void "a validated save without flush runs the validators exactly once"() {
        when:
        ValidateOnceExample.withNewTransaction {
            new ValidateOnceExample(name: 'new', code: 'c').save()
            ValidateOnceExample.withSession { session -> session.flush() }
        }

        then:
        ValidateOnceExample.VALIDATIONS.get() == 1
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.count() } == 1
    }

    void "a validated save without flush of a changed persistent instance is not validated again on flush"() {
        given:
        Long id = ValidateOnceExample.withNewTransaction {
            new ValidateOnceExample(name: 'old', code: 'c').save(flush: true).id
        }
        ValidateOnceExample.VALIDATIONS.set(0)

        when:
        ValidateOnceExample.withNewTransaction {
            ValidateOnceExample example = ValidateOnceExample.get(id)
            example.name = 'changed'
            example.save()
            ValidateOnceExample.withSession { session -> session.flush() }
        }

        then:
        ValidateOnceExample.VALIDATIONS.get() == 1
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.get(id).name } == 'changed'
    }

    void "save without flush still rejects an invalid instance"() {
        when:
        ValidateOnceExample example = ValidateOnceExample.withNewTransaction {
            ValidateOnceExample invalid = new ValidateOnceExample(name: 'invalid')
            assert invalid.save() == null
            invalid
        }

        then:
        example.hasErrors()
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.count() } == 0
    }

    void "save with flush still validates and does not store an invalid instance"() {
        when:
        ValidateOnceExample example = ValidateOnceExample.withNewTransaction {
            ValidateOnceExample invalid = new ValidateOnceExample(name: 'invalid')
            assert invalid.save(flush: true) == null
            invalid
        }

        then:
        example.hasErrors()
        ValidateOnceExample.VALIDATIONS.get() == 1
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.count() } == 0
    }

    void "save with flush and failOnError still throws for an invalid instance"() {
        when:
        ValidateOnceExample.withNewTransaction {
            new ValidateOnceExample(name: 'invalid').save(flush: true, failOnError: true)
        }

        then:
        thrown(ValidationException)
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.count() } == 0
    }

    void "save with flush of a valid new instance validates it once and stores it"() {
        when:
        ValidateOnceExample.withNewTransaction {
            new ValidateOnceExample(name: 'new', code: 'c').save(flush: true, failOnError: true)
        }

        then:
        ValidateOnceExample.VALIDATIONS.get() == 1
        ValidateOnceExample.withNewTransaction { ValidateOnceExample.count() } == 1
    }
}

@Entity
class ValidateOnceExample {

    static final AtomicInteger VALIDATIONS = new AtomicInteger()

    String name
    String code

    static mapping = {
        id generator: 'sequence'
    }

    static constraints = {
        name validator: { String val, obj ->
            VALIDATIONS.incrementAndGet()
            if (val == 'invalid') {
                return ['invalidName']
            }
        }
        // like backend validators that only apply to an existing row
        code nullable: true, validator: { String val, obj ->
            if (obj.id && !val) {
                return ['requiredForExisting']
            }
        }
    }
}
