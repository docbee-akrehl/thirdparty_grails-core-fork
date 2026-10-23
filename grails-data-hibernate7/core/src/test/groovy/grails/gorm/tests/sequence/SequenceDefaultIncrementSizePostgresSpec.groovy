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
package grails.gorm.tests.sequence

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Requires
import spock.lang.Shared

import grails.gorm.tests.HibernateGormDatastoreSpec

/**
 * {@link SequenceDefaultIncrementSizeSpec} on PostgreSQL.
 */
@Testcontainers
@Requires({ HibernateGormDatastoreSpec.isDockerAvailable() })
class SequenceDefaultIncrementSizePostgresSpec extends SequenceDefaultIncrementSizeSpec {

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
}
