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

import spock.lang.AutoCleanup
import spock.lang.Issue
import spock.lang.Shared
import spock.lang.Specification

import grails.gorm.annotation.Entity
import grails.validation.ValidationException
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.cfg.Settings

/**
 * An instance saved with {@code validate: false} must not be validated again when the session is
 * flushed later in the same transaction (Grails 6 behaviour). Since docbee.9 the same holds for a
 * validated save without flush (validated once, by the save; see {@link SaveWithoutFlushValidatesOnceSpec}),
 * while an instance saved with {@code flush: true} is validated again on a later flush after a change.
 *
 * <p>The entity uses a sequence id so that the insert is deferred to the flush, as it is with the
 * default PostgreSQL mapping. With an identity id the insert would run inside {@code save()} itself
 * and hide the problem.
 */
@Issue('https://github.com/apache/grails-core/issues/15276')
class SaveWithValidateFalseFlushSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore hibernateDatastore = new HibernateDatastore([
            'dataSource.url'          : 'jdbc:h2:mem:saveWithValidateFalseFlushDB;LOCK_TIMEOUT=10000',
            (Settings.SETTING_DB_CREATE): 'create-drop',
            'grails.gorm.failOnError' : true
    ], ValidateFalseExample)

    void cleanup() {
        ValidateFalseExample.withNewTransaction {
            ValidateFalseExample.executeUpdate('delete from ValidateFalseExample')
        }
    }

    void "save with validate false persists a new instance when the transaction commits"() {
        when: 'an invalid instance is saved without validation and the transaction commits'
        ValidateFalseExample.withNewTransaction {
            new ValidateFalseExample(text: 'invalid').save(validate: false)
        }

        then: 'the flush on commit does not validate it again'
        noExceptionThrown()
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.count() } == 1
    }

    void "save with validate false followed by an explicit flush in the same transaction persists without ValidationException"() {
        when:
        ValidateFalseExample.withNewTransaction {
            new ValidateFalseExample(text: 'invalid').save(validate: false)
            ValidateFalseExample.withSession { session -> session.flush() }
        }

        then:
        noExceptionThrown()
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.findByText('invalid') } != null
    }

    void "save with validate false of a changed persistent instance persists the change on flush"() {
        given:
        Long id = ValidateFalseExample.withNewTransaction {
            new ValidateFalseExample(text: 'valid').save(flush: true).id
        }

        when:
        ValidateFalseExample.withNewTransaction {
            ValidateFalseExample example = ValidateFalseExample.get(id)
            example.text = 'invalid'
            example.save(validate: false)
            ValidateFalseExample.withSession { session -> session.flush() }
        }

        then:
        noExceptionThrown()
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.get(id).text } == 'invalid'
    }

    void "save with validate false without flush stays exempt from validation until the session is flushed"() {
        when: 'the instance is changed again after save(validate: false) but before the flush'
        ValidateFalseExample.withNewTransaction {
            ValidateFalseExample example = new ValidateFalseExample(text: 'invalid').save(validate: false)
            example.text = 'invalid again'
            ValidateFalseExample.withSession { session -> session.flush() }
        }

        then:
        noExceptionThrown()
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.findByText('invalid again') } != null
    }

    // Until docbee.8 this feature asserted that the flush validates the changed instance again and rejects
    // it ("save with validation still validates again on flush after the instance became invalid"). Since
    // docbee.9 a save without flush validates once, as GORM for Hibernate 5 (Grails 6) did.
    void "save with validation without flush is not validated again on flush after the instance became invalid"() {
        when:
        ValidateFalseExample.withNewTransaction {
            ValidateFalseExample example = new ValidateFalseExample(text: 'valid').save()
            example.text = 'invalid'
            ValidateFalseExample.withSession { session -> session.flush() }
        }

        then:
        noExceptionThrown()
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.findByText('invalid') } != null
    }

    void "save with validation without flush of an instance that became invalid is rejected by the next save"() {
        when:
        ValidateFalseExample.withNewTransaction {
            ValidateFalseExample example = new ValidateFalseExample(text: 'valid').save()
            example.text = 'invalid'
            example.save()
            ValidateFalseExample.withSession { session -> session.flush() }
        }

        then:
        Exception e = thrown()
        isValidationFailure(e)
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.count() } == 0
    }

    void "save with validate false and flush validates again on a later flush after a change"() {
        when:
        ValidateFalseExample.withNewTransaction {
            ValidateFalseExample example = new ValidateFalseExample(text: 'invalid').save(validate: false, flush: true)
            example.text = 'invalid again'
            ValidateFalseExample.withSession { session -> session.flush() }
        }

        then:
        Exception e = thrown()
        isValidationFailure(e)
        ValidateFalseExample.withNewTransaction { ValidateFalseExample.count() } == 0
    }

    private static boolean isValidationFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.cause) {
            if (t instanceof ValidationException) {
                return true
            }
        }
        false
    }
}

@Entity
class ValidateFalseExample {

    String text

    static mapping = {
        id generator: 'sequence'
    }

    static constraints = {
        text validator: { String val, obj ->
            if (val?.startsWith('invalid')) {
                return ['invalidText']
            }
        }
    }
}
