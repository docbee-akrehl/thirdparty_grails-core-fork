/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.orm.hibernate.query

import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.JoinType
import spock.lang.Specification

/**
 * Unit tests for AliasRegistry.
 */
class AliasRegistrySpec extends Specification {

    def "test define and check alias"() {
        given:
        def registry = new AliasRegistry()
        def definition = new HibernateAlias("face", "f", JoinType.INNER)

        when:
        registry.define("f", definition)

        then:
        registry.isDefined("f")
        registry.getDefinition("f") == definition
        !registry.hasRealized("f")
    }

    def "test realize and check alias"() {
        given:
        def registry = new AliasRegistry()
        def expression = Mock(Expression)

        when:
        registry.realize("f", expression)

        then:
        registry.hasRealized("f")
        registry.getRealized("f") == expression
    }

    def "test parent delegation for definitions"() {
        given:
        def parent = new AliasRegistry()
        def child = new AliasRegistry(parent)
        def definition = new HibernateAlias("face", "f", JoinType.INNER)

        when:
        parent.define("f", definition)

        then:
        child.isDefined("f")
        child.getDefinition("f") == definition
    }

    def "test parent delegation for realization"() {
        given:
        def parent = new AliasRegistry()
        def child = new AliasRegistry(parent)
        def expression = Mock(Expression)

        when:
        parent.realize("f", expression)

        then:
        child.hasRealized("f")
        child.getRealized("f") == expression
    }

    def "test child override"() {
        given:
        def parent = new AliasRegistry()
        def child = new AliasRegistry(parent)
        def parentExpr = Mock(Expression)
        def childExpr = Mock(Expression)

        when:
        parent.realize("f", parentExpr)
        child.realize("f", childExpr)

        then:
        child.getRealized("f") == childExpr
        parent.getRealized("f") == parentExpr
    }

    def "a subquery registry does not inherit the association path aliases of the enclosing query"() {
        given:
        def parent = new AliasRegistry()
        def explicitDefinition = new HibernateAlias("customer", "c", JoinType.INNER)
        def pathDefinition = new HibernateAlias("mandant", "mandant", JoinType.LEFT)
        parent.define("c", explicitDefinition)
        parent.realize("c", Mock(Expression))
        parent.defineImplicit("customer")
        parent.realize("customer", Mock(Expression))
        parent.define("mandant", pathDefinition)
        def subquery = new AliasRegistry(parent, true)
        def association = new AliasRegistry(parent)

        expect: 'the explicit alias is visible in the subquery, the association paths are not'
        subquery.isDefined("c")
        subquery.getDefinition("c") == explicitDefinition
        subquery.hasRealized("c")
        !subquery.hidesInheritedAlias("c")
        !subquery.isDefined("customer")
        !subquery.hasRealized("customer")
        subquery.getRealized("customer") == null
        subquery.hidesInheritedAlias("customer")
        !subquery.isDefined("mandant")
        subquery.getDefinition("mandant") == null

        and: 'a registry that does not scope association paths, as of an association block, inherits them all'
        association.isDefined("customer")
        association.hasRealized("customer")
        association.getDefinition("mandant") == pathDefinition
        !association.hidesInheritedAlias("customer")
    }

    def "an alias defined in the subquery itself is never hidden"() {
        given:
        def parent = new AliasRegistry()
        parent.defineImplicit("customer")
        def subquery = new AliasRegistry(parent, true)

        when:
        subquery.defineImplicit("customer")

        then:
        subquery.isDefined("customer")
        !subquery.hidesInheritedAlias("customer")
    }
}
