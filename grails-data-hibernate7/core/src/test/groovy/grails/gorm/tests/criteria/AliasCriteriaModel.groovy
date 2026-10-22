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

import grails.gorm.annotation.Entity

/**
 * Shared domain model of the alias criteria specs.
 *
 * <pre>
 * T1 'A' -> C1 -> M1 (active), links L1, L2
 * T2 'B' -> C2 -> M2 (inactive)
 * T3 'Z' -> no customer, link L3
 * T4 'Z' -> C3 -> no mandant
 * T5 'X' -> no customer
 * </pre>
 */
class AliasCriteriaModel {

    static final List<Class> DOMAIN_CLASSES = [AliasMandant, AliasCustomer, AliasLink, AliasTicket].asImmutable()

    static void seed() {
        def m1 = new AliasMandant(name: 'M1', active: true).save()
        def m2 = new AliasMandant(name: 'M2', active: false).save()
        def c1 = new AliasCustomer(name: 'C1', mandant: m1).save()
        def c2 = new AliasCustomer(name: 'C2', mandant: m2).save()
        def c3 = new AliasCustomer(name: 'C3').save()
        new AliasTicket(code: 'T1', title: 'A', customer: c1, prio: 1)
                .addToLinks(name: 'L1').addToLinks(name: 'L2').save()
        new AliasTicket(code: 'T2', title: 'B', customer: c2, prio: 2).save()
        new AliasTicket(code: 'T3', title: 'Z').addToLinks(name: 'L3').save()
        new AliasTicket(code: 'T4', title: 'Z', customer: c3, prio: 3).save()
        new AliasTicket(code: 'T5', title: 'X').save(flush: true)
    }
}

@Entity
class AliasMandant {
    String name
    Boolean active
}

@Entity
class AliasCustomer {
    String name
    AliasMandant mandant

    static constraints = {
        mandant nullable: true
    }
}

@Entity
class AliasLink {
    String name

    static belongsTo = [ticket: AliasTicket]
}

@Entity
class AliasTicket {
    String code
    String title
    AliasCustomer customer
    Integer prio

    static hasMany = [links: AliasLink]

    static constraints = {
        customer nullable: true
        prio nullable: true
    }
}
