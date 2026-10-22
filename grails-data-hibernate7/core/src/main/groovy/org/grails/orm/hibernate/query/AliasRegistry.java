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
import java.util.Map;

import jakarta.persistence.criteria.Expression;

/**
 * Registry for user-defined and auto-generated aliases in a JPA query.
 *
 * @since 8.0
 */
public class AliasRegistry {
    private final Map<String, HibernateAlias> definitions = new HashMap<>();
    private final Map<String, Expression<?>> realizedExpressions = new HashMap<>();
    private final AliasRegistry parent;

    public AliasRegistry() {
        this.parent = null;
    }

    public AliasRegistry(AliasRegistry parent) {
        this.parent = parent;
    }

    public void define(String alias, HibernateAlias definition) {
        definitions.put(alias, definition);
    }

    public void realize(String alias, Expression<?> expression) {
        realizedExpressions.put(alias, expression);
    }

    public boolean isDefined(String alias) {
        if (definitions.containsKey(alias) || realizedExpressions.containsKey(alias)) {
            return true;
        }
        if (parent != null) {
            return parent.isDefined(alias);
        }
        return false;
    }

    public HibernateAlias getDefinition(String alias) {
        HibernateAlias def = definitions.get(alias);
        if (def == null && parent != null) {
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

    public Expression<?> getRealized(String alias) {
        Expression<?> expr = realizedExpressions.get(alias);
        if (expr == null && parent != null) {
            return parent.getRealized(alias);
        }
        return expr;
    }

    public boolean hasRealized(String alias) {
        return realizedExpressions.containsKey(alias) || (parent != null && parent.hasRealized(alias));
    }
}
