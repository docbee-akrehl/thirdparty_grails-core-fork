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
package org.grails.orm.hibernate.query;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.criteria.Expression;

/**
 * Registry for user-defined and auto-generated aliases in a JPA query.
 *
 * <p>The registry of a subquery that scopes association paths does not inherit the association path aliases of
 * the enclosing query: the implicit alias of a dotted property path ({@code customer} of {@code customer.name}) and
 * an alias named like its association path ({@code createAlias('customer', 'customer')}). The subquery's own
 * association path of that name refers to the subquery's root, as in Hibernate 5's criteria. Any other alias of
 * the enclosing query stays visible and correlates the subquery with it.
 *
 * @since 8.0
 */
public class AliasRegistry {
    private final Map<String, HibernateAlias> definitions = new HashMap<>();
    private final Map<String, Expression<?>> realizedExpressions = new HashMap<>();
    private final Set<String> implicitAliases = new HashSet<>();
    private final AliasRegistry parent;
    private final boolean scopesAssociationPaths;

    public AliasRegistry() {
        this(null, false);
    }

    public AliasRegistry(AliasRegistry parent) {
        this(parent, false);
    }

    /**
     * @param parent the registry of the enclosing query or association, or {@code null}
     * @param scopesAssociationPaths whether the association path aliases of the enclosing query are hidden from
     *        this registry, as for a subquery
     */
    public AliasRegistry(AliasRegistry parent, boolean scopesAssociationPaths) {
        this.parent = parent;
        this.scopesAssociationPaths = scopesAssociationPaths;
    }

    /**
     * Returns whether an alias of the enclosing query is hidden from this registry because it is an association
     * path alias and this registry scopes association paths. An alias defined or realized in this registry is
     * never hidden.
     *
     * @param alias the alias
     * @return {@code true} if the enclosing query's alias of that name is not visible here
     */
    public boolean hidesInheritedAlias(String alias) {
        return scopesAssociationPaths && parent != null && !definitions.containsKey(alias) &&
                !realizedExpressions.containsKey(alias) && parent.isAssociationPathAlias(alias);
    }

    /**
     * Returns whether the nearest definition of the alias is an association path alias: an implicit alias or an
     * alias named like its association path.
     */
    private boolean isAssociationPathAlias(String alias) {
        if (definitions.containsKey(alias)) {
            HibernateAlias definition = definitions.get(alias);
            return implicitAliases.contains(alias) || (definition != null && alias.equals(definition.path()));
        }
        if (realizedExpressions.containsKey(alias)) {
            return false;
        }
        return parent != null && parent.isAssociationPathAlias(alias);
    }

    private boolean inherits(String alias) {
        return parent != null && !hidesInheritedAlias(alias);
    }

    public void define(String alias, HibernateAlias definition) {
        definitions.put(alias, definition);
    }

    /**
     * Defines the alias a criterion on a dotted property path such as {@code category.name} implies for the
     * association it starts with, as opposed to an alias created with {@code createAlias}.
     *
     * @param alias the association name, which is the alias and its path
     */
    public void defineImplicit(String alias) {
        definitions.put(alias, new HibernateAlias(alias, alias));
        implicitAliases.add(alias);
    }

    /**
     * Returns whether the alias is the implicit alias of an association named in a dotted property path, in this
     * or an enclosing query, rather than one created with {@code createAlias}.
     *
     * @param alias the alias
     * @return {@code true} if the nearest definition of the alias is implicit
     */
    public boolean isImplicit(String alias) {
        if (definitions.containsKey(alias)) {
            return implicitAliases.contains(alias);
        }
        return parent != null && parent.isImplicit(alias);
    }

    public void realize(String alias, Expression<?> expression) {
        realizedExpressions.put(alias, expression);
    }

    public boolean isDefined(String alias) {
        if (definitions.containsKey(alias) || realizedExpressions.containsKey(alias)) {
            return true;
        }
        if (inherits(alias)) {
            return parent.isDefined(alias);
        }
        return false;
    }

    public HibernateAlias getDefinition(String alias) {
        HibernateAlias def = definitions.get(alias);
        if (def == null && inherits(alias)) {
            return parent.getDefinition(alias);
        }
        return def;
    }

    /**
     * Returns the definition of an alias created in this query, without looking at the enclosing query.
     *
     * @param alias the alias
     * @return the definition, or {@code null} if the alias is not defined in this query
     */
    public HibernateAlias getLocalDefinition(String alias) {
        return definitions.get(alias);
    }

    /**
     * Returns an alias created in this query for exactly the given association path.
     *
     * @param path the association path
     * @return the alias, or {@code null} if no alias of this query was created for the path
     */
    public String findLocalAliasForPath(String path) {
        for (Map.Entry<String, HibernateAlias> entry : definitions.entrySet()) {
            HibernateAlias definition = entry.getValue();
            if (definition != null && path.equals(definition.path()) && !path.equals(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Returns the expression an alias was realized as in this query, without looking at the enclosing query.
     *
     * @param alias the alias
     * @return the expression, or {@code null} if the alias was not realized in this query
     */
    public Expression<?> getLocalRealized(String alias) {
        return realizedExpressions.get(alias);
    }

    public Expression<?> getRealized(String alias) {
        Expression<?> expr = realizedExpressions.get(alias);
        if (expr == null && inherits(alias)) {
            return parent.getRealized(alias);
        }
        return expr;
    }

    public boolean hasRealized(String alias) {
        return realizedExpressions.containsKey(alias) || (inherits(alias) && parent.hasRealized(alias));
    }
}
