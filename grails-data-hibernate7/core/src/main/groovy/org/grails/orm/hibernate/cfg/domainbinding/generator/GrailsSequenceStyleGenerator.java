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
package org.grails.orm.hibernate.cfg.domainbinding.generator;

import java.io.Serial;
import java.util.Optional;
import java.util.Properties;

import org.hibernate.MappingException;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.boot.model.relational.internal.SqlStringGenerationContextImpl;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.generator.GeneratorCreationContext;
import org.hibernate.id.PersistentIdentifierGenerator;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.hibernate.service.ServiceRegistry;

import org.grails.orm.hibernate.cfg.HibernateSimpleIdentity;

@SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
public class GrailsSequenceStyleGenerator extends SequenceStyleGenerator {

    /**
     * The setting that replaces the default increment size of a sequence whose mapping gives no
     * {@code increment_size}. Not set, such a sequence gets an increment size of {@value #DEFAULT_INCREMENT_SIZE}
     * with the {@value #DEFAULT_OPTIMIZER} optimizer. Set to {@code 1}, every id draws its own value from the
     * sequence, without a pooled optimizer, as GORM for Hibernate 5 did. Set to a larger value, that value is the
     * increment size of the {@value #DEFAULT_OPTIMIZER} optimizer. An {@code increment_size} or {@code optimizer}
     * in the mapping always wins.
     */
    public static final String DEFAULT_INCREMENT_SIZE_SETTING = "hibernate.grails.id.sequence.default_increment_size";

    /** The increment size of a sequence whose mapping gives none, when the setting is not set. */
    public static final String DEFAULT_INCREMENT_SIZE = "50";

    /** The optimizer of a sequence whose mapping gives none, unless the increment size is 1. */
    public static final String DEFAULT_OPTIMIZER = "pooled-lo";

    @Serial
    private static final long serialVersionUID = 1L;

    public GrailsSequenceStyleGenerator(
        GeneratorCreationContext context, HibernateSimpleIdentity mappedId, JdbcEnvironment jdbcEnvironment) {
        Properties generatorProps =
                Optional.ofNullable(mappedId).map(HibernateSimpleIdentity::getProperties).orElse(new Properties());

        applyDefaultIncrementSize(generatorProps, configuredDefaultIncrementSize(context));
        // Without an explicit 'sequence' mapping param, Hibernate's own naming strategy falls back to
        // deriving an implicit sequence name from the target table - but only if it is told what that
        // table is. Hibernate's standard generator-creation path supplies this itself; the Grails-specific
        // path that constructs this generator directly does not, so without it every implicit sequence
        // mapping fails session factory bootstrap with "Unable to determine implicit sequence name for
        // target table 'null'". Only resolved when actually needed, since getRootClass() is not always
        // available (e.g. a component/embedded identifier's generator).
        if (!hasExplicitSequenceName(generatorProps) && context.getRootClass() != null) {
            generatorProps.putIfAbsent(PersistentIdentifierGenerator.TABLE, context.getRootClass().getTable().getName());
        }

        this.configure(context, generatorProps);

        if (jdbcEnvironment != null) {
            var database = context.getDatabase();
            if (getDatabaseStructure() != null) {
                this.registerExportables(database);
            }

            var physicalName = database.getDefaultNamespace().getPhysicalName();

            String catalog =
                    (physicalName.catalog() != null) ? physicalName.catalog().getCanonicalName() : null;

            String schema =
                    (physicalName.schema() != null) ? physicalName.schema().getCanonicalName() : null;

            if (getDatabaseStructure() != null) {
                SqlStringGenerationContext sqlContext =
                        SqlStringGenerationContextImpl.fromExplicit(jdbcEnvironment, database, catalog, schema);
                this.initialize(sqlContext);
            }
        }
    }

    /**
     * Supplies the increment size and optimizer a mapping does not give itself. Without a configured default (and
     * whenever the mapping gives an {@code increment_size}), that is increment {@value #DEFAULT_INCREMENT_SIZE} with
     * the {@value #DEFAULT_OPTIMIZER} optimizer. With a configured default the mapping's missing increment size is
     * that default; for {@code 1} no optimizer is supplied, so Hibernate draws one sequence value per id
     * (its implicit optimizer for an increment size of 1 is {@code none}).
     */
    private static void applyDefaultIncrementSize(Properties generatorProps, Integer configuredIncrementSize) {
        if (configuredIncrementSize == null || generatorProps.containsKey(INCREMENT_PARAM)) {
            generatorProps.putIfAbsent(INCREMENT_PARAM, DEFAULT_INCREMENT_SIZE);
            generatorProps.putIfAbsent(OPT_PARAM, DEFAULT_OPTIMIZER);
            return;
        }
        generatorProps.setProperty(INCREMENT_PARAM, String.valueOf(configuredIncrementSize));
        if (configuredIncrementSize > 1) {
            generatorProps.putIfAbsent(OPT_PARAM, DEFAULT_OPTIMIZER);
        }
    }

    /**
     * The value of {@link #DEFAULT_INCREMENT_SIZE_SETTING} among the settings of the session factory being built,
     * or {@code null} when it is not set.
     *
     * @throws MappingException when the setting is not a whole number of at least 1
     */
    private static Integer configuredDefaultIncrementSize(GeneratorCreationContext context) {
        ServiceRegistry serviceRegistry = context.getServiceRegistry();
        ConfigurationService configurationService =
                serviceRegistry != null ? serviceRegistry.getService(ConfigurationService.class) : null;
        if (configurationService == null) {
            return null;
        }
        Object value = configurationService.getSettings().get(DEFAULT_INCREMENT_SIZE_SETTING);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        int incrementSize;
        try {
            incrementSize = Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw invalidDefaultIncrementSize(value, e);
        }
        if (incrementSize < 1) {
            throw invalidDefaultIncrementSize(value, null);
        }
        return incrementSize;
    }

    private static MappingException invalidDefaultIncrementSize(Object value, Exception cause) {
        String message = "Setting '" + DEFAULT_INCREMENT_SIZE_SETTING +
                "' must be a whole number of at least 1, but was '" + value + "'";
        return new MappingException(message, cause);
    }

    /**
     * Whether the mapping named the sequence itself, resolved the way {@link SequenceStyleGenerator} resolves
     * it: {@code sequence_name} when present, otherwise {@code sequence}, and a blank name counts as no name
     * at all. Anything the generator would treat as unnamed still needs the target table to derive one.
     */
    private static boolean hasExplicitSequenceName(Properties generatorProps) {
        Object sequenceName = generatorProps.get(SEQUENCE_PARAM);
        if (sequenceName == null) {
            sequenceName = generatorProps.get(ALT_SEQUENCE_PARAM);
        }
        return sequenceName != null && !sequenceName.toString().isEmpty();
    }

    @Override
    public void initialize(SqlStringGenerationContext context) {
        super.initialize(context);
    }
}
