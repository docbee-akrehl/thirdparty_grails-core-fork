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
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.SingularAttribute;

/**
 * Resolves string paths and aliases into JPA Expressions and Paths.
 *
 * @since 8.0
 */
public class ExpressionResolver {
    private final AliasRegistry aliasRegistry;
    private final JoinTracker joinTracker;
    private final Map<String, String> associationPaths = new HashMap<>();

    public ExpressionResolver(AliasRegistry aliasRegistry, JoinTracker joinTracker) {
        this.aliasRegistry = aliasRegistry;
        this.joinTracker = joinTracker;
    }

    public Expression<?> resolve(String path) {
        if (path == null) return null;

        if (path.equals("{alias}") || path.equals("root")) {
            return joinTracker.getRoot();
        }
        if (path.startsWith("{alias}.")) {
            return getPath(joinTracker.getRoot(), path.substring(8));
        }
        
        String cleanPath = path;
        if (cleanPath.startsWith("root.")) {
            cleanPath = cleanPath.substring(5);
        }

        // 0. An alias defined in this query shadows an alias of the same name of an enclosing query
        if (aliasRegistry.getLocalDefinition(cleanPath) != null) {
            return resolveFromAlias(cleanPath, null);
        }

        // 1. Check for materialized aliases (Materialized Selects/Joins)
        if (aliasRegistry.hasRealized(cleanPath)) {
            return aliasRegistry.getRealized(cleanPath);
        }

        // 2. Handle defined but unrealized aliases
        if (aliasRegistry.isDefined(cleanPath)) {
            return resolveFromAlias(cleanPath, null);
        }

        // 3. Handle alias:property
        if (cleanPath.contains(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR)) {
            String[] parts = cleanPath.split(grails.orm.HibernateCriteriaBuilder.ALIAS_SEPARATOR);
            Expression<?> resolved = resolveFromAlias(parts[0], parts[1]);
            if (resolved == null) {
                resolved = getPath(joinTracker.getRoot(), parts[1]);
                if (resolved != null) {
                    resolved.alias(parts[0]);
                    aliasRegistry.realize(parts[0], resolved);
                }
            }
            return resolved;
        }

        // 4. The identifier of a to-one association named without an alias is its foreign key column
        Path<?> foreignKey = implicitAssociationIdentifier(cleanPath);
        if (foreignKey != null) {
            return foreignKey;
        }

        // 5. Handle alias.property
        if (cleanPath.contains(".")) {
            int dotIdx = cleanPath.indexOf(".");
            String aliasCandidate = cleanPath.substring(0, dotIdx);
            if (aliasRegistry.isDefined(aliasCandidate)) {
                return resolveFromAlias(aliasCandidate, cleanPath.substring(dotIdx + 1));
            }
        }

        // 6. Check Join Tracker (Already joined paths)
        From<?, ?> joined = joinTracker.getJoin(cleanPath);
        if (joined != null) {
            return joined;
        }

        // 7. Fallback to Root path resolution
        return getPath(joinTracker.getRoot(), cleanPath);
    }

    private Expression<?> resolveFromAlias(String alias, String subPath) {
        // 0. An alias defined in this query is joined in this query, even if an enclosing query has an alias or
        //    a join of the same name: a subquery is only correlated through aliases it does not define itself
        HibernateAlias localDefinition = aliasRegistry.getLocalDefinition(alias);
        if (localDefinition != null && localDefinition.path() != null && aliasRegistry.getLocalRealized(alias) == null) {
            From<?, ?> join = joinTracker.getLocalJoin(alias);
            if (join != null) {
                aliasRegistry.realize(alias, join);
            } else {
                join = joinAlias(alias, localDefinition);
            }
            return subPath != null ? getPath(join, subPath) : join;
        }

        // 1. Check if already realized in THIS or PARENT registry
        Expression<?> aliased = aliasRegistry.getRealized(alias);
        if (aliased != null) {
            if (subPath != null && aliased instanceof From<?, ?> from) {
                return getPath(from, subPath);
            }
            return aliased; // It's a non-From expression (projection alias) or subPath is null
        }

        // 2. Check if already joined in THIS or PARENT JoinTracker (correlated joins)
        aliased = joinTracker.getJoin(alias);
        if (aliased != null) {
            aliasRegistry.realize(alias, aliased);
            return subPath != null ? getPath((From<?, ?>) aliased, subPath) : aliased;
        }

        // 3. If defined but not joined, materialize it on the CURRENT root
        if (aliasRegistry.isDefined(alias)) {
            HibernateAlias def = aliasRegistry.getDefinition(alias);
            if (def != null && def.path() != null) {
                From<?, ?> join = joinAlias(alias, def);
                return subPath != null ? getPath(join, subPath) : join;
            }
        }

        return null;
    }

