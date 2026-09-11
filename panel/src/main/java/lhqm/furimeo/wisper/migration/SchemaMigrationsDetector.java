package lhqm.furimeo.wisper.migration;

import java.util.Set;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

/**
 * Tells Spring Boot that {@link SchemaMigrations} is what initialises this database.
 *
 * <p>Boot already knows how to hold a bean back until the schema exists: annotate it
 * {@code @DependsOnDatabaseInitialization} and it is created after every detected
 * initialiser. db-scheduler's {@code Scheduler} bean carries that annotation, and so
 * should any bean here that reads from the database while it is being built. None of
 * that works unless something declares which bean does the initialising, which is this.
 *
 * <p>Registered through {@code META-INF/spring.factories}; Boot loads detectors with
 * {@code SpringFactoriesLoader}, not from the bean factory, so a {@code @Component}
 * annotation here would do nothing.
 */
public class SchemaMigrationsDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {

    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(SchemaMigrations.class);
    }
}
