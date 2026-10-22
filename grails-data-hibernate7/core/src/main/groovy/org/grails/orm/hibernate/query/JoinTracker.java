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

import jakarta.persistence.criteria.From;

/**
 * Tracks physical JPA Joins and Roots to ensure consistency and avoid duplicate joins.
 *
 * <p>Joins are registered either under an alias ({@link #addJoin}) or under an association path
 * ({@link #addAssociationPathJoin}). A tracker of a subquery that scopes association paths sees only the aliases
 * of the enclosing query, not its association paths: an association path of the subquery, such as
 * {@code customer} in {@code customer { … }}, refers to the subquery's own root, as in Hibernate 5's criteria.
 *
 * @since 8.0
 */
public class JoinTracker {
    private final Map<String, From<?, ?>> joinsByPath = new HashMap<>();
    private final Map<String, From<?, ?>> joinsByAssociationPath = new HashMap<>();
    private From<?, ?> root;
    private final JoinTracker parent;
    private final boolean scopesAssociationPaths;

    public JoinTracker(From<?, ?> root) {
        this(null, root, false);
    }

    public JoinTracker(JoinTracker parent, From<?, ?> root) {
        this(parent, root, false);
    }

    /**
     * @param parent the tracker of the enclosing query or association, or {@code null}
     * @param root the root of this context
     * @param scopesAssociationPaths whether the association paths of the enclosing query are hidden from this
     *        tracker, as for a subquery; its aliases stay visible
     */
    public JoinTracker(JoinTracker parent, From<?, ?> root, boolean scopesAssociationPaths) {
        this.root = root;
        this.parent = parent;
        this.scopesAssociationPaths = scopesAssociationPaths;
    }

    public void addJoin(String path, From<?, ?> from) {
        joinsByPath.put(path, from);
    }

    /**
     * Registers the join of an association path, such as {@code customer} or {@code customer.mandant}, relative
     * to the root of this context. Unlike an alias, it is not visible in a subquery that scopes association paths.
     *
     * @param path the association path
     * @param from the join
     */
    public void addAssociationPathJoin(String path, From<?, ?> from) {
        joinsByAssociationPath.put(path, from);
    }

    public From<?, ?> getJoin(String path) {
        From<?, ?> join = getLocalJoin(path);
        if (join == null && parent != null) {
            return scopesAssociationPaths ? parent.getAliasJoin(path) : parent.getJoin(path);
        }
        return join;
    }

    /**
     * Returns the join registered under an alias in this context or an enclosing one, ignoring association paths.
     */
    private From<?, ?> getAliasJoin(String alias) {
        From<?, ?> join = joinsByPath.get(alias);
        if (join == null && parent != null) {
            return parent.getAliasJoin(alias);
        }
        return join;
    }

    /**
     * Returns the join registered for the path in this context only, without looking at the enclosing query.
     *
     * @param path the path or alias of the join
     * @return the join, or {@code null} if none is registered in this context
     */
    public From<?, ?> getLocalJoin(String path) {
        From<?, ?> join = joinsByPath.get(path);
        return join != null ? join : joinsByAssociationPath.get(path);
    }

    public From<?, ?> getRoot() {
        return root;
    }

    public void setRoot(From<?, ?> root) {
        this.root = root;
    }
}