    /**
     * Joins the association path of an alias with the alias' join type. The path may start with another alias
     * of this query ({@code c.mandant}), at any depth, or be the full association path from the root
     * ({@code customer.mandant}); a segment of a full path that is the association path of another alias reuses
     * that alias' join, as Hibernate 5's criteria did.
     */
    private From<?, ?> joinAlias(String alias, HibernateAlias def) {
        From<?, ?> from = joinTracker.getRoot();
        String associationPath = null;
        String path = def.path();
        int dot = path.indexOf('.');
        if (dot > 0) {
            String head = path.substring(0, dot);
            if (!head.equals(alias) && aliasRegistry.getLocalDefinition(head) != null &&
                    resolveFromAlias(head, null) instanceof From<?, ?> aliasFrom) {
                from = aliasFrom;
                associationPath = associationPaths.getOrDefault(head, head);
                path = path.substring(dot + 1);
            }
        }
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            associationPath = associationPath == null ? segments[i] : associationPath + '.' + segments[i];
            boolean last = i == segments.length - 1;
            From<?, ?> existing = last ? null : joinOfAssociationPath(associationPath, alias);
            if (existing != null) {
                from = existing;
            } else {
                from = from.join(segments[i], def.joinType());
                if (!last) {
                    registerAssociationPath(associationPath, from);
                }
            }
        }
        aliasRegistry.realize(alias, from);
        joinTracker.addJoin(alias, from);
        associationPaths.put(alias, associationPath);
        registerAssociationPath(associationPath, from);
        return from;
    }

    /**
     * Returns the join of an association path in this query: one already made, or the join of another alias of
     * this query created for exactly that path.
     */
    private From<?, ?> joinOfAssociationPath(String associationPath, String joiningAlias) {
        From<?, ?> existing = joinTracker.getLocalJoin(associationPath);
        if (existing != null) {
            return existing;
        }
        String owner = aliasRegistry.findLocalAliasForPath(associationPath);
        if (owner != null && !owner.equals(joiningAlias) && resolveFromAlias(owner, null) instanceof From<?, ?> ownerFrom) {
            return ownerFrom;
        }
        return null;
    }

    /**
     * Makes the join of an alias also the join of its association path, so the association referenced by its
     * property name, for example {@code customer.name} next to {@code createAlias('customer', 'c')}, reuses the
     * alias' join instead of joining the association a second time, as Hibernate 5's criteria did.
     */
    private void registerAssociationPath(String path, From<?, ?> join) {
        if (joinTracker.getLocalJoin(path) == null) {
            joinTracker.addJoin(path, join);
        }
    }

    /**
     * Resolves {@code association.identifier}, where the association is a to-one association of this query's
     * root named without an alias, to the foreign key column, as Hibernate 5's criteria did: the associated table
     * is not joined, so rows without the association are kept ({@code isNull('category.id')} finds them, and
     * {@code or} / {@code not} work on them). The identifier may have a mapped name ({@code owner.code}), and
     * {@code id} stands for it when the associated entity has no property named {@code id}. Any other property
     * of the association ({@code category.name}) still joins it through its implicit alias, and an alias
     * created with {@code createAlias} is not affected.
     *
     * @return the foreign key path, or {@code null} if the path is not the identifier of such an association
     */
    private Path<?> implicitAssociationIdentifier(String path) {
        int dot = path.indexOf('.');
        if (dot <= 0 || path.indexOf('.', dot + 1) >= 0) {
            return null;
        }
        String association = path.substring(0, dot);
        if (!aliasRegistry.isImplicit(association)) {
            return null;
        }
        From<?, ?> root = joinTracker.getRoot();
        if (root == null) {
            return null;
        }
        return toOneIdentifier(root, association, path.substring(dot + 1));
    }

    /**
     * Returns the path of the identifier of a to-one association of {@code from}, which Hibernate renders as the
     * foreign key column of {@code from}'s table, or {@code null} if {@code property} is not that identifier.
     */
    private static Path<?> toOneIdentifier(From<?, ?> from, String association, String property) {
        Path<?> associationPath;
        try {
            associationPath = from.get(association);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(associationPath.getModel() instanceof SingularAttribute<?, ?> attribute) ||
                (attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.MANY_TO_ONE &&
                        attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.ONE_TO_ONE) ||
                !(attribute.getType() instanceof IdentifiableType<?> target) ||
                !target.hasSingleIdAttribute()) {
            return null;
        }
        SingularAttribute<?, ?> identifier = null;
        boolean hasIdProperty = false;
        for (SingularAttribute<?, ?> candidate : target.getSingularAttributes()) {
            if (candidate.isId()) {
                identifier = candidate;
            }
            if ("id".equals(candidate.getName())) {
                hasIdProperty = true;
            }
        }
        if (identifier == null ||
                !(identifier.getName().equals(property) || ("id".equals(property) && !hasIdProperty))) {
            return null;
        }
        return associationPath.get(identifier.getName());
    }

    private Path<?> getPath(From<?, ?> from, String path) {
        if (from == null || path == null) return null;
        try {
            if (path.contains(".")) {
                String[] parts = path.split("\\.");
                Path<?> p = from;
                for (String part : parts) {
                    p = p.get(part);
                }
                return p;
            }
            return from.get(path);
        } catch (Exception e) {
            return null;
        }
    }
}
